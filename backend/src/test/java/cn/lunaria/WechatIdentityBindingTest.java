package cn.lunaria;

import cn.lunaria.integration.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:lunaria_wechat_binding;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","lunaria.wechat.enabled=true","lunaria.wechat.app-id=fixture-app","lunaria.wechat.app-secret=fixture-secret"})
@ActiveProfiles("test") @AutoConfigureMockMvc
class WechatIdentityBindingTest {
    @Autowired MockMvc mvc;@Autowired JdbcTemplate db;
    @MockitoBean WechatTransport transport;
    private final ObjectMapper json=new ObjectMapper();
    @BeforeEach void isolate(){db.update("delete from commands where scope='WECHAT_BIND'");db.update("delete from wechat_identities");db.update("delete from audit where action like 'WECHAT_IDENTITY_%'");reset(transport);when(transport.request(eq("GET"),any(),isNull())).thenReturn(new WechatTransport.Reply(200,"{\"openid\":\"fixture-open\",\"session_key\":\"fixture-discarded\"}"));}
    private ResultActions login(MockHttpSession session) throws Exception{return mvc.perform(post("/api/auth/wechat").session(session).with(csrf()).contentType("application/json").content("{\"code\":\"fixture-code\"}"));}
    private String pending() throws Exception {var result=login(new MockHttpSession()).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("WECHAT_BINDING_REQUIRED")).andReturn();return json.readTree(result.getResponse().getContentAsString()).path("binding_id").asText();}
    private ResultActions bind(String identity,String customer,String key) throws Exception{return mvc.perform(post("/api/owner/wechat-bindings/"+identity).with(user("owner").roles("OWNER")).with(csrf()).header("Idempotency-Key",key).contentType("application/json").content(json.writeValueAsString(java.util.Map.of("customerId",customer,"reason","in-person fixture verification"))));}
    @Test void unboundVerifiedIdentityCannotSeeAssetsOrCreateACustomer() throws Exception {
        long customers=db.queryForObject("select count(*) from customers",Long.class);var session=new MockHttpSession();
        var result=login(session).andExpect(status().isConflict()).andReturn();assertThat(result.getResponse().getContentAsString()).doesNotContain("fixture-open","fixture-discarded","fixture-secret");
        mvc.perform(get("/api/me").session(session)).andExpect(status().isUnauthorized());
        assertThat(db.queryForObject("select count(*) from customers",Long.class)).isEqualTo(customers);
        assertThat(db.queryForObject("select count(*) from wechat_identities where customer_id is null",Long.class)).isEqualTo(1);
        pending();assertThat(db.queryForObject("select count(*) from wechat_identities",Long.class)).isEqualTo(1);
        assertThat(db.queryForObject("select count(*) from audit where action='WECHAT_IDENTITY_PENDING'",Long.class)).isEqualTo(1);
    }
    @Test void ownerBindingIsIdempotentAndOnlyGrantsTheAttachedCustomer() throws Exception {
        var id=pending();bind(id,"bob","bind-fixture-001").andExpect(status().isOk());bind(id,"bob","bind-fixture-001").andExpect(status().isOk());
        assertThat(db.queryForObject("select count(*) from audit where action='WECHAT_IDENTITY_BOUND'",Long.class)).isEqualTo(1);
        var session=new MockHttpSession();String oldId=session.getId();login(session).andExpect(status().isOk()).andExpect(jsonPath("$.username").value("bob")).andExpect(jsonPath("$.role").value("CUSTOMER"));assertThat(session.getId()).isNotEqualTo(oldId);
        var overview=mvc.perform(get("/api/me").session(session)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();assertThat(overview).contains("bob").doesNotContain("alice");
        mvc.perform(get("/api/owner/dashboard").session(session)).andExpect(status().isForbidden());mvc.perform(get("/api/owner/customers/alice").session(session)).andExpect(status().isForbidden());
        assertThat(db.queryForObject("select balance_cents from customers where id='bob'",Long.class)).isEqualTo(20000);
    }
    @Test void bindingCannotBeOverwrittenAndKeyReuseCannotChangeTheTarget() throws Exception {
        var id=pending();bind(id,"alice","bind-fixture-002").andExpect(status().isOk());bind(id,"bob","bind-fixture-002").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));bind(id,"bob","bind-fixture-003").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDENTITY_ALREADY_BOUND"));
        assertThat(db.queryForObject("select customer_id from wechat_identities where id=?",String.class,id)).isEqualTo("alice");
    }
    @Test void customerCannotBindAndMissingTargetRollsBack() throws Exception {
        var id=pending();mvc.perform(post("/api/owner/wechat-bindings/"+id).with(user("alice").roles("CUSTOMER")).with(csrf()).header("Idempotency-Key","bind-fixture-004").contentType("application/json").content("{\"customerId\":\"alice\",\"reason\":\"fixture\"}")).andExpect(status().isForbidden());
        bind(id,"missing-customer","bind-fixture-005").andExpect(status().isNotFound());assertThat(db.queryForObject("select count(*) from commands where scope='WECHAT_BIND'",Long.class)).isZero();assertThat(db.queryForObject("select customer_id from wechat_identities where id=?",String.class,id)).isNull();
    }
    @Test void providerRejectedCodeDoesNotPersistAnIdentityOrAuthenticate() throws Exception {
        when(transport.request(eq("GET"),any(),isNull())).thenReturn(new WechatTransport.Reply(200,"{\"errcode\":40029,\"errmsg\":\"fixture debug\"}"));
        var session=new MockHttpSession();login(session).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("WECHAT_CODE_REJECTED"));assertThat(db.queryForObject("select count(*) from wechat_identities",Long.class)).isZero();mvc.perform(get("/api/me").session(session)).andExpect(status().isUnauthorized());
    }
    @Test void configuredCapabilityStillDoesNotClaimProductionOrPaymentReadiness() throws Exception {
        mvc.perform(get("/api/integrations/status").with(user("owner").roles("OWNER"))).andExpect(status().isOk()).andExpect(jsonPath("$.production_ready").value(false)).andExpect(jsonPath("$.services.wechat_identity.state").value("CONFIGURED_UNVERIFIED")).andExpect(jsonPath("$.services.wechat_payment.enabled").value(false));verifyNoInteractions(transport);
    }
    @Test void anotherVerifiedIdentityCannotSilentlyReplaceACustomersBinding() throws Exception {
        var first=pending();bind(first,"alice","bind:fixture:006").andExpect(status().isOk());
        when(transport.request(eq("GET"),any(),isNull())).thenReturn(new WechatTransport.Reply(200,"{\"openid\":\"different-fixture-open\",\"session_key\":\"fixture-discarded\"}"));
        var second=pending();bind(second,"alice","bind:fixture:007").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CUSTOMER_ALREADY_BOUND"));
        assertThat(db.queryForObject("select customer_id from wechat_identities where id=?",String.class,second)).isNull();
        assertThat(db.queryForObject("select count(*) from commands where scope='WECHAT_BIND'",Long.class)).isEqualTo(1);
    }
}
