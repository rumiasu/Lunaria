package cn.lunaria;

import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import static cn.lunaria.ShopService.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:lunaria_balance_refund;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test") @AutoConfigureMockMvc
class BalanceRefundProcessingTest {
    @Autowired ShopService service;@Autowired JdbcTemplate sql;@Autowired DemoSeed seed;@Autowired MockMvc mvc;
    @MockitoSpyBean StoreMapper mapper;
    @BeforeEach void isolate(){
        reset(mapper);sql.update("delete from balance_refund_records where reversal_of is not null");
        for(String table:List.of("balance_refund_records","customer_contacts","commands","audit","wechat_identities","refund_negotiations","refunds","ledger","appointments","cards","tier_prices","packages","services","customers","shop"))sql.update("delete from "+table);
        seed.run(null);
    }
    private String key(){return UUID.randomUUID().toString();}
    private String reserve(){String id=s(service.balanceRefund("bob",5000,"synthetic request",key()),"id");service.reviewRefund("owner",id,true,5000,"synthetic reserve; no payment",key());return id;}
    private Map<String,Object> values(String action,Long amount,String reference,String note,String reversal,Boolean disputed){return row("action",action,"amount",amount,"reference",reference,"note",note,"reversalOf",reversal,"disputed",disputed);}
    private Map<String,Object> process(String refund,String action,String reversal,String operation){return service.processBalanceRefund("owner",refund,values(action,5000L,"fixture-reference","synthetic owner record",reversal,null),operation);}
    @SuppressWarnings("unchecked") private Map<String,Object> record(Map<String,Object> result){return (Map<String,Object>)result.get("record");}
    @SuppressWarnings("unchecked") private Map<String,Object> summary(){return (Map<String,Object>)service.overview("bob").get("summary");}
    @Test void cardRefundReturnsOnlyToOriginalBalanceAndRetainsPurchaseSource(){
        String card=s(service.purchase("alice","facial5",key()),"id");var purchase=mapper.cardPurchases(card).get(0);
        String refund=s(service.requestRefund("alice",card,"CONSUMER","PROPORTIONAL","",key()),"id"),operation=key();
        var view=service.reviewRefund("owner",refund,true,9500,"synthetic ordinary refund",operation);
        service.reviewRefund("owner",refund,true,9500,"synthetic ordinary refund",operation);
        assertThat(n(mapper.customer("alice"),"balance_cents")).isEqualTo(30000);assertThat(n(mapper.customer("alice"),"fulfilled_cents")).isZero();
        assertThat(view.get("refund_destination")).isEqualTo("CUSTOMER_BALANCE");assertThat(view.get("original_purchase_entries")).asList().hasSize(1);
        assertThat(n(purchase,"amount_cents")).isEqualTo(-9500);assertThat(purchase.get("source_id")).isEqualTo(card);
        var returns=mapper.ledger("alice").stream().filter(e->"REFUND_TO_BALANCE".equals(e.get("kind"))).toList();
        assertThat(returns).hasSize(1);assertThat(returns.get(0).get("source_id")).isEqualTo(refund);assertThat(returns.get(0).get("card_id")).isEqualTo(card);
        @SuppressWarnings("unchecked") var totals=(Map<String,Object>)service.overview("alice").get("summary");
        assertThat(n(totals,"recharged_principal_cents")).isEqualTo(30000);assertThat(n(totals,"purchased_card_cents")).isEqualTo(9500);assertThat(n(totals,"fulfilled_reference_cents")).isZero();
    }
    @Test void manualCompletionOnlyRecordsTheReservedPrincipalWithoutAnotherDebit(){
        String refund=reserve(),operation=key();var result=process(refund,"RECORD_MANUAL",null,operation);
        assertThat(mapper.refund(refund).get("status")).isEqualTo("MANUAL_RECORDED");assertThat(n(mapper.customer("bob"),"balance_cents")).isEqualTo(15000);
        assertThat(process(refund,"RECORD_MANUAL",null,operation).get("record")).isEqualTo(result.get("record"));
        assertThat(mapper.balanceRefundRecords(refund)).hasSize(1);assertThat(result.get("external_transfer_executed")).isEqualTo(false);
        var entry=mapper.entry(s(record(result),"ledger_id"));assertThat(entry.get("source_id")).isEqualTo(mapper.balanceReservations(refund).get(0).get("id"));
        assertThat(n(summary(),"reserved_balance_refund_cents")).isZero();assertThat(n(summary(),"manually_recorded_balance_refund_cents")).isEqualTo(5000);
        assertThat(n(summary(),"recharged_principal_cents")).isEqualTo(20000);assertThat(n(summary(),"fulfilled_reference_cents")).isZero();
        assertThatThrownBy(()->process(refund,"RECORD_MANUAL",null,key())).isInstanceOf(BusinessException.class);
    }
    @Test void releasingAnUnpaidReservationRestoresBalanceWithoutNewRecharge(){
        String refund=reserve(),operation=key();process(refund,"RELEASE",null,operation);process(refund,"RELEASE",null,operation);
        assertThat(mapper.refund(refund).get("status")).isEqualTo("RELEASED");assertThat(n(mapper.customer("bob"),"balance_cents")).isEqualTo(20000);
        assertThat(n(summary(),"reserved_balance_refund_cents")).isZero();assertThat(n(summary(),"released_balance_refund_cents")).isEqualTo(5000);
        assertThat(n(summary(),"recharged_principal_cents")).isEqualTo(20000);assertThat(n(mapper.customer("bob"),"fulfilled_cents")).isZero();
        assertThat(mapper.balanceRefundRecords(refund)).hasSize(1);assertThatThrownBy(()->process(refund,"RELEASE",null,key())).isInstanceOf(BusinessException.class);
    }
    @Test void reversingAManualRecordRetainsItAndDoesNotPretendFundsReturned(){
        String refund=reserve();String original=s(record(process(refund,"RECORD_MANUAL",null,key())),"id"),operation=key();
        process(refund,"REVERSE_MANUAL",original,operation);process(refund,"REVERSE_MANUAL",original,operation);
        assertThat(mapper.refund(refund).get("status")).isEqualTo("RESERVED_NO_PAYOUT");assertThat(n(mapper.customer("bob"),"balance_cents")).isEqualTo(15000);
        assertThat(n(summary(),"manually_recorded_balance_refund_cents")).isZero();assertThat(n(summary(),"reserved_balance_refund_cents")).isEqualTo(5000);
        assertThat(mapper.balanceRefundRecord(original).get("action")).isEqualTo("RECORD_MANUAL");assertThat(mapper.balanceRefundRecordReversed(original)).isEqualTo(1);
        assertThatThrownBy(()->process(refund,"REVERSE_MANUAL",original,key())).isInstanceOf(BusinessException.class);
        process(refund,"RELEASE",null,key());assertThat(n(mapper.customer("bob"),"balance_cents")).isEqualTo(20000);assertThat(mapper.balanceRefundRecords(refund)).hasSize(3);
    }
    @Test void disputedNotesCanBeSavedWithoutSettlingOrReleasingFunds(){
        String refund=reserve();service.processBalanceRefund("owner",refund,values("NOTE",null,null,"synthetic dispute",null,true),key());
        assertThat(mapper.refund(refund).get("status")).isEqualTo("RESERVED_DISPUTED");
        service.processBalanceRefund("owner",refund,values("NOTE",null,null,"still checking",null,null),key());
        assertThatThrownBy(()->process(refund,"RECORD_MANUAL",null,key())).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->process(refund,"RELEASE",null,key())).isInstanceOf(BusinessException.class);
        assertThat(n(mapper.customer("bob"),"balance_cents")).isEqualTo(15000);assertThat(mapper.balanceRefundRecords(refund)).hasSize(2);
        service.processBalanceRefund("owner",refund,values("NOTE",null,null,"synthetic issue resolved",null,false),key());
        String original=s(record(process(refund,"RECORD_MANUAL",null,key())),"id");
        service.processBalanceRefund("owner",refund,values("NOTE",null,null,"record disputed after entry",null,true),key());
        assertThat(mapper.refund(refund).get("status")).isEqualTo("MANUAL_RECORD_DISPUTED");assertThat(n(summary(),"manually_recorded_balance_refund_cents")).isZero();
        process(refund,"REVERSE_MANUAL",original,key());assertThat(mapper.refund(refund).get("status")).isEqualTo("RESERVED_DISPUTED");
    }
    @Test void aPendingDisputeBlocksApprovalWhileItsNoteRemainsSaved(){
        String refund=s(service.balanceRefund("bob",5000,"synthetic request",key()),"id");
        service.processBalanceRefund("owner",refund,values("NOTE",null,null,"pending cause dispute",null,true),key());
        assertThatThrownBy(()->service.reviewRefund("owner",refund,true,5000,"synthetic approval",key())).hasMessageContaining("争议");
        assertThat(n(mapper.customer("bob"),"balance_cents")).isEqualTo(20000);assertThat(mapper.balanceReservations(refund)).isEmpty();
        assertThat(service.refundDetail("bob",refund).get("disputed")).isEqualTo(true);assertThat(mapper.balanceRefundRecords(refund)).hasSize(1);
    }
    @Test void processingWriteFailureRollsBackReleaseLedgerBalanceAndCommand(){
        String refund=reserve(),operation=key();int auditCount=mapper.audit().size(),ledgerCount=mapper.ledger("bob").size();
        doThrow(new IllegalStateException("synthetic processing record failure")).when(mapper).insertBalanceRefundRecord(anyMap());
        assertThatThrownBy(()->process(refund,"RELEASE",null,operation)).isInstanceOf(IllegalStateException.class);
        assertThat(n(mapper.customer("bob"),"balance_cents")).isEqualTo(15000);assertThat(mapper.refund(refund).get("status")).isEqualTo("RESERVED_NO_PAYOUT");
        assertThat(mapper.ledger("bob")).hasSize(ledgerCount);assertThat(mapper.audit()).hasSize(auditCount);assertThat(mapper.command("owner",operation)).isNull();
        reset(mapper);process(refund,"RELEASE",null,operation);assertThat(n(mapper.customer("bob"),"balance_cents")).isEqualTo(20000);
    }
    @Test void processingApiProtectsRolesAmountsCsrfAndAllowsNoteOnly() throws Exception {
        String refund=reserve(),path="/api/owner/balance-refunds/"+refund+"/records";
        mvc.perform(post(path).with(user("bob").roles("CUSTOMER")).with(csrf()).header("Idempotency-Key",key()).contentType("application/json").content("{\"note\":\"forbidden\"}")).andExpect(status().isForbidden());
        mvc.perform(post(path).with(user("owner").roles("OWNER")).header("Idempotency-Key",key()).contentType("application/json").content("{\"note\":\"missing csrf\"}")).andExpect(status().isForbidden());
        mvc.perform(post(path).with(user("owner").roles("OWNER")).with(csrf()).header("Idempotency-Key",key()).contentType("application/json").content("{\"action\":\"RELEASE\",\"amountCents\":100000001,\"note\":\"too much\"}")).andExpect(status().isBadRequest());
        mvc.perform(post(path).with(user("owner").roles("OWNER")).with(csrf()).header("Idempotency-Key",key()).contentType("application/json").content("{\"action\":\"RELEASE\",\"amountCents\":4999,\"note\":\"partial amount\"}")).andExpect(status().isConflict());
        mvc.perform(post(path).with(user("owner").roles("OWNER")).with(csrf()).header("Idempotency-Key",key()).contentType("application/json").content("{\"note\":\"contacted owner; still disputed\",\"disputed\":true}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.refund.status").value("RESERVED_DISPUTED")).andExpect(jsonPath("$.external_transfer_executed").value(false));
        mvc.perform(get("/api/me/refunds/"+refund).with(user("alice").roles("CUSTOMER"))).andExpect(status().isNotFound());
    }
    @Test void aManualRecordCannotBeReversedThroughAnotherRefund(){
        String first=reserve(),second=reserve();String original=s(record(process(first,"RECORD_MANUAL",null,key())),"id");process(second,"RECORD_MANUAL",null,key());
        assertThatThrownBy(()->process(second,"REVERSE_MANUAL",original,key())).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->process(first,"RELEASE",null,key())).isInstanceOf(BusinessException.class);
        assertThat(n(mapper.customer("bob"),"balance_cents")).isEqualTo(10000);assertThat(mapper.balanceRefundRecordReversed(original)).isZero();
    }
    @Test void fractionalCentsCannotBeTruncatedIntoValidPrincipalOperations() throws Exception {
        String refund=reserve();
        mvc.perform(post("/api/owner/balance-refunds/"+refund+"/records").with(user("owner").roles("OWNER")).with(csrf()).header("Idempotency-Key",key()).contentType("application/json")
            .content("{\"action\":\"RELEASE\",\"amountCents\":5000.1,\"note\":\"fraction must not be truncated\"}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/me/balance-refunds").with(user("bob").roles("CUSTOMER")).with(csrf()).header("Idempotency-Key",key()).contentType("application/json")
            .content("{\"amountCents\":5000.1,\"evidence\":\"fraction must not be truncated\"}")).andExpect(status().isBadRequest());
        assertThat(n(mapper.customer("bob"),"balance_cents")).isEqualTo(15000);assertThat(mapper.balanceRefundRecords(refund)).isEmpty();
    }
    @Test void competingManualCompletionAndReleaseCanOnlyApplyOneTransition() throws Exception {
        String refund=reserve();var gate=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try {
            var tasks=new ArrayList<Future<Boolean>>();for(String action:List.of("RECORD_MANUAL","RELEASE"))tasks.add(pool.submit(()->{gate.await();try{process(refund,action,null,key());return true;}catch(BusinessException error){return false;}}));
            gate.countDown();int success=0;for(var task:tasks)if(task.get(10,TimeUnit.SECONDS))success++;assertThat(success).isEqualTo(1);
        } finally {pool.shutdownNow();}
        assertThat(mapper.balanceRefundRecords(refund)).hasSize(1);String state=s(mapper.refund(refund),"status");
        assertThat(n(mapper.customer("bob"),"balance_cents")).isEqualTo("RELEASED".equals(state)?20000:15000);
    }
}
