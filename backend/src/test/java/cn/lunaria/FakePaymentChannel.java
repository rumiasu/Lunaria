package cn.lunaria;

import cn.lunaria.integration.*;
import com.fasterxml.jackson.databind.*;

/** Test-source-only channel. Literal fixture signatures have no cryptographic or production meaning. */
final class FakePaymentChannel implements PaymentGateway {
    boolean unknownPrepare,unknownRefund,unknownRefundQuery;String paymentState="NOT_PAID",refundState="PROCESSING";int prepares,submits,queries;
    private final ObjectMapper json=new ObjectMapper();
    public boolean available(){return true;}public String mode(){return "ISOLATED_FAKE_CHANNEL";}public Context context(){return new Context("fixture-customer-app","fixture-merchant");}
    public String prepare(Order order){prepares++;if(unknownPrepare)throw new UnknownResult();return "fixture-prepay-"+order.id();}
    private JsonNode body(WechatPaymentAdapter.Callback callback){
        if(!"fixture-valid".equals(callback.signature()))throw new ProviderFailure(400,"FIXTURE_SIGNATURE_REJECTED","isolated verifier rejected signature","wechat_payment");
        try{return json.readTree(callback.rawBody());}catch(Exception error){throw new ProviderFailure(400,"FIXTURE_BODY_REJECTED","isolated body rejected","wechat_payment");}
    }
    public Payment verifyPayment(WechatPaymentAdapter.Callback callback){var value=body(callback);String id=value.path("orderId").asText();return new Payment(id,"fixture-customer-app","fixture-merchant","fixture-legacy-open",value.path("amountCents").asLong(),"CNY","fixture-tx-"+id,"SUCCESS");}
    public Payment queryPayment(Order order){queries++;return new Payment(order.id(),order.appId(),order.merchantId(),order.payerOpenId(),order.amountCents(),"CNY","fixture-tx-"+order.id(),paymentState);}
    private Refund result(RefundIntent intent){return new Refund(intent.id(),intent.order().id(),intent.order().merchantId(),intent.transactionId(),"fixture-refund-"+intent.id(),intent.order().amountCents(),intent.amountCents(),"CNY",refundState);}
    public Refund submitRefund(RefundIntent intent){submits++;if(unknownRefund)throw new UnknownResult();return result(intent);}
    public Refund queryRefund(RefundIntent intent){if(unknownRefundQuery)throw new UnknownResult();return result(intent);}
    public Refund verifyRefund(WechatPaymentAdapter.Callback callback){var value=body(callback);String id=value.path("refundId").asText(),order=value.path("orderId").asText();return new Refund(id,order,"fixture-merchant","fixture-tx-"+order,"fixture-refund-"+id,value.path("orderCents").asLong(),value.path("refundCents").asLong(),"CNY","SUCCESS");}
}
