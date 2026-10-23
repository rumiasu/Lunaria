package cn.lunaria.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** All transport replies are local fixtures. No HTTP, real code, token or secret is used. */
class WechatClientsContractTest {
    private final ObjectMapper json=new ObjectMapper();
    private final WechatAppSettings configured=new WechatAppSettings(true,"fixture-app","fixture-secret&only");
    static class FakeTransport implements WechatTransport {
        record Call(String method,URI uri,String body) {}
        final List<Call> calls=new ArrayList<>();final Deque<Reply> replies=new ArrayDeque<>();
        FakeTransport reply(String body){replies.add(new Reply(200,body));return this;}
        public Reply request(String method,URI uri,String body){calls.add(new Call(method,uri,body));return replies.remove();}
    }
    private void fails(Runnable action,int status,String code){assertThatThrownBy(action::run).isInstanceOfSatisfying(ProviderFailure.class,e->{assertThat(e.status).isEqualTo(status);assertThat(e.code).isEqualTo(code);assertThat(e.getMessage()).doesNotContain("fixture-secret","private-session","provider debug");assertThat(e.getCause()).isNull();});}
    @Test void codeExchangeMapsOfficialEndpointAndDiscardsSessionKey(){
        var transport=new FakeTransport().reply("{\"openid\":\"fixture-open\",\"session_key\":\"private-session\"}");
        var identity=new WechatCodeClient(configured,transport,json).exchange("code & fixture");
        assertThat(identity).isEqualTo(new WechatCodeClient.VerifiedIdentity("fixture-app","fixture-open"));
        var call=transport.calls.get(0);assertThat(call.method()).isEqualTo("GET");assertThat(call.uri().getHost()).isEqualTo("api.weixin.qq.com");assertThat(call.uri().getPath()).isEqualTo("/sns/jscode2session");
        assertThat(call.uri().getRawQuery()).contains("secret=fixture-secret%26only","js_code=code+%26+fixture","grant_type=authorization_code");assertThat(identity.toString()).doesNotContain("private-session","fixture-secret");
    }
    @Test void rejectedCodeNeverProducesIdentityOrEchoesProviderDebug(){
        var transport=new FakeTransport().reply("{\"errcode\":40029,\"errmsg\":\"provider debug fixture-secret\"}");
        fails(()->new WechatCodeClient(configured,transport,json).exchange("invalid-fixture"),401,"WECHAT_CODE_REJECTED");
    }
    @Test void malformedAndFailedRepliesAreSanitized(){
        for(String body:List.of("[]","{bad provider debug","{\"session_key\":\"private-session\"}")){
            var transport=new FakeTransport().reply(body);
            assertThatThrownBy(()->new WechatCodeClient(configured,transport,json).exchange("fixture")).isInstanceOfSatisfying(ProviderFailure.class,e->{assertThat(e.status).isEqualTo(502);assertThat(e.getMessage()).doesNotContain("private-session","provider debug");assertThat(e.getCause()).isNull();});
        }
        var transport=new FakeTransport();transport.replies.add(new WechatTransport.Reply(503,"provider debug"));
        fails(()->new WechatCodeClient(configured,transport,json).exchange("fixture"),502,"WECHAT_HTTP_REJECTED");
    }
    @Test void disabledOrIncompleteConfigurationNeverCallsTransport(){
        var transport=new FakeTransport();
        for(var settings:List.of(new WechatAppSettings(false,"fixture-app","fixture-secret"),new WechatAppSettings(true,"fixture-app",""),new WechatAppSettings(true,"touristappid","fixture-secret")))fails(()->new WechatCodeClient(settings,transport,json).exchange("fixture"),503,"INTEGRATION_NOT_CONFIGURED");
        assertThat(transport.calls).isEmpty();assertThat(configured.toString()).doesNotContain("fixture-secret");
    }
    private WechatSubscriptionClient notices(FakeTransport transport,WechatSubscriptionClient.Consent consent){return new WechatSubscriptionClient(configured,transport,json,Clock.fixed(Instant.parse("2026-10-23T10:00:00Z"),ZoneOffset.UTC),consent,(customer,code)->{});}
    private WechatSubscriptionClient.Notice notice(){return new WechatSubscriptionClient.Notice("alice","bound-fixture-open","fixture-template",Map.of("thing1","fixture appointment"));}
    @Test void subscriptionRequiresConsentBeforeEvenFetchingAToken(){
        var transport=new FakeTransport();fails(()->notices(transport,(customer,template)->false).send(notice()),409,"WECHAT_SUBSCRIPTION_REQUIRED");assertThat(transport.calls).isEmpty();
    }
    @Test void subscriptionUsesBoundRecipientAndCachesTokenOnlyInMemory() throws Exception {
        var transport=new FakeTransport().reply("{\"access_token\":\"fixture-access\",\"expires_in\":7200}").reply("{\"errcode\":0}").reply("{\"errcode\":0}");
        var client=notices(transport,(customer,template)->customer.equals("alice")&&template.equals("fixture-template"));
        assertThat(client.send(notice()).providerAccepted()).isTrue();assertThat(client.send(notice()).providerAccepted()).isTrue();assertThat(transport.calls).hasSize(3);
        assertThat(transport.calls.get(0).uri().getPath()).isEqualTo("/cgi-bin/token");var sent=transport.calls.get(1);assertThat(sent.method()).isEqualTo("POST");assertThat(sent.uri().getPath()).isEqualTo("/cgi-bin/message/subscribe/send");
        var body=json.readTree(sent.body());assertThat(body.path("touser").asText()).isEqualTo("bound-fixture-open");assertThat(body.path("template_id").asText()).isEqualTo("fixture-template");assertThat(body.path("data").path("thing1").path("value").asText()).isEqualTo("fixture appointment");
    }
    @Test void subscriptionProviderRejectionDoesNotReturnSuccessOrRetry(){
        var transport=new FakeTransport().reply("{\"access_token\":\"fixture-access\",\"expires_in\":7200}").reply("{\"errcode\":43101,\"errmsg\":\"provider debug\"}");
        fails(()->notices(transport,(c,t)->true).send(notice()),502,"WECHAT_NOTICE_REJECTED");assertThat(transport.calls).hasSize(2);
    }
    @Test void invalidTokenOrTemplateFieldsStopTheSend(){
        var transport=new FakeTransport().reply("{\"errcode\":40013,\"errmsg\":\"provider debug\"}");
        fails(()->notices(transport,(c,t)->true).send(notice()),502,"WECHAT_ACCESS_TOKEN_REJECTED");assertThat(transport.calls).hasSize(1);
        var noCalls=new FakeTransport();fails(()->notices(noCalls,(c,t)->true).send(new WechatSubscriptionClient.Notice("alice","bound-fixture-open","fixture-template",Map.of("bad-key","value"))),400,"WECHAT_NOTICE_INVALID");assertThat(noCalls.calls).isEmpty();
    }
}
