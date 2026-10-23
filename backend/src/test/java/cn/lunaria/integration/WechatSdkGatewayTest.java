package cn.lunaria.integration;

import com.wechat.pay.java.core.notification.*;
import com.wechat.pay.java.service.payments.jsapi.JsapiService;
import com.wechat.pay.java.service.payments.jsapi.model.QueryOrderByOutTradeNoRequest;
import com.wechat.pay.java.service.payments.model.*;
import com.wechat.pay.java.service.refund.RefundService;
import com.wechat.pay.java.service.refund.model.*;
import java.time.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Official SDK mapping, mocked clients/parser. No credentials, signing keys or real channel requests. */
class WechatSdkGatewayTest {
    private final JsapiService payments=mock(JsapiService.class);private final RefundService refunds=mock(RefundService.class);private final NotificationParser parser=mock(NotificationParser.class);
    private final Clock clock=Clock.fixed(Instant.parse("2026-10-23T12:00:00Z"),ZoneOffset.UTC);
    private final WechatSdkGateway gateway=new WechatSdkGateway("fixture-app","fixture-merchant","https://example.invalid/payment","https://example.invalid/refund",payments,refunds,parser,clock);
    private final PaymentGateway.Order order=new PaymentGateway.Order("fixture-order-01","fixture-app","fixture-merchant","fixture-open",10000);
    private final PaymentGateway.RefundIntent intent=new PaymentGateway.RefundIntent("fixture-refund-01",order,"fixture-payment-tx",5000);
    private WechatPaymentAdapter.Callback callback(){return new WechatPaymentAdapter.Callback("fixture-serial",Long.toString(clock.instant().getEpochSecond()),"fixture-nonce","fixture-signature","fixture encrypted resource");}
    private com.wechat.pay.java.service.refund.model.Refund refund(Status status){var r=new com.wechat.pay.java.service.refund.model.Refund();r.setOutTradeNo(order.id());r.setOutRefundNo(intent.id());r.setTransactionId(intent.transactionId());r.setRefundId("fixture-provider-refund");r.setStatus(status);var a=new Amount();a.setTotal(10000L);a.setRefund(5000L);a.setCurrency("CNY");r.setAmount(a);return r;}
    private Transaction payment(){var t=new Transaction();t.setAppid(order.appId());t.setMchid(order.merchantId());t.setOutTradeNo(order.id());t.setTransactionId(intent.transactionId());t.setTradeState(Transaction.TradeStateEnum.SUCCESS);var a=new TransactionAmount();a.setTotal(10000);a.setCurrency("CNY");t.setAmount(a);var payer=new TransactionPayer();payer.setOpenid(order.payerOpenId());t.setPayer(payer);return t;}
    @Test void paymentQueryUsesStoredMerchantAndOrderAndReturnsFullContext(){
        when(payments.queryOrderByOutTradeNo(any())).thenReturn(payment());var result=gateway.queryPayment(order);
        assertThat(result.state()).isEqualTo("SUCCESS");assertThat(result.merchantId()).isEqualTo(order.merchantId());assertThat(result.amountCents()).isEqualTo(10000);
        var request=ArgumentCaptor.forClass(QueryOrderByOutTradeNoRequest.class);verify(payments).queryOrderByOutTradeNo(request.capture());assertThat(request.getValue().getMchid()).isEqualTo(order.merchantId());assertThat(request.getValue().getOutTradeNo()).isEqualTo(order.id());
    }
    @Test void refundSubmissionUsesOriginalTransactionTotalAndStableRefundNumber(){
        when(refunds.create(any())).thenReturn(refund(Status.PROCESSING));assertThat(gateway.submitRefund(intent).state()).isEqualTo("PROCESSING");
        var request=ArgumentCaptor.forClass(CreateRequest.class);verify(refunds).create(request.capture());var value=request.getValue();
        assertThat(value.getTransactionId()).isEqualTo(intent.transactionId());assertThat(value.getOutTradeNo()).isNull();assertThat(value.getOutRefundNo()).isEqualTo(intent.id());assertThat(value.getNotifyUrl()).isEqualTo("https://example.invalid/refund");
        assertThat(value.getAmount().getTotal()).isEqualTo(10000);assertThat(value.getAmount().getRefund()).isEqualTo(5000);assertThat(value.getAmount().getCurrency()).isEqualTo("CNY");
    }
    @Test void refundQueryPreservesTheSameRefundIdentity(){
        when(refunds.queryByOutRefundNo(any())).thenReturn(refund(Status.CLOSED));assertThat(gateway.queryRefund(intent).state()).isEqualTo("CLOSED");
        var request=ArgumentCaptor.forClass(QueryByOutRefundNoRequest.class);verify(refunds).queryByOutRefundNo(request.capture());assertThat(request.getValue().getOutRefundNo()).isEqualTo(intent.id());
    }
    @Test void refundCallbackAlwaysUsesOfficialSignatureAndDecryptionParser(){
        var notice=new RefundNotification();notice.setOutTradeNo(order.id());notice.setOutRefundNo(intent.id());notice.setTransactionId(intent.transactionId());notice.setRefundId("fixture-provider-refund");notice.setAmount(refund(Status.SUCCESS).getAmount());notice.setRefundStatus(Status.SUCCESS);
        when(parser.parse(any(RequestParam.class),eq(RefundNotification.class))).thenReturn(notice);
        var result=gateway.verifyRefund(callback());assertThat(result.state()).isEqualTo("SUCCESS");assertThat(result.merchantId()).isEqualTo("fixture-merchant");assertThat(result.refundCents()).isEqualTo(5000);verify(parser).parse(any(RequestParam.class),eq(RefundNotification.class));
        clearInvocations(parser);var c=callback();assertThatThrownBy(()->gateway.verifyRefund(new WechatPaymentAdapter.Callback(c.serial(),"0",c.nonce(),c.signature(),c.rawBody()))).isInstanceOf(ProviderFailure.class);verifyNoInteractions(parser);
    }
    @Test void channelTransportExceptionsRemainUnknownAndInvalidIntentNeverCallsSdk(){
        when(refunds.create(any())).thenThrow(new RuntimeException("secret merchant transport debug"));
        assertThatThrownBy(()->gateway.submitRefund(intent)).isInstanceOfSatisfying(PaymentGateway.UnknownResult.class,e->{assertThat(e.getCause()).isNull();assertThat(e.getMessage()).doesNotContain("secret","debug");});
        clearInvocations(refunds);assertThatThrownBy(()->gateway.submitRefund(new PaymentGateway.RefundIntent(intent.id(),order,intent.transactionId(),10001))).isInstanceOf(ProviderFailure.class);verifyNoInteractions(refunds);
    }
    @Test void paymentParserRejectionAndMerchantMismatchNeverProduceVerifiedResults(){
        when(parser.parse(any(RequestParam.class),eq(Transaction.class))).thenThrow(new RuntimeException("private callback debug"));
        assertThatThrownBy(()->gateway.verifyPayment(callback())).isInstanceOfSatisfying(ProviderFailure.class,e->{assertThat(e.feature).isEqualTo("wechat_payment");assertThat(e.getCause()).isNull();});
        var wrong=payment();wrong.setMchid("other-merchant");when(payments.queryOrderByOutTradeNo(any())).thenReturn(wrong);assertThatThrownBy(()->gateway.queryPayment(order)).isInstanceOf(ProviderFailure.class);
    }
}
