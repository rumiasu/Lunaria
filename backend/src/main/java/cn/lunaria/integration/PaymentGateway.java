package cn.lunaria.integration;

/** Authenticated channel seam. The application never accepts these results from a browser. */
public interface PaymentGateway {
    record Context(String appId,String merchantId) {}
    record Order(String id,String appId,String merchantId,String payerOpenId,long amountCents) {}
    record Payment(String orderId,String appId,String merchantId,String payerOpenId,long amountCents,String currency,String transactionId,String state) {}
    record RefundIntent(String id,Order order,String transactionId,long amountCents) {}
    record Refund(String id,String orderId,String merchantId,String transactionId,String providerRefundId,long orderCents,long refundCents,String currency,String state) {}
    boolean available();
    default String mode(){return available()?"CONFIGURED_UNVERIFIED":"DISABLED";}
    Context context();
    String prepare(Order order);
    Payment verifyPayment(WechatPaymentAdapter.Callback callback);
    Payment queryPayment(Order order);
    Refund submitRefund(RefundIntent refund);
    Refund verifyRefund(WechatPaymentAdapter.Callback callback);
    Refund queryRefund(RefundIntent refund);
    final class UnknownResult extends RuntimeException {
        public UnknownResult(){super("渠道结果未知，请查单或保存人工对账说明，不自动重发");}
    }
    static ProviderFailure disabled(){return new ProviderFailure(503,"INTEGRATION_NOT_CONFIGURED","支付渠道未配置，当前保持关闭","wechat_payment");}
}
