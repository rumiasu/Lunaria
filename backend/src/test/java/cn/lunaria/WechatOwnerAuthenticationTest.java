package cn.lunaria;

import cn.lunaria.integration.WechatTransport;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:lunaria_owner_auth;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "lunaria.wechat.owner.enabled=true","lunaria.wechat.owner.app-id=fixture-owner-app","lunaria.wechat.owner.app-secret=fixture-owner-secret","lunaria.wechat.owner.open-id=fixture-owner-open"})
@ActiveProfiles("test") @AutoConfigureMockMvc
class WechatOwnerAuthenticationTest {
    @Autowired MockMvc mvc;@Autowired JdbcTemplate sql;
    @MockitoBean WechatTransport transport;@MockitoSpyBean StoreMapper mapper;
    @BeforeEach void isolate(){reset(transport,mapper);sql.update("delete from audit where action='WECHAT_OWNER_LOGIN'");}
    private void reply(String open){when(transport.request(eq("GET"),any(),isNull())).thenReturn(new WechatTransport.Reply(200,"{\"openid\":\""+open+"\",\"session_key\":\"discarded-fixture\"}"));}
    @Test void configuredOwnerGetsOnlyOwnerRoleAndRotatesSession() throws Exception {
        reply("fixture-owner-open");var session=new MockHttpSession();String before=session.getId();
        mvc.perform(post("/api/auth/wechat/owner").session(session).with(csrf()).contentType("application/json").content("{\"code\":\"fixture-code\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.username").value("owner")).andExpect(jsonPath("$.role").value("OWNER"));
        assertThat(session.getId()).isNotEqualTo(before);
        mvc.perform(get("/api/owner/dashboard").session(session)).andExpect(status().isOk());
        mvc.perform(get("/api/me").session(session)).andExpect(status().isForbidden());
        assertThat(sql.queryForObject("select count(*) from audit where action='WECHAT_OWNER_LOGIN'",Integer.class)).isEqualTo(1);
        assertThat(sql.queryForObject("select details from audit where action='WECHAT_OWNER_LOGIN'",String.class)).doesNotContain("fixture-owner-open","fixture-code","discarded-fixture");
    }
    @Test void anyOtherVerifiedIdentityCannotSelfAssignOwnerRole() throws Exception {
        reply("fixture-customer-open");var session=new MockHttpSession();
        mvc.perform(post("/api/auth/wechat/owner").session(session).with(csrf()).contentType("application/json").content("{\"code\":\"fixture-code\",\"role\":\"OWNER\",\"openid\":\"fixture-owner-open\"}"))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("WECHAT_OWNER_NOT_AUTHORIZED"));
        mvc.perform(get("/api/owner/dashboard").session(session)).andExpect(status().isUnauthorized());
        assertThat(sql.queryForObject("select count(*) from audit where action='WECHAT_OWNER_LOGIN'",Integer.class)).isZero();
        assertThat(sql.queryForObject("select balance_cents from customers where id='alice'",Long.class)).isEqualTo(30000);
    }
    @Test void ownerAppIsIndependentFromUnconfiguredCustomerAppAndStatusNeverClaimsProduction() throws Exception {
        reply("fixture-owner-open");
        mvc.perform(get("/api/integrations/status").with(user("owner").roles("OWNER"))).andExpect(status().isOk())
            .andExpect(jsonPath("$.production_ready").value(false)).andExpect(jsonPath("$.services.wechat_owner_identity.state").value("CONFIGURED_UNVERIFIED"))
            .andExpect(jsonPath("$.services.wechat_identity.enabled").value(false));
        mvc.perform(post("/api/auth/wechat").with(csrf()).contentType("application/json").content("{\"code\":\"fixture-code\"}"))
            .andExpect(status().isServiceUnavailable());verifyNoInteractions(transport);
    }
    @Test void csrfAndProviderRejectionCannotGrantOwnerSession() throws Exception {
        mvc.perform(post("/api/auth/wechat/owner").contentType("application/json").content("{\"code\":\"fixture-code\"}"))
            .andExpect(status().isForbidden());verifyNoInteractions(transport);
        when(transport.request(eq("GET"),any(),isNull())).thenReturn(new WechatTransport.Reply(200,"{\"errcode\":40029}"));
        var session=new MockHttpSession();mvc.perform(post("/api/auth/wechat/owner").session(session).with(csrf()).contentType("application/json").content("{\"code\":\"expired-fixture\"}"))
            .andExpect(status().isUnauthorized());mvc.perform(get("/api/owner/dashboard").session(session)).andExpect(status().isUnauthorized());
    }
    @Test void ownerAuditFailureCannotEstablishSuccessfulLogin() throws Exception {
        reply("fixture-owner-open");doThrow(new IllegalStateException("synthetic owner audit failure")).when(mapper).insertAudit(anyMap());
        var session=new MockHttpSession();assertThatThrownBy(()->mvc.perform(post("/api/auth/wechat/owner").session(session).with(csrf()).contentType("application/json").content("{\"code\":\"fixture-code\"}"))).hasRootCauseMessage("synthetic owner audit failure");
        mvc.perform(get("/api/owner/dashboard").session(session)).andExpect(status().isUnauthorized());
    }
}
