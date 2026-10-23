package cn.lunaria.integration;

import com.fasterxml.jackson.databind.*;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** Real code2Session request/response adapter. No demo identity or session_key is returned or stored. */
public final class WechatCodeClient {
    public record VerifiedIdentity(String appId,String openId) {}
    private final WechatAppSettings settings;private final WechatTransport transport;private final ObjectMapper json;
    public WechatCodeClient(WechatAppSettings settings,WechatTransport transport,ObjectMapper json){this.settings=settings;this.transport=transport;this.json=json;}
    static String encode(String value){return URLEncoder.encode(value,StandardCharsets.UTF_8);}
    public VerifiedIdentity exchange(String code){
        settings.requireConfigured();if(code==null||code.isBlank()||code.length()>512)throw new ProviderFailure(400,"WECHAT_CODE_REQUIRED","微信临时凭证不正确");
        URI uri=URI.create("https://api.weixin.qq.com/sns/jscode2session?appid="+encode(settings.appId())+"&secret="+encode(settings.secret())+"&js_code="+encode(code)+"&grant_type=authorization_code");
        var reply=transport.request("GET",uri,null);var body=parse(json,reply);
        if(body.path("errcode").asInt(0)!=0)throw new ProviderFailure(401,"WECHAT_CODE_REJECTED","微信临时凭证未通过验证；不会使用演示身份");
        String openId=body.path("openid").asText("");if(openId.isBlank()||openId.length()>128)throw ProviderFailure.unavailable("WECHAT_IDENTITY_MALFORMED");
        return new VerifiedIdentity(settings.appId(),openId);
    }
    static JsonNode parse(ObjectMapper json,WechatTransport.Reply reply){
        if(reply==null||reply.status()<200||reply.status()>=300||reply.body()==null||reply.body().length()>65536)throw ProviderFailure.unavailable("WECHAT_HTTP_REJECTED");
        try {var body=json.readTree(reply.body());if(body==null||!body.isObject())throw ProviderFailure.unavailable("WECHAT_RESPONSE_MALFORMED");return body;}
        catch(ProviderFailure error){throw error;}catch(Exception error){throw ProviderFailure.unavailable("WECHAT_RESPONSE_MALFORMED");}
    }
}
