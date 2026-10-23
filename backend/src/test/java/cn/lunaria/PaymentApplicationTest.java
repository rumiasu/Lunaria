package cn.lunaria;

import cn.lunaria.integration.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.test.web.servlet.MockMvc;
import static cn.lunaria.ShopService.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Application accounting against an explicitly substituted channel, never real payments. */
@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:lunaria_payment_app;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test") @AutoConfigureMockMvc
class PaymentApplicationTest {
    @Autowired PaymentService payments;@Autowired ShopService shop;@Autowired JdbcTemplate sql;@Autowired DemoSeed seed;@Autowired MockMvc mvc;
    @MockitoBean PaymentGateway channel;@MockitoSpyBean StoreMapper store;@MockitoSpyBean PaymentMapper pay;
    private final AtomicReference<PaymentGateway.Payment> paymentNotice=new AtomicReference<>();
    private final AtomicReference<PaymentGateway.Refund> refundNotice=new AtomicReference<>();
    @BeforeEach void isolate(){
        reset(channel,store,pay);sql.update("delete from balance_refund_records where reversal_of is not null");
        for(String table:List.of("payment_reconciliation","payment_attempts","channel_refunds","payment_receipts","payment_orders","balance_refund_records","customer_contacts","commands","audit","wechat_identities","refund_negotiations","refunds","ledger","appointments","cards","tier_prices","packages","services","customers","shop"))sql.update("delete from "+table);
        seed.run(null);sql.update("insert into wechat_identities values('payment-binding','fixture-app','fixture-open','alice',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        when(channel.available()).thenReturn(true);when(channel.mode()).thenReturn("ISOLATED_FAKE_CHANNEL");when(channel.context()).thenReturn(new PaymentGateway.Context("fixture-app","fixture-merchant"));
        when(channel.prepare(any())).thenReturn("fixture-prepay");
        when(channel.verifyPayment(any())).thenAnswer(call->{checkSignature(call.getArgument(0));return paymentNotice.get();});
        when(channel.verifyRefund(any())).thenAnswer(call->{checkSignature(call.getArgument(0));return refundNotice.get();});
    }
    private void checkSignature(WechatPaymentAdapter.Callback c){if(!"fixture-valid".equals(c.signature()))throw new ProviderFailure(400,"CALLBACK_REJECTED","isolated verifier rejected signature","wechat_payment");}
    private String key(){return UUID.randomUUID().toString();}
    private WechatPaymentAdapter.Callback callback(){return new WechatPaymentAdapter.Callback("fixture-serial","1","fixture-nonce","fixture-valid","fixture encrypted callback");}
    private PaymentGateway.Payment payment(String id,String transaction){return new PaymentGateway.Payment(id,"fixture-app","fixture-merchant","fixture-open",n(pay.order(id),"amount_cents"),"CNY",transaction,"SUCCESS");}
    private String create(){return s(payments.createOrder("alice",10000,key()),"id");}
    private String paid(){String id=create();paymentNotice.set(payment(id,"tx-"+id));payments.paymentCallback(callback());return id;}
    private String ready(String order){String id=s(payments.requestRefund("alice",order,5000,"synthetic refund",key()),"id");payments.reviewRefund("owner",id,true,"synthetic reserve",key());return id;}
    private PaymentGateway.Refund refundResult(String id,String state){var r=pay.refund(id);var o=pay.order(s(r,"order_id"));return new PaymentGateway.Refund(id,s(o,"id"),"fixture-merchant",s(pay.receipt(s(o,"id")),"provider_transaction_id"),"refund-"+id,n(o,"amount_cents"),n(r,"amount_cents"),"CNY",state);}
    @SuppressWarnings("unchecked") private String attemptState(Map<String,Object> result){return s((Map<String,Object>)result.get("attempt"),"status");}
    @Test void serverOrderIsImmutableIdempotentAndRequiresAnActualBoundCustomer(){
        String operation=key();var order=payments.createOrder("alice",10000,operation);String id=s(order,"id");
        assertThat(payments.createOrder("alice",10000,operation).get("id")).isEqualTo(id);assertThat(order).doesNotContainKey("payer_open_id");
        assertThatThrownBy(()->payments.createOrder("alice",9999,operation)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->payments.createOrder("bob",10000,key())).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->payments.order("bob",id)).isInstanceOf(BusinessException.class);
        assertThat(n(store.customer("alice"),"balance_cents")).isEqualTo(30000);assertThat(pay.receipt(id)).isNull();
    }
    @Test void missingShopLockRowCannotSilentlyRunUnserializedFinancialOperations(){
        sql.update("delete from shop");assertThatThrownBy(()->payments.createOrder("alice",10000,key())).hasMessageContaining("尚未初始化");
        assertThat(pay.orders("alice")).isEmpty();verify(channel,never()).prepare(any());assertThat(n(store.customer("alice"),"balance_cents")).isEqualTo(30000);
    }
    @Test void missingPrepayOrderIsBadInputAndNeverDispatchesToChannel() throws Exception {
        mvc.perform(post("/api/payments/wechat/prepay").with(user("alice").roles("CUSTOMER")).with(csrf()).header("Idempotency-Key",key()).contentType("application/json").content("{}"))
            .andExpect(status().isBadRequest());verify(channel,never()).prepare(any());assertThat(sql.queryForObject("select count(*) from payment_attempts",Integer.class)).isZero();
    }
    @Test void prepayIsNotPaymentAndRetriesNeverResendAnAcceptedIntent(){
        String id=create(),operation=key();payments.prepare("alice",id,operation);payments.prepare("alice",id,operation);payments.prepare("alice",id,key());
        verify(channel,times(1)).prepare(any());assertThat(pay.order(id).get("status")).isEqualTo("PREPAY_READY");
        assertThat(n(store.customer("alice"),"balance_cents")).isEqualTo(30000);assertThat(store.cards("alice")).isEmpty();assertThat(pay.receipt(id)).isNull();
    }
    @Test void unknownPrepayRequiresQueryInsteadOfBlindResubmission(){
        String id=create(),operation=key();when(channel.prepare(any())).thenThrow(new PaymentGateway.UnknownResult());
        assertThat(attemptState(payments.prepare("alice",id,operation))).isEqualTo("UNKNOWN");payments.prepare("alice",id,operation);
        assertThatThrownBy(()->payments.prepare("alice",id,key())).isInstanceOf(BusinessException.class);verify(channel,times(1)).prepare(any());
        when(channel.queryPayment(any())).thenReturn(new PaymentGateway.Payment(id,"fixture-app","fixture-merchant",null,10000,"CNY",null,"NOT_PAID"));
        payments.queryOrder("alice","alice",id,key());assertThat(pay.order(id).get("status")).isEqualTo("AWAITING_PAYMENT");
        doReturn("recovered-prepay").when(channel).prepare(any());payments.prepare("alice",id,key());verify(channel,times(2)).prepare(any());
        assertThat(n(store.customer("alice"),"balance_cents")).isEqualTo(30000);
    }
    @Test void verifiedCallbackPostsPrincipalOnceAndPublicSignatureFailureNeverAcknowledges() throws Exception {
        String id=create();paymentNotice.set(payment(id,"fixture-transaction"));
        for(int i=0;i<2;i++)mvc.perform(post("/api/payments/wechat/notify").header("Wechatpay-Signature","fixture-valid").contentType("application/json").content("{}"))
            .andExpect(status().isNoContent());
        mvc.perform(post("/api/payments/wechat/notify").header("Wechatpay-Signature","forged").contentType("application/json").content("{}"))
            .andExpect(status().isBadRequest());
        assertThat(n(store.customer("alice"),"balance_cents")).isEqualTo(40000);assertThat(store.ledger("alice")).hasSize(2);
        assertThat(store.entry(s(pay.receipt(id),"ledger_id")).get("source_id")).isEqualTo(id);assertThat(n(store.customer("alice"),"fulfilled_cents")).isZero();assertThat(store.cards("alice")).isEmpty();
    }
    @Test void allAuthoritativeContextFieldsAndUniqueProviderTransactionsAreChecked(){
        String id=create();var good=payment(id,"unique-provider-transaction");
        List<PaymentGateway.Payment> wrong=List.of(new PaymentGateway.Payment(id,"wrong","fixture-merchant","fixture-open",10000,"CNY","x","SUCCESS"),new PaymentGateway.Payment(id,"fixture-app","wrong","fixture-open",10000,"CNY","x","SUCCESS"),new PaymentGateway.Payment(id,"fixture-app","fixture-merchant","wrong",10000,"CNY","x","SUCCESS"),new PaymentGateway.Payment(id,"fixture-app","fixture-merchant","fixture-open",9999,"CNY","x","SUCCESS"),new PaymentGateway.Payment(id,"fixture-app","fixture-merchant","fixture-open",10000,"USD","x","SUCCESS"));
        for(var value:wrong){paymentNotice.set(value);assertThatThrownBy(()->payments.paymentCallback(callback())).isInstanceOf(ProviderFailure.class);}
        assertThat(n(store.customer("alice"),"balance_cents")).isEqualTo(30000);paymentNotice.set(good);payments.paymentCallback(callback());
        String second=create();paymentNotice.set(payment(second,"unique-provider-transaction"));assertThatThrownBy(()->payments.paymentCallback(callback())).isInstanceOf(ProviderFailure.class);
        assertThat(n(store.customer("alice"),"balance_cents")).isEqualTo(40000);assertThat(pay.receipt(second)).isNull();
    }
    @Test void aQueryCannotPostAnotherOrdersOtherwiseValidResult(){
        String first=create(),second=create();when(channel.queryPayment(any())).thenAnswer(call->payment(second,"query-wrong-target"));
        assertThat(attemptState(payments.queryOrder("alice","alice",first,key()))).isEqualTo("UNKNOWN");
        assertThat(pay.receipt(first)).isNull();assertThat(pay.receipt(second)).isNull();assertThat(n(store.customer("alice"),"balance_cents")).isEqualTo(30000);
    }
    @Test void paymentPostingFailureRollsBackReceiptLedgerAndBalanceBeforeAnyAck(){
        String id=create();paymentNotice.set(payment(id,"rollback-payment"));int audits=store.audit().size();
        doThrow(new IllegalStateException("synthetic receipt failure")).when(pay).insertReceipt(anyMap());
        assertThatThrownBy(()->payments.paymentCallback(callback())).isInstanceOf(IllegalStateException.class);
        assertThat(n(store.customer("alice"),"balance_cents")).isEqualTo(30000);assertThat(store.ledger("alice")).hasSize(1);assertThat(pay.receipt(id)).isNull();assertThat(store.audit()).hasSize(audits);
        reset(pay);payments.paymentCallback(callback());assertThat(n(store.customer("alice"),"balance_cents")).isEqualTo(40000);
    }
    @Test void callbackAndQueryContentionStillCreatesOneFinancialEntry() throws Exception {
        String id=create();var value=payment(id,"concurrent-payment");paymentNotice.set(value);when(channel.queryPayment(any())).thenReturn(value);
        var pool=Executors.newFixedThreadPool(4);var gate=new CountDownLatch(1);var tasks=new ArrayList<Future<?>>();
        try{for(int i=0;i<4;i++){int method=i;tasks.add(pool.submit(()->{gate.await();if(method==0)payments.queryOrder("alice","alice",id,key());else payments.paymentCallback(callback());return null;}));}gate.countDown();for(var task:tasks)task.get(10,TimeUnit.SECONDS);}finally{pool.shutdownNow();}
        assertThat(n(store.customer("alice"),"balance_cents")).isEqualTo(40000);assertThat(store.ledger("alice")).hasSize(2);assertThat(pay.receipt(id)).isNotNull();
    }
    @Test void refundLinksOriginalPaymentAndReusesExistingPrincipalReservation(){
        String order=paid(),id=ready(order);var request=pay.refund(id);String business=s(request,"business_refund_id");
        assertThat(n(store.customer("alice"),"balance_cents")).isEqualTo(35000);assertThat(store.refund(business).get("status")).isEqualTo("RESERVED_NO_PAYOUT");
        assertThatThrownBy(()->payments.requestRefund("alice",order,6000,"exceeds original remaining",key())).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->shop.processBalanceRefund("owner",business,row("action","RELEASE","amount",5000L,"note","wrong path"),key())).isInstanceOf(BusinessException.class);
        when(channel.submitRefund(any())).thenAnswer(call->refundResult(id,"PROCESSING"));String operation=key();payments.submitRefund("owner",id,operation);payments.submitRefund("owner",id,operation);
        String transaction=s(pay.receipt(order),"provider_transaction_id");verify(channel,times(1)).submitRefund(argThat(intent->intent.order().id().equals(order)&&intent.transactionId().equals(transaction)&&intent.amountCents()==5000));
        assertThat(n(store.customer("alice"),"balance_cents")).isEqualTo(35000);assertThatThrownBy(()->payments.submitRefund("owner",id,key())).isInstanceOf(BusinessException.class);
    }
    @Test void refundSuccessIsIdempotentAndConsumesReservationWithoutAnotherDebit(){
        String order=paid(),id=ready(order);refundNotice.set(refundResult(id,"SUCCESS"));payments.refundCallback(callback());payments.refundCallback(callback());
        assertThat(pay.refund(id).get("status")).isEqualTo("SUCCESS");assertThat(store.refund(s(pay.refund(id),"business_refund_id")).get("status")).isEqualTo("CHANNEL_REFUNDED");
        assertThat(n(store.customer("alice"),"balance_cents")).isEqualTo(35000);assertThat(store.ledger("alice")).filteredOn(e->"BALANCE_REFUND_CHANNEL_SETTLED".equals(e.get("kind"))).hasSize(1);
        @SuppressWarnings("unchecked") var summary=(Map<String,Object>)shop.overview("alice").get("summary");assertThat(n(summary,"channel_refunded_balance_cents")).isEqualTo(5000);assertThat(n(summary,"recharged_principal_cents")).isEqualTo(40000);assertThat(n(summary,"fulfilled_reference_cents")).isZero();
    }
    @Test void unknownRefundCannotReleaseOrResendButQueryCanRecoverIt(){
        String id=ready(paid()),operation=key();when(channel.submitRefund(any())).thenThrow(new RuntimeException("secret provider debug"));
        assertThat(attemptState(payments.submitRefund("owner",id,operation))).isEqualTo("UNKNOWN");payments.submitRefund("owner",id,operation);verify(channel,times(1)).submitRefund(any());
        assertThatThrownBy(()->payments.releaseRefund("owner",id,"unknown cannot release",key())).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->payments.submitRefund("owner",id,key())).isInstanceOf(BusinessException.class);
        payments.reconciliation("owner","REFUND",id,"fixture statement","still checking channel; no forced success",key());
        assertThat(pay.refund(id).get("status")).isEqualTo("UNKNOWN");when(channel.queryRefund(any())).thenAnswer(call->refundResult(id,"SUCCESS"));payments.queryRefund("owner",null,id,key());
        assertThat(pay.refund(id).get("status")).isEqualTo("SUCCESS");assertThat(n(store.customer("alice"),"balance_cents")).isEqualTo(35000);
    }
    @Test void definitiveClosedRefundCanReleaseButContradictoryLateSuccessStaysForReview(){
        String id=ready(paid());when(channel.submitRefund(any())).thenAnswer(call->refundResult(id,"CLOSED"));payments.submitRefund("owner",id,key());
        String operation=key();payments.releaseRefund("owner",id,"verified closed; restore reservation",operation);payments.releaseRefund("owner",id,"verified closed; restore reservation",operation);
        assertThat(n(store.customer("alice"),"balance_cents")).isEqualTo(40000);refundNotice.set(refundResult(id,"SUCCESS"));
        assertThatThrownBy(()->payments.refundCallback(callback())).isInstanceOf(ProviderFailure.class);assertThat(pay.refund(id).get("status")).isEqualTo("CONFLICT_REVIEW");assertThat(pay.notes("REFUND",id)).isNotEmpty();
        assertThat(n(store.customer("alice"),"balance_cents")).isEqualTo(40000);assertThat(pay.refund(id).get("settlement_ledger_id")).isNull();
    }
    @Test void refundSettlementFailureDoesNotAcknowledgeOrConsumeTheReservation(){
        String id=ready(paid());refundNotice.set(refundResult(id,"SUCCESS"));
        doThrow(new IllegalStateException("synthetic settlement failure")).when(store).insertEntry(argThat(row->"BALANCE_REFUND_CHANNEL_SETTLED".equals(row.get("kind"))));
        assertThatThrownBy(()->payments.refundCallback(callback())).isInstanceOf(IllegalStateException.class);
        assertThat(pay.refund(id).get("status")).isEqualTo("READY");assertThat(pay.refund(id).get("provider_refund_id")).isNull();assertThat(n(store.customer("alice"),"balance_cents")).isEqualTo(35000);
        reset(store);payments.refundCallback(callback());assertThat(pay.refund(id).get("status")).isEqualTo("SUCCESS");
    }
    @Test void browserMutationRoutesKeepCsrfRolesAndManualNotesCannotInventPayment() throws Exception {
        String id=create();mvc.perform(post("/api/payments/wechat/prepay").with(user("alice").roles("CUSTOMER")).contentType("application/json").content("{\"orderId\":\""+id+"\"}")).andExpect(status().isForbidden());
        mvc.perform(get("/api/me/payment-orders/"+id).with(user("bob").roles("CUSTOMER"))).andExpect(status().isNotFound());
        mvc.perform(post("/api/owner/payment-orders/"+id+"/reconciliation").with(user("alice").roles("CUSTOMER")).with(csrf()).header("Idempotency-Key",key()).contentType("application/json").content("{\"note\":\"forbidden\"}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/owner/payment-orders/"+id+"/reconciliation").with(user("owner").roles("OWNER")).with(csrf()).header("Idempotency-Key",key()).contentType("application/json").content("{\"note\":\"checking statement\",\"status\":\"PAID\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.financial_mutation").value(false));
        assertThat(pay.order(id).get("status")).isEqualTo("CREATED");assertThat(n(store.customer("alice"),"balance_cents")).isEqualTo(30000);
    }
    @Test void manualNegativeReconciliationNeedsExactAmountAndEvidenceAndNeverClaimsProviderSuccess(){
        String id=ready(paid());when(channel.submitRefund(any())).thenThrow(new PaymentGateway.UnknownResult());payments.submitRefund("owner",id,key());
        assertThatThrownBy(()->payments.confirmNoRefund("owner",id,4999,"fixture-statement","wrong amount",key())).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->payments.confirmNoRefund("owner",id,5000,"","missing evidence",key())).isInstanceOf(BusinessException.class);
        String operation=key();var result=payments.confirmNoRefund("owner",id,5000,"fixture-statement","owner confirmed no refund from external statement",operation);
        payments.confirmNoRefund("owner",id,5000,"fixture-statement","owner confirmed no refund from external statement",operation);
        assertThat(result.get("provider_verified")).isEqualTo(false);assertThat(result.get("financial_mutation")).isEqualTo(false);assertThat(pay.notes("REFUND",id)).hasSize(1);assertThat(n(store.customer("alice"),"balance_cents")).isEqualTo(35000);
        payments.releaseRefund("owner",id,"manual external no-refund evidence recorded",key());assertThat(n(store.customer("alice"),"balance_cents")).isEqualTo(40000);
    }
    @Test void knownProviderSuccessCannotBeOverriddenByManualNoRefundClaim(){
        String id=ready(paid());refundNotice.set(refundResult(id,"SUCCESS"));payments.refundCallback(callback());
        assertThatThrownBy(()->payments.confirmNoRefund("owner",id,5000,"fixture","must not overwrite verified result",key())).isInstanceOf(BusinessException.class);
        assertThat(pay.refund(id).get("status")).isEqualTo("SUCCESS");assertThat(n(store.customer("alice"),"balance_cents")).isEqualTo(35000);
    }
    @Test void refundMerchantOriginalTransactionCurrencyAndAmountsMustAllMatch(){
        String id=ready(paid());var correct=refundResult(id,"SUCCESS");
        var wrong=List.of(new PaymentGateway.Refund(id,correct.orderId(),"other-merchant",correct.transactionId(),correct.providerRefundId(),10000,5000,"CNY","SUCCESS"),
            new PaymentGateway.Refund(id,correct.orderId(),correct.merchantId(),"other-payment",correct.providerRefundId(),10000,5000,"CNY","SUCCESS"),
            new PaymentGateway.Refund(id,"other-order",correct.merchantId(),correct.transactionId(),correct.providerRefundId(),10000,5000,"CNY","SUCCESS"),
            new PaymentGateway.Refund(id,correct.orderId(),correct.merchantId(),correct.transactionId(),correct.providerRefundId(),10000,4999,"CNY","SUCCESS"),
            new PaymentGateway.Refund(id,correct.orderId(),correct.merchantId(),correct.transactionId(),correct.providerRefundId(),9999,5000,"CNY","SUCCESS"),
            new PaymentGateway.Refund(id,correct.orderId(),correct.merchantId(),correct.transactionId(),correct.providerRefundId(),10000,5000,"USD","SUCCESS"));
        for(var value:wrong){refundNotice.set(value);assertThatThrownBy(()->payments.refundCallback(callback())).isInstanceOf(ProviderFailure.class);}
        assertThat(pay.refund(id).get("status")).isEqualTo("READY");assertThat(pay.refund(id).get("settlement_ledger_id")).isNull();assertThat(n(store.customer("alice"),"balance_cents")).isEqualTo(35000);
    }
    @Test void refundQueryCannotSettleAnotherRefundsValidResult(){
        String order=paid(),first=ready(order),second=ready(order);when(channel.queryRefund(any())).thenAnswer(call->refundResult(second,"SUCCESS"));
        assertThat(attemptState(payments.queryRefund("owner",null,first,key()))).isEqualTo("UNKNOWN");
        assertThat(pay.refund(second).get("status")).isEqualTo("READY");assertThat(pay.refund(first).get("settlement_ledger_id")).isNull();assertThat(n(store.customer("alice"),"balance_cents")).isEqualTo(30000);
    }
    @Test void refundCallbackAndQueryContentionConsumesOnlyOneReservation() throws Exception {
        String id=ready(paid());var value=refundResult(id,"SUCCESS");refundNotice.set(value);when(channel.queryRefund(any())).thenReturn(value);
        var pool=Executors.newFixedThreadPool(2);var gate=new CountDownLatch(1);
        try{var a=pool.submit(()->{gate.await();payments.refundCallback(callback());return null;});var b=pool.submit(()->{gate.await();payments.queryRefund("owner",null,id,key());return null;});gate.countDown();a.get(10,TimeUnit.SECONDS);b.get(10,TimeUnit.SECONDS);}finally{pool.shutdownNow();}
        assertThat(pay.refund(id).get("status")).isEqualTo("SUCCESS");assertThat(store.ledger("alice")).filteredOn(e->"BALANCE_REFUND_CHANNEL_SETTLED".equals(e.get("kind"))).hasSize(1);assertThat(n(store.customer("alice"),"balance_cents")).isEqualTo(35000);
    }
}
