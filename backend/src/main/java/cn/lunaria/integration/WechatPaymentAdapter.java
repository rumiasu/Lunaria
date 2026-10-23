package cn.lunaria.integration;

import com.wechat.pay.java.core.notification.*;
import com.wechat.pay.java.service.payments.jsapi.JsapiService;
import com.wechat.pay.java.service.payments.jsapi.model.*;
import com.wechat.pay.java.service.payments.model.Transaction;
import java.net.URI;
import java.time.Clock;
import java.util.Objects;

/** SDK seam only: no Spring bean, HTTP callback, order creation, balance credit or refund execution. */
public final class WechatPaymentAdapter {
    public record Intent(String orderId,String boundOpenId,long amountCents,String description) {}
    public record Callback(String serial,String timestamp,String nonce,String signature,String rawBody) {}
    public record ExpectedPayment(String orderId,String boundOpenId,long amountCents) {}
    public record VerifiedPayment(String orderId,String providerTransactionId,long amountCents) {}
    private final String appId,merchantId,notifyUrl;
    private final JsapiService service;
    private final NotificationParser parser;
    private final Clock clock;
    public WechatPaymentAdapter(String appId,String merchantId,String notifyUrl,JsapiService service,NotificationParser parser,Clock clock){
        if(blank(appId)||blank(merchantId)||blank(notifyUrl))throw new IllegalArgumentException("payment identifiers required");
        URI uri=URI.create(notifyUrl);if(!"https".equals(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null||uri.getFragment()!=null)throw new IllegalArgumentException("HTTPS callback required");
        this.appId=appId;this.merchantId=merchantId;this.notifyUrl=notifyUrl;this.service=Objects.requireNonNull(service);this.parser=Objects.requireNonNull(parser);this.clock=Objects.requireNonNull(clock);
    }
    /** Caller must derive the intent from an authoritative pending server order and a bound identity. */
    public String prepare(Intent intent){
        if(intent==null||!validOrder(intent.orderId())||blank(intent.boundOpenId())||intent.boundOpenId().length()>128||intent.amountCents()<=0||intent.amountCents()>Integer.MAX_VALUE||blank(intent.description())||intent.description().getBytes(java.nio.charset.StandardCharsets.UTF_8).length>127)throw invalid();
        var request=new PrepayRequest();request.setAppid(appId);request.setMchid(merchantId);request.setNotifyUrl(notifyUrl);request.setOutTradeNo(intent.orderId());request.setDescription(intent.description());
        var amount=new Amount();amount.setTotal(Math.toIntExact(intent.amountCents()));amount.setCurrency("CNY");request.setAmount(amount);
        var payer=new Payer();payer.setOpenid(intent.boundOpenId());request.setPayer(payer);
        try{var response=service.prepay(request);if(response==null||blank(response.getPrepayId()))throw ProviderFailure.unavailable("WECHAT_PREPAY_MALFORMED");return response.getPrepayId();}
        catch(ProviderFailure error){throw error;}catch(Exception error){throw ProviderFailure.unavailable("WECHAT_PREPAY_REJECTED");}
    }
    /** Official parser verifies/decrypts first; application then checks the entire expected order context. */
    public VerifiedPayment verify(Callback callback,ExpectedPayment expected){
        if(callback==null||expected==null||!validOrder(expected.orderId())||blank(expected.boundOpenId())||expected.amountCents()<=0||expected.amountCents()>Integer.MAX_VALUE||blank(callback.serial())||blank(callback.timestamp())||blank(callback.nonce())||blank(callback.signature())||blank(callback.rawBody())||callback.rawBody().length()>65536)throw invalid();
        Transaction transaction=verifiedBody(callback,Transaction.class);
        if(transaction==null||!appId.equals(transaction.getAppid())||!merchantId.equals(transaction.getMchid())||!expected.orderId().equals(transaction.getOutTradeNo())||transaction.getTradeState()!=Transaction.TradeStateEnum.SUCCESS||blank(transaction.getTransactionId())||transaction.getPayer()==null||!expected.boundOpenId().equals(transaction.getPayer().getOpenid())||transaction.getAmount()==null||transaction.getAmount().getTotal()==null||transaction.getAmount().getTotal().longValue()!=expected.amountCents()||!"CNY".equals(transaction.getAmount().getCurrency()))throw new ProviderFailure(409,"WECHAT_PAYMENT_MISMATCH","支付通知与待支付订单不一致");
        return new VerifiedPayment(expected.orderId(),transaction.getTransactionId(),expected.amountCents());
    }
    <T>T verifiedBody(Callback callback,Class<T> type){
        if(callback==null||blank(callback.serial())||blank(callback.timestamp())||blank(callback.nonce())||blank(callback.signature())||blank(callback.rawBody())||callback.rawBody().length()>65536)throw invalid();
        try{long timestamp=Long.parseLong(callback.timestamp());long now=clock.instant().getEpochSecond();if(timestamp<now-300||timestamp>now+300)throw invalid();}catch(NumberFormatException error){throw invalid();}
        try{var request=new RequestParam.Builder().serialNumber(callback.serial()).timestamp(callback.timestamp()).nonce(callback.nonce()).signature(callback.signature()).body(callback.rawBody()).build();return parser.parse(request,type);}
        catch(Exception error){throw new ProviderFailure(400,"WECHAT_CALLBACK_REJECTED","微信支付通知未通过校验");}
    }
    private static boolean blank(String s){return s==null||s.isBlank();}
    private static boolean validOrder(String s){return s!=null&&s.matches("[A-Za-z0-9_-]{6,32}");}
    private static ProviderFailure invalid(){return new ProviderFailure(400,"WECHAT_PAYMENT_INPUT_INVALID","支付参数不正确");}
}
