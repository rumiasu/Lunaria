package cn.lunaria.integration;

import cn.lunaria.StoreMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** A separate, operator-configured single owner. Customer records and request bodies cannot grant this role. */
@Service
public class WechatOwnerAuthentication {
    private final WechatAppSettings settings;private final String ownerOpenId;
    private final WechatCodeClient codes;private final StoreMapper store;
    public WechatOwnerAuthentication(WechatTransport transport,StoreMapper store,
        @Value("${lunaria.wechat.owner.enabled:false}") boolean enabled,
        @Value("${lunaria.wechat.owner.app-id:}") String appId,
        @Value("${lunaria.wechat.owner.app-secret:}") String secret,
        @Value("${lunaria.wechat.owner.open-id:}") String ownerOpenId) {
        settings=new WechatAppSettings(enabled,appId,secret);this.ownerOpenId=ownerOpenId;this.store=store;
        codes=new WechatCodeClient(settings,transport,new ObjectMapper());
    }
    public boolean configured(){return settings.configured()&&ownerOpenId!=null&&!ownerOpenId.isBlank()&&ownerOpenId.length()<=128;}
    @Transactional public void authenticate(String code) {
        if(!configured())throw new ProviderFailure(503,"INTEGRATION_NOT_CONFIGURED","店长微信身份尚未配置，当前保持关闭","wechat_owner_identity");
        WechatCodeClient.VerifiedIdentity verified;
        try{verified=codes.exchange(code);}
        catch(ProviderFailure error){throw new ProviderFailure(error.status,error.code,error.getMessage(),"wechat_owner_identity");}
        if(!settings.appId().equals(verified.appId())||!ownerOpenId.equals(verified.openId()))
            throw new ProviderFailure(403,"WECHAT_OWNER_NOT_AUTHORIZED","此微信身份未绑定为本店店长","wechat_owner_identity");
        store.insertAudit(Map.of("id",UUID.randomUUID().toString(),"actor","owner","action","WECHAT_OWNER_LOGIN","target","owner","details","provider verified; configured single owner; no customer asset merge"));
    }
}
