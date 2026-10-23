package cn.lunaria.integration;

import com.wechat.pay.java.core.notification.*;
import com.wechat.pay.java.service.payments.jsapi.JsapiService;
import com.wechat.pay.java.service.payments.jsapi.model.*;
import com.wechat.pay.java.service.payments.model.*;
import java.time.*;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** SDK boundary mapping only; mocked SDK, no signing keys, crypto claims or provider connection. */
class WechatPaymentAdapterTest {
    private final JsapiService sdk=mock(JsapiService.class);
    private final NotificationParser parser=mock(NotificationParser.class);
    private final Clock clock=Clock.fixed(Instant.parse("2026-10-23T10:00:00Z"),ZoneOffset.UTC);
    private final WechatPaymentAdapter adapter=new WechatPaymentAdapter("fixture-app","fixture-merchant","https://lunaria.example.invalid/payments/notify",sdk,parser,clock);
    private final WechatPaymentAdapter.ExpectedPayment expected=new WechatPaymentAdapter.ExpectedPayment("fixture-order-01","fixture-open",9500);
    private WechatPaymentAdapter.Callback callback(){return new WechatPaymentAdapter.Callback("fixture-serial",Long.toString(clock.instant().getEpochSecond()),"fixture-nonce","fixture-signature","{\"fixture\":true}");}
    private Transaction transaction(){
        var t=new Transaction();t.setAppid("fixture-app");t.setMchid("fixture-merchant");t.setOutTradeNo("fixture-order-01");t.setTradeState(Transaction.TradeStateEnum.SUCCESS);t.setTransactionId("fixture-provider-id");
        var a=new TransactionAmount();a.setTotal(9500);a.setCurrency("CNY");t.setAmount(a);var p=new TransactionPayer();p.setOpenid("fixture-open");t.setPayer(p);return t;
    }
    @Test void sdkPrepayMapsServerAmountOrderAndBoundIdentity(){
        var response=new PrepayResponse();response.setPrepayId("fixture-prepay");when(sdk.prepay(any())).thenReturn(response);
        assertThat(adapter.prepare(new WechatPaymentAdapter.Intent("fixture-order-01","fixture-open",9500,"fixture package"))).isEqualTo("fixture-prepay");
        var captured=ArgumentCaptor.forClass(PrepayRequest.class);verify(sdk).prepay(captured.capture());var r=captured.getValue();
        assertThat(r.getAppid()).isEqualTo("fixture-app");assertThat(r.getMchid()).isEqualTo("fixture-merchant");assertThat(r.getNotifyUrl()).isEqualTo("https://lunaria.example.invalid/payments/notify");assertThat(r.getOutTradeNo()).isEqualTo(expected.orderId());assertThat(r.getPayer().getOpenid()).isEqualTo(expected.boundOpenId());assertThat(r.getAmount().getTotal()).isEqualTo(9500);assertThat(r.getAmount().getCurrency()).isEqualTo("CNY");
    }
    @Test void invalidOrOverflowingAmountsNeverCallSdk(){
        for(long amount:List.of(0L,-1L,2147483648L))assertThatThrownBy(()->adapter.prepare(new WechatPaymentAdapter.Intent("fixture-order-01","fixture-open",amount,"fixture"))).isInstanceOf(ProviderFailure.class);
        verifyNoInteractions(sdk);
    }
    @Test void sdkExceptionsAreSanitizedAndNeverExposeRequestOrKeyDetails(){
        when(sdk.prepay(any())).thenThrow(new RuntimeException("private-key provider request debug"));
        assertThatThrownBy(()->adapter.prepare(new WechatPaymentAdapter.Intent("fixture-order-01","fixture-open",9500,"fixture"))).isInstanceOfSatisfying(ProviderFailure.class,e->{assertThat(e.code).isEqualTo("WECHAT_PREPAY_REJECTED");assertThat(e.getMessage()).doesNotContain("private-key","debug");assertThat(e.getCause()).isNull();});
    }
    @Test void callbackAlwaysUsesOfficialVerificationAndDecryptionParser(){
        when(parser.parse(any(RequestParam.class),eq(Transaction.class))).thenReturn(transaction());
        assertThat(adapter.verify(callback(),expected)).isEqualTo(new WechatPaymentAdapter.VerifiedPayment("fixture-order-01","fixture-provider-id",9500));verify(parser).parse(any(RequestParam.class),eq(Transaction.class));
    }
    @Test void parserRejectionCannotBecomeAcceptedPayment(){
        when(parser.parse(any(RequestParam.class),eq(Transaction.class))).thenThrow(new RuntimeException("raw payload provider debug"));
        assertThatThrownBy(()->adapter.verify(callback(),expected)).isInstanceOfSatisfying(ProviderFailure.class,e->{assertThat(e.code).isEqualTo("WECHAT_CALLBACK_REJECTED");assertThat(e.getCause()).isNull();assertThat(e.getMessage()).doesNotContain("payload","debug");});
    }
    @Test void mismatchedMerchantAppOrderPayerStateCurrencyOrAmountAreRejected(){
        List<Consumer<Transaction>> changes=List.of(t->t.setMchid("other"),t->t.setAppid("other"),t->t.setOutTradeNo("other"),t->t.getPayer().setOpenid("other"),t->t.setTradeState(Transaction.TradeStateEnum.NOTPAY),t->t.getAmount().setCurrency("USD"),t->t.getAmount().setTotal(1),t->t.setTransactionId(""));
        for(var change:changes){var t=transaction();change.accept(t);when(parser.parse(any(RequestParam.class),eq(Transaction.class))).thenReturn(t);assertThatThrownBy(()->adapter.verify(callback(),expected)).isInstanceOfSatisfying(ProviderFailure.class,e->assertThat(e.code).isEqualTo("WECHAT_PAYMENT_MISMATCH"));}
    }
    @Test void staleOrMalformedHeadersAreRejectedBeforeTheParser(){
        var c=callback();for(String timestamp:List.of("not-a-number",Long.toString(clock.instant().minusSeconds(301).getEpochSecond()),Long.toString(clock.instant().plusSeconds(301).getEpochSecond())))assertThatThrownBy(()->adapter.verify(new WechatPaymentAdapter.Callback(c.serial(),timestamp,c.nonce(),c.signature(),c.rawBody()),expected)).isInstanceOf(ProviderFailure.class);
        verifyNoInteractions(parser);
    }
}
