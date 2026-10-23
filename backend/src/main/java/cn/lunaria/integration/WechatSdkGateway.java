package cn.lunaria.integration;

import com.wechat.pay.java.core.notification.NotificationParser;
import com.wechat.pay.java.service.payments.jsapi.JsapiService;
import com.wechat.pay.java.service.payments.jsapi.model.QueryOrderByOutTradeNoRequest;
import com.wechat.pay.java.service.payments.model.Transaction;
import com.wechat.pay.java.service.refund.RefundService;
import com.wechat.pay.java.service.refund.model.*;
import java.net.URI;
import java.time.Clock;
import java.util.Objects;

/** Explicitly constructed SDK bridge. No credential builder, automatic Spring registration or live default. */
public final class WechatSdkGateway implements PaymentGateway {
    private final Context context;private final JsapiService payments;private final RefundService refunds;
    private final WechatPaymentAdapter adapter;private final String refundNotify;
    public WechatSdkGateway(String app,String merchant,String paymentNotify,String refundNotify,JsapiService payments,RefundService refunds,NotificationParser parser,Clock clock){
        context=new Context(app,merchant);this.payments=Objects.requireNonNull(payments);this.refunds=Objects.requireNonNull(refunds);
        adapter=new WechatPaymentAdapter(app,merchant,paymentNotify,payments,parser,clock);
        URI uri=URI.create(refundNotify);if(!"https".equals(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null||uri.getFragment()!=null)throw new IllegalArgumentException("HTTPS refund callback required");this.refundNotify=refundNotify;
    }
    public boolean available(){return true;}
    public Context context(){return context;}
    private void check(Order order){if(order==null||!context.appId().equals(order.appId())||!context.merchantId().equals(order.merchantId()))throw mismatch();}
    public String prepare(Order order){check(order);return adapter.prepare(new WechatPaymentAdapter.Intent(order.id(),order.payerOpenId(),order.amountCents(),"本金充值"));}
    private <T>T verified(WechatPaymentAdapter.Callback callback,Class<T> type){try{return adapter.verifiedBody(callback,type);}catch(ProviderFailure error){throw new ProviderFailure(error.status,error.code,error.getMessage(),"wechat_payment");}}
    public Payment verifyPayment(WechatPaymentAdapter.Callback callback){return payment(verified(callback,Transaction.class));}
    public Payment queryPayment(Order order){
        check(order);var request=new QueryOrderByOutTradeNoRequest();request.setMchid(context.merchantId());request.setOutTradeNo(order.id());
        Transaction result;try{result=payments.queryOrderByOutTradeNo(request);}catch(RuntimeException error){throw new UnknownResult();}return payment(result);
    }
    private Payment payment(Transaction t){
        if(t==null||!context.appId().equals(t.getAppid())||!context.merchantId().equals(t.getMchid())||t.getAmount()==null||t.getAmount().getTotal()==null||t.getOutTradeNo()==null)throw mismatch();
        String state=t.getTradeState()==null?"UNKNOWN":switch(t.getTradeState()){case SUCCESS->"SUCCESS";case NOTPAY,USERPAYING->"NOT_PAID";case CLOSED,REVOKED,PAYERROR->"CLOSED";default->"UNKNOWN";};
        return new Payment(t.getOutTradeNo(),t.getAppid(),t.getMchid(),t.getPayer()==null?null:t.getPayer().getOpenid(),t.getAmount().getTotal(),t.getAmount().getCurrency(),t.getTransactionId(),state);
    }
    public PaymentGateway.Refund submitRefund(RefundIntent intent){
        check(intent.order());if(intent.amountCents()<=0||intent.amountCents()>intent.order().amountCents()||intent.transactionId()==null||intent.transactionId().isBlank())throw mismatch();
        var request=new CreateRequest();request.setTransactionId(intent.transactionId());request.setOutRefundNo(intent.id());request.setNotifyUrl(refundNotify);request.setReason("本金余额退款");
        var amount=new AmountReq();amount.setTotal(intent.order().amountCents());amount.setRefund(intent.amountCents());amount.setCurrency("CNY");request.setAmount(amount);
        com.wechat.pay.java.service.refund.model.Refund response;try{response=refunds.create(request);}catch(RuntimeException error){throw new UnknownResult();}return refund(response);
    }
    public PaymentGateway.Refund queryRefund(RefundIntent intent){
        check(intent.order());var request=new QueryByOutRefundNoRequest();request.setOutRefundNo(intent.id());
        com.wechat.pay.java.service.refund.model.Refund response;try{response=refunds.queryByOutRefundNo(request);}catch(RuntimeException error){throw new UnknownResult();}return refund(response);
    }
    // This SDK refund model has no mchid field. Merchant identity is the configured authenticated SDK/parser context.
    private PaymentGateway.Refund refund(com.wechat.pay.java.service.refund.model.Refund r){
        if(r==null||r.getAmount()==null||r.getAmount().getTotal()==null||r.getAmount().getRefund()==null)throw mismatch();
        return new PaymentGateway.Refund(r.getOutRefundNo(),r.getOutTradeNo(),context.merchantId(),r.getTransactionId(),r.getRefundId(),r.getAmount().getTotal(),r.getAmount().getRefund(),r.getAmount().getCurrency(),r.getStatus()==null?"UNKNOWN":r.getStatus().name());
    }
    public PaymentGateway.Refund verifyRefund(WechatPaymentAdapter.Callback callback){
        var r=verified(callback,RefundNotification.class);if(r==null||r.getAmount()==null||r.getAmount().getTotal()==null||r.getAmount().getRefund()==null)throw mismatch();
        return new PaymentGateway.Refund(r.getOutRefundNo(),r.getOutTradeNo(),context.merchantId(),r.getTransactionId(),r.getRefundId(),r.getAmount().getTotal(),r.getAmount().getRefund(),r.getAmount().getCurrency(),r.getRefundStatus()==null?"UNKNOWN":r.getRefundStatus().name());
    }
    private ProviderFailure mismatch(){return new ProviderFailure(409,"PAYMENT_CONTEXT_MISMATCH","渠道返回与已配置商户或订单不一致","wechat_payment");}
}
