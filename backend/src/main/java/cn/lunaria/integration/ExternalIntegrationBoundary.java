package cn.lunaria.integration;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Explicit disabled adapter. No credentials, HTTP client, fake identity, payment success or send fallback. */
@Component
public final class ExternalIntegrationBoundary {
    public enum Feature {
        WECHAT_IDENTITY("wechat_identity","微信身份"),
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
        return result;
    }
    public Unavailable unavailable(Feature feature) {return new Unavailable(feature);}
    public static final class Unavailable extends RuntimeException {
        public final Feature feature;
        private Unavailable(Feature feature){super(feature.label+"尚未接入；当前不调用外部服务、不创建身份或资金记录");this.feature=feature;}
    }
}
