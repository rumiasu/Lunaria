package cn.lunaria.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.*;

@Configuration(proxyBeanMethods=false)
public class WechatClientConfiguration {
    @Bean WechatAppSettings wechatSettings(@Value("${lunaria.wechat.enabled:false}") boolean enabled,@Value("${lunaria.wechat.app-id:}") String appId,@Value("${lunaria.wechat.app-secret:}") String secret){return new WechatAppSettings(enabled,appId,secret);}
    @Bean @ConditionalOnMissingBean(WechatTransport.class) WechatTransport wechatTransport(){return new WechatTransport.Jdk();}
    @Bean WechatCodeClient wechatCodeClient(WechatAppSettings settings,WechatTransport transport){return new WechatCodeClient(settings,transport,new ObjectMapper());}
}
