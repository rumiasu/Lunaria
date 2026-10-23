package cn.lunaria;

import cn.lunaria.integration.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.Map;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:lunaria_notice_audit;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
class WechatNotificationAuditTest {
    @Autowired WechatNotificationAudit audit;
    @Autowired JdbcTemplate sql;
    @Autowired PlatformTransactionManager transactions;
    @BeforeEach void clear(){sql.update("delete from audit where action='WECHAT_NOTICE_FAILED'");}
    private WechatSubscriptionClient.Notice notice(){return new WechatSubscriptionClient.Notice("alice","private-openid","fixture-template",Map.of("thing1","private payload"));}
    private WechatSubscriptionClient client(WechatTransport transport,boolean consent,WechatSubscriptionClient.FailureRecorder recorder){return new WechatSubscriptionClient(new WechatAppSettings(true,"fixture-app","private-secret"),transport,new ObjectMapper(),Clock.systemUTC(),(customer,template)->consent,recorder);}
    @Test void providerFailureIsPersistedOnceAndSurvivesCallerRollback(){
        var transport=mock(WechatTransport.class);
        when(transport.request(anyString(),any(),any())).thenReturn(new WechatTransport.Reply(200,"{\"access_token\":\"private-token\",\"expires_in\":7200}"),new WechatTransport.Reply(200,"{\"errcode\":43101,\"errmsg\":\"private provider debug\"}"));
        var sender=client(transport,true,audit);
        assertThatThrownBy(()->new TransactionTemplate(transactions).execute(status->sender.send(notice()))).isInstanceOf(ProviderFailure.class);
        var rows=sql.queryForList("select * from audit where action='WECHAT_NOTICE_FAILED'");
        assertThat(rows).hasSize(1);assertThat(rows.get(0).get("target_id")).isEqualTo("alice");
        assertThat(rows.toString()).contains("WECHAT_NOTICE_REJECTED","automatic_retry=false").doesNotContain("private-","fixture-template");
        verify(transport,times(2)).request(anyString(),any(),any());
    }
    @Test void missingConsentRecordsFailureWithoutCallingWechat(){
        var transport=mock(WechatTransport.class);
        assertThatThrownBy(()->client(transport,false,audit).send(notice())).isInstanceOf(ProviderFailure.class);
        assertThat(sql.queryForObject("select details from audit where action='WECHAT_NOTICE_FAILED'",String.class)).contains("WECHAT_SUBSCRIPTION_REQUIRED");verifyNoInteractions(transport);
    }
    @Test void malformedTargetAndUnexpectedTransportErrorsAreSanitized(){
        audit.record("private-openid/payload","private-debug");
        assertThat(sql.queryForList("select * from audit where action='WECHAT_NOTICE_FAILED'").toString()).contains("unresolved","WECHAT_NOTICE_FAILED").doesNotContain("private-");
        var transport=mock(WechatTransport.class);when(transport.request(anyString(),any(),any())).thenThrow(new IllegalStateException("private-token"));
        assertThatThrownBy(()->client(transport,true,audit).send(notice())).isInstanceOfSatisfying(ProviderFailure.class,e->{assertThat(e.code).isEqualTo("WECHAT_NOTICE_FAILED");assertThat(e.getMessage()).doesNotContain("private-");assertThat(e.getCause()).isNull();});
        assertThat(sql.queryForObject("select count(*) from audit where action='WECHAT_NOTICE_FAILED'",Integer.class)).isEqualTo(2);
    }
    @Test void failedAuditNeverReturnsSuccessOrExposesDatabaseErrors(){
        var transport=mock(WechatTransport.class);
        assertThatThrownBy(()->client(transport,false,(customer,code)->{throw new IllegalStateException("private database settings");}).send(notice())).isInstanceOfSatisfying(ProviderFailure.class,e->assertThat(e.code).isEqualTo("WECHAT_NOTICE_AUDIT_FAILED"));
        verifyNoInteractions(transport);
    }
}
