package cn.lunaria.integration;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Capability status distinguishes configured identity transport from the still-closed money/send routes. */
@Component
public final class ExternalIntegrationBoundary {
    private final WechatAppSettings settings;
    private final WechatOwnerAuthentication owner;
    private final PaymentGateway payments;
    public ExternalIntegrationBoundary(WechatAppSettings settings,WechatOwnerAuthentication owner,PaymentGateway payments){this.settings=settings;this.owner=owner;this.payments=payments;}
    public enum Feature {
        WECHAT_IDENTITY("wechat_identity","微信身份"),
        WECHAT_OWNER_IDENTITY("wechat_owner_identity","店长微信身份"),
        WECHAT_PAYMENT("wechat_payment","微信在线支付"),
        WECHAT_NOTIFICATION("wechat_notification","微信订阅通知");
        public final String key;
        public final String label;
        Feature(String key,String label){this.key=key;this.label=label;}
    }
    public record Capability(boolean enabled,String state) {}
    public Map<String,Capability> capabilities() {
        var result=new LinkedHashMap<String,Capability>();
        for(var feature:Feature.values())result.put(feature.key,new Capability(false,"DISABLED_NOT_CONFIGURED"));
        if(settings.configured())result.put(Feature.WECHAT_IDENTITY.key,new Capability(true,"CONFIGURED_UNVERIFIED"));
        if(owner.configured())result.put(Feature.WECHAT_OWNER_IDENTITY.key,new Capability(true,"CONFIGURED_UNVERIFIED"));
        if(payments.available())result.put(Feature.WECHAT_PAYMENT.key,new Capability(true,payments.mode()));
        return result;
    }
    public Unavailable unavailable(Feature feature) {return new Unavailable(feature);}
    public static final class Unavailable extends RuntimeException {
        public final Feature feature;
        private Unavailable(Feature feature){super(feature.label+"尚未接入；当前不调用外部服务、不创建身份或资金记录");this.feature=feature;}
    }
}
