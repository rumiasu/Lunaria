package cn.lunaria;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:lunaria_external_boundary;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","lunaria.integrations.wechat.enabled=true"})
@ActiveProfiles("test") @AutoConfigureMockMvc
class ExternalIntegrationBoundaryTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate db;
    private long count(String table){return db.queryForObject("select count(*) from "+table,Long.class);}
    @Test @WithMockUser(username="alice",roles="CUSTOMER") void capabilitiesStayDisabledEvenIfAnUnimplementedEnableFlagIsSet() throws Exception {
        mvc.perform(get("/api/integrations/status")).andExpect(status().isOk())
            .andExpect(jsonPath("$.production_ready").value(false))
            .andExpect(jsonPath("$.services.wechat_identity.enabled").value(false))
            .andExpect(jsonPath("$.services.wechat_payment.state").value("DISABLED_NOT_CONFIGURED"))
            .andExpect(jsonPath("$.services.wechat_notification.enabled").value(false));
    }
    @Test void realWechatLoginDoesNotFallBackToADemoCustomer() throws Exception {
        long customers=count("customers"),commands=count("commands");
        mvc.perform(post("/api/auth/wechat").with(csrf()).contentType("application/json").content("{\"code\":\"not-a-real-login-code\"}"))
            .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("INTEGRATION_NOT_CONFIGURED")).andExpect(jsonPath("$.feature").value("wechat_identity"));
        mvc.perform(get("/api/session")).andExpect(status().isOk()).andExpect(jsonPath("$.authenticated").value(false));
        assertThat(count("customers")).isEqualTo(customers);assertThat(count("commands")).isEqualTo(commands);
    }
    @Test void unconfiguredOwnerIdentityDoesNotUsePublicDemoCredentials() throws Exception {
        mvc.perform(post("/api/auth/wechat/owner").with(csrf()).contentType("application/json").content("{\"code\":\"fixture-code\"}"))
            .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("INTEGRATION_NOT_CONFIGURED")).andExpect(jsonPath("$.feature").value("wechat_owner_identity"));
        mvc.perform(get("/api/session")).andExpect(status().isOk()).andExpect(jsonPath("$.authenticated").value(false));
    }
    @Test @WithMockUser(username="alice",roles="CUSTOMER") void prepayCannotCreateACardOrChangeBalanceAndRetriesStayDisabled() throws Exception {
        long ledger=count("ledger"),cards=count("cards"),commands=count("commands");
        Long balance=db.queryForObject("select balance_cents from customers where id='alice'",Long.class);
        for(int i=0;i<2;i++)mvc.perform(post("/api/payments/wechat/prepay").with(csrf()))
            .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.feature").value("wechat_payment"));
        assertThat(count("ledger")).isEqualTo(ledger);assertThat(count("cards")).isEqualTo(cards);assertThat(count("commands")).isEqualTo(commands);
        assertThat(db.queryForObject("select balance_cents from customers where id='alice'",Long.class)).isEqualTo(balance);
    }
    @Test @WithMockUser(username="owner",roles="OWNER") void notificationAttemptIsExplicitlyUnavailableWithoutBusinessWrites() throws Exception {
        long audit=count("audit"),appointments=count("appointments");
        mvc.perform(post("/api/owner/integrations/wechat/notifications").with(csrf()))
            .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.feature").value("wechat_notification"));
        assertThat(count("audit")).isEqualTo(audit);assertThat(count("appointments")).isEqualTo(appointments);
    }
    @Test void roleRestrictionsRemainInForceBeforeExternalBoundaries() throws Exception {
        mvc.perform(get("/api/integrations/status")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/owner/integrations/wechat/notifications").with(user("alice").roles("CUSTOMER")).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(post("/api/payments/wechat/prepay").with(user("owner").roles("OWNER")).with(csrf())).andExpect(status().isForbidden());
    }
    @Test void csrfIsNotDisabledForLoginOrPayment() throws Exception {
        mvc.perform(post("/api/auth/wechat").contentType("application/json").content("{\"code\":\"placeholder\"}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/payments/wechat/prepay").with(user("alice").roles("CUSTOMER"))).andExpect(status().isForbidden());
    }
    @Test @WithMockUser(username="alice",roles="CUSTOMER") void aDisabledPaymentCallbackNeverAcknowledgesSuccess() throws Exception {
        long entries=count("ledger");mvc.perform(post("/api/payments/wechat/notify").with(csrf()).contentType("application/json").content("{}"))
            .andExpect(status().isServiceUnavailable());assertThat(count("ledger")).isEqualTo(entries);
    }
    @Test void invalidLoginInputIsRejectedWithoutEchoingOrPersistingIt() throws Exception {
        long customers=count("customers");mvc.perform(post("/api/auth/wechat").with(csrf()).contentType("application/json").content("{\"code\":\"\"}"))
            .andExpect(status().isBadRequest());assertThat(count("customers")).isEqualTo(customers);
    }
}
