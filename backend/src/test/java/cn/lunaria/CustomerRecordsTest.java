package cn.lunaria;

import cn.lunaria.integration.WechatTransport;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.test.web.servlet.*;
import static cn.lunaria.ShopService.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:lunaria_contacts;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "lunaria.wechat.enabled=true","lunaria.wechat.app-id=fixture-app","lunaria.wechat.app-secret=fixture-secret"})
@ActiveProfiles("test") @AutoConfigureMockMvc
class CustomerRecordsTest {
    @Autowired ShopService service;@Autowired JdbcTemplate sql;@Autowired DemoSeed seed;@Autowired MockMvc mvc;
    @MockitoSpyBean StoreMapper mapper;@MockitoBean WechatTransport transport;
    private final ObjectMapper json=new ObjectMapper();
    @BeforeEach void isolate(){
        reset(mapper,transport);
        for(String table:List.of("customer_contacts","commands","audit","wechat_identities","refund_negotiations","refunds","ledger","appointments","cards","tier_prices","packages","services","customers","shop"))sql.update("delete from "+table);
        seed.run(null);
    }
    private String key(){return UUID.randomUUID().toString();}
    private ResultActions save(String id,String body,String key) throws Exception {
        return mvc.perform(post("/api/owner/customers"+(id==null?"":"/"+id)).with(user("owner").roles("OWNER")).with(csrf())
            .header("Idempotency-Key",key).contentType("application/json").content(body));
    }
    @Test void ownerRegistrationStartsEmptyAndRetryDoesNotCreateAnotherCustomer() throws Exception {
        String operation=key(),body="{\"name\":\"Synthetic visitor\",\"contact\":\"fixture-contact\",\"note\":\"manual contact only\"}";
        var result=save(null,body,operation).andExpect(status().isOk()).andExpect(jsonPath("$.customer.balance_cents").value(0))
            .andExpect(jsonPath("$.customer.fulfilled_cents").value(0)).andExpect(jsonPath("$.customer.tier").value("NORMAL"))
            .andExpect(jsonPath("$.customer.qualification").value("UNDECIDED")).andExpect(jsonPath("$.cards").isEmpty())
            .andExpect(jsonPath("$.ledger").isEmpty()).andExpect(jsonPath("$.contact.contact").value("fixture-contact")).andReturn();
        String id=json.readTree(result.getResponse().getContentAsString()).path("customer").path("id").asText();
        save(null,body,operation).andExpect(status().isOk()).andExpect(jsonPath("$.customer.id").value(id));
        save(null,"{\"name\":\"Another visitor\"}",operation).andExpect(status().isConflict());
        assertThat(mapper.customers()).hasSize(3);assertThat(sql.queryForObject("select count(*) from audit where action='CREATE_CUSTOMER'",Integer.class)).isEqualTo(1);
        assertThat(n(mapper.customer("alice"),"balance_cents")).isEqualTo(30000);verifyNoInteractions(transport);
    }
    @Test void editingContactsPreservesAssetsSnapshotsTierAndBinding() throws Exception {
        String card=s(service.purchase("alice","facial5",key()),"id");var before=new LinkedHashMap<>(mapper.card(card));
        sql.update("insert into wechat_identities values('contact-binding','fixture-app','fixture-open','alice',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        String operation=key(),body="{\"name\":\"Synthetic updated name\",\"contact\":\"manual-reference\",\"note\":\"owner-only memo\"}";
        save("alice",body,operation).andExpect(status().isOk()).andExpect(jsonPath("$.contact.note").value("owner-only memo"));
        save("alice",body,operation).andExpect(status().isOk());
        assertThat(mapper.card(card)).isEqualTo(before);assertThat(n(mapper.customer("alice"),"balance_cents")).isEqualTo(20500);
        assertThat(mapper.customer("alice").get("tier")).isEqualTo("VIP");assertThat(mapper.customer("alice").get("qualification")).isEqualTo("FAVORABLE");
        assertThat(sql.queryForObject("select customer_id from wechat_identities where id='contact-binding'",String.class)).isEqualTo("alice");
        assertThat(mapper.ledger("alice")).hasSize(2);assertThat(sql.queryForObject("select count(*) from audit where action='UPDATE_CUSTOMER'",Integer.class)).isEqualTo(1);
        mvc.perform(get("/api/me").with(user("alice").roles("CUSTOMER"))).andExpect(status().isOk()).andExpect(jsonPath("$.contact").doesNotExist())
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("owner-only memo"))));
        save("alice","{\"name\":\"Synthetic updated name\"}",key()).andExpect(status().isOk()).andExpect(jsonPath("$.contact.contact").value(""));
    }
    @Test void contactRecordsRequireOwnerRoleAndCsrf() throws Exception {
        mvc.perform(get("/api/owner/customers")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/owner/customers").with(user("alice").roles("CUSTOMER"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/owner/customers/bob").with(user("alice").roles("CUSTOMER"))).andExpect(status().isForbidden());
        mvc.perform(post("/api/owner/customers").with(user("alice").roles("CUSTOMER")).with(csrf()).header("Idempotency-Key",key())
            .contentType("application/json").content("{\"name\":\"forbidden\"}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/owner/customers/alice").with(user("owner").roles("OWNER")).header("Idempotency-Key",key())
            .contentType("application/json").content("{\"name\":\"missing csrf\"}")).andExpect(status().isForbidden());
        assertThat(mapper.contact("alice")).isNull();assertThat(mapper.customers()).hasSize(2);
    }
    private String finish(String card,int day){
        String appointment=s(service.book("alice","facial",LocalDate.now(SHOP_ZONE).plusDays(day+2).atTime(9,0),"",key()),"id");
        service.confirm("owner",appointment,key());sql.update("update appointments set starts_at=?,ends_at=? where id=?",now().minusHours(2),now().minusHours(1),appointment);
        return s(service.complete("owner",appointment,card,key()),"id");
    }
    @Test void manualContactListSearchAndHistoryReflectReversedVisits() throws Exception {
        service.saveCustomer("owner","alice","Synthetic Alice","fixture-CONTACT","internal note",key());
        String card=s(service.purchase("alice","facial5",key()),"id"),entry="";for(int i=0;i<4;i++)entry=finish(card,i);
        var list=service.ownerCustomers("fixture-contact",true);assertThat(list).hasSize(1);
        assertThat(n(list.get(0),"visits")).isEqualTo(4);assertThat(n(list.get(0),"remaining")).isEqualTo(1);
        assertThat(list.get(0).get("usual")).isEqualTo("舒缓面部护理");
        service.reverse("owner",entry,"synthetic correction",key());assertThat(service.ownerCustomers("fixture-contact",true)).isEmpty();
        mvc.perform(get("/api/owner/customers").param("query","SYNTHETIC ALICE").with(user("owner").roles("OWNER")))
            .andExpect(status().isOk()).andExpect(jsonPath("$[0].visits").value(3)).andExpect(jsonPath("$[0].remaining").value(2));
        verifyNoInteractions(transport);
    }
    @Test void contactWriteFailureRollsBackNewCustomerAndCommand() {
        doThrow(new IllegalStateException("synthetic contact failure")).when(mapper).insertContact(anyString(),anyString(),anyString());
        assertThatThrownBy(()->service.saveCustomer("owner",null,"Synthetic visitor","fixture","",key())).isInstanceOf(IllegalStateException.class);
        assertThat(mapper.customers()).hasSize(2);assertThat(sql.queryForObject("select count(*) from customer_contacts",Integer.class)).isZero();
        assertThat(sql.queryForObject("select count(*) from commands",Integer.class)).isZero();assertThat(mapper.audit()).isEmpty();
    }
    @Test void ownerRegisteredCustomerCanBindVerifiedWechatAndReadOnlyOwnEmptyAccount() throws Exception {
        String customer=s((Map<String,Object>)service.saveCustomer("owner",null,"Synthetic visitor","fixture","owner memo",key()).get("customer"),"id");
        when(transport.request(eq("GET"),any(),isNull())).thenReturn(new WechatTransport.Reply(200,"{\"openid\":\"fixture-new-visitor\",\"session_key\":\"discarded\"}"));
        var session=new MockHttpSession();
        var pending=mvc.perform(post("/api/auth/wechat").session(session).with(csrf()).contentType("application/json").content("{\"code\":\"fixture-code\"}"))
            .andExpect(status().isConflict()).andReturn();
        String binding=json.readTree(pending.getResponse().getContentAsString()).path("binding_id").asText();
        mvc.perform(post("/api/owner/wechat-bindings/"+binding).with(user("owner").roles("OWNER")).with(csrf()).header("Idempotency-Key",key())
            .contentType("application/json").content(json.writeValueAsString(Map.of("customerId",customer,"reason","synthetic verified in person")))).andExpect(status().isOk());
        mvc.perform(post("/api/auth/wechat").session(session).with(csrf()).contentType("application/json").content("{\"code\":\"fixture-code-2\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.username").value(customer)).andExpect(jsonPath("$.role").value("CUSTOMER"));
        mvc.perform(get("/api/me").session(session)).andExpect(status().isOk()).andExpect(jsonPath("$.customer.id").value(customer))
            .andExpect(jsonPath("$.customer.balance_cents").value(0)).andExpect(jsonPath("$.cards").isEmpty()).andExpect(jsonPath("$.contact").doesNotExist());
        mvc.perform(get("/api/owner/customers/alice").session(session)).andExpect(status().isForbidden());
    }
    @Test void missingCustomerOrInvalidProfileCannotCreatePartialRecords() throws Exception {
        save("missing-customer","{\"name\":\"Synthetic visitor\"}",key()).andExpect(status().isNotFound());
        save(null,"{\"name\":\"   \"}",key()).andExpect(status().isBadRequest());
        save(null,json.writeValueAsString(Map.of("name","Synthetic visitor","contact","x".repeat(121))),key()).andExpect(status().isBadRequest());
        assertThat(mapper.customers()).hasSize(2);assertThat(sql.queryForObject("select count(*) from customer_contacts",Integer.class)).isZero();
        assertThat(sql.queryForObject("select count(*) from commands",Integer.class)).isZero();
    }
}
