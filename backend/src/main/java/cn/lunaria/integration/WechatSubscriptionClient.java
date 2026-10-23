package cn.lunaria.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.time.*;
import java.util.*;

/** Concrete subscribeMessage client; caller must supply a trusted consent check and an internally bound recipient. */
public final class WechatSubscriptionClient {
    @FunctionalInterface public interface Consent {boolean allowed(String customerId,String templateId);}
    public record Notice(String customerId,String boundOpenId,String templateId,Map<String,String> fields) {}
    public record Receipt(boolean providerAccepted) {}
    private final WechatAppSettings settings;private final WechatTransport transport;private final ObjectMapper json;private final Clock clock;private final Consent consent;
    private String token;private Instant refreshAt=Instant.EPOCH;
    public WechatSubscriptionClient(WechatAppSettings settings,WechatTransport transport,ObjectMapper json,Clock clock,Consent consent){this.settings=settings;this.transport=transport;this.json=json;this.clock=clock;this.consent=Objects.requireNonNull(consent);}
    private synchronized String token(){
        if(token!=null&&clock.instant().isBefore(refreshAt))return token;
        var uri=URI.create("https://api.weixin.qq.com/cgi-bin/token?grant_type=client_credential&appid="+WechatCodeClient.encode(settings.appId())+"&secret="+WechatCodeClient.encode(settings.secret()));
        var body=WechatCodeClient.parse(json,transport.request("GET",uri,null));
        String next=body.path("access_token").asText("");long ttl=body.path("expires_in").asLong(0);
        if(body.path("errcode").asInt(0)!=0||next.isBlank()||ttl<=0)throw ProviderFailure.unavailable("WECHAT_ACCESS_TOKEN_REJECTED");
        token=next;refreshAt=clock.instant().plusSeconds(Math.max(0,Math.min(ttl,7200)-60));return token;
    }
    public Receipt send(Notice notice){
        settings.requireConfigured();
        if(notice==null||notice.customerId()==null||notice.boundOpenId()==null||notice.boundOpenId().isBlank()||notice.templateId()==null||notice.templateId().isBlank()||notice.fields()==null||notice.fields().isEmpty()||notice.fields().size()>10)throw new ProviderFailure(400,"WECHAT_NOTICE_INVALID","通知对象、模板或字段不正确");
        if(!consent.allowed(notice.customerId(),notice.templateId()))throw new ProviderFailure(409,"WECHAT_SUBSCRIPTION_REQUIRED","没有可用订阅授权，不发送通知");
        var data=new LinkedHashMap<String,Object>();notice.fields().forEach((key,value)->{if(!key.matches("[A-Za-z][A-Za-z0-9_]{0,39}")||value==null||value.length()>200)throw new ProviderFailure(400,"WECHAT_NOTICE_INVALID","通知字段不正确");data.put(key,Map.of("value",value));});
        String payload;try{payload=json.writeValueAsString(Map.of("touser",notice.boundOpenId(),"template_id",notice.templateId(),"data",data));}catch(Exception error){throw ProviderFailure.unavailable("WECHAT_NOTICE_ENCODING_FAILED");}
        var uri=URI.create("https://api.weixin.qq.com/cgi-bin/message/subscribe/send?access_token="+WechatCodeClient.encode(token()));
        var result=WechatCodeClient.parse(json,transport.request("POST",uri,payload));
        if(!result.has("errcode")||result.path("errcode").asInt(-1)!=0)throw ProviderFailure.unavailable("WECHAT_NOTICE_REJECTED");
        return new Receipt(true); // Only provider errcode=0 acknowledges the protocol operation; never logged as a fallback.
    }
}
