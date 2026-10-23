package cn.lunaria;

import cn.lunaria.integration.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Provider-verified identities may be attached only by the single owner. No asset merging or role promotion. */
@Service
public class WechatIdentityBindingService {
    private final StoreMapper store;
    private final WechatIdentityMapper identities;
    private final WechatAppSettings settings;
    public WechatIdentityBindingService(StoreMapper store,WechatIdentityMapper identities,WechatAppSettings settings){this.store=store;this.identities=identities;this.settings=settings;}
    public record Resolution(String bindingId,String customerId) {}
    @Transactional public Resolution resolve(WechatCodeClient.VerifiedIdentity verified){
        settings.requireConfigured();
        if(!settings.appId().equals(verified.appId())||verified.openId()==null||verified.openId().isBlank()||verified.openId().length()>128)throw new ProviderFailure(502,"WECHAT_IDENTITY_MALFORMED","微信身份响应无效");
        store.lockShop();
        var entry=identities.find(verified.appId(),verified.openId());
        if(entry==null){String id=UUID.randomUUID().toString();identities.insert(id,verified.appId(),verified.openId());audit("wechat","WECHAT_IDENTITY_PENDING",id,"provider verified; no customer attached");entry=identities.byId(id);}
        return resolution(entry);
    }
    @Transactional public Resolution bind(String actor,String id,String customer,String reason,String key){
        if(!"owner".equals(actor))throw new ProviderFailure(403,"OWNER_REQUIRED","需要店长权限");
        if(key==null||!key.matches("[A-Za-z0-9:_-]{8,100}")||reason==null||reason.isBlank()||reason.length()>300)throw new ProviderFailure(400,"BINDING_INPUT_INVALID","请填写绑定原因和有效防重键");
        store.lockShop();
        String hash=hash(id,customer,reason);var command=store.command(actor,key);
        if(command!=null){if(!"WECHAT_BIND".equals(command.get("scope"))||!hash.equals(command.get("fingerprint")))throw new ProviderFailure(409,"IDEMPOTENCY_CONFLICT","防重键已用于其他操作");return resolution(identities.byId((String)command.get("result_id")));}
        var entry=identities.byId(id);
        if(entry==null||customer==null||store.customer(customer)==null)throw new ProviderFailure(404,"BINDING_TARGET_MISSING","绑定申请或顾客不存在");
        String attached=(String)entry.get("customer_id");
        if(attached!=null&&!attached.equals(customer))throw new ProviderFailure(409,"IDENTITY_ALREADY_BOUND","身份已绑定，不能直接覆盖或合并资产");
        var other=identities.forCustomer((String)entry.get("app_id"),customer);
        if(other!=null&&!id.equals(other.get("id")))throw new ProviderFailure(409,"CUSTOMER_ALREADY_BOUND","顾客已有微信绑定，请人工核对");
        if(attached==null){if(identities.bind(id,customer)!=1)throw new ProviderFailure(409,"BINDING_CONFLICT","绑定状态已变化");audit(actor,"WECHAT_IDENTITY_BOUND",id,"customer="+customer+"; reason="+reason);}
        store.commandSave(actor,key,"WECHAT_BIND",hash,id);
        return resolution(identities.byId(id));
    }
    private Resolution resolution(Map<String,Object> row){return new Resolution((String)row.get("id"),(String)row.get("customer_id"));}
    private void audit(String actor,String action,String target,String details){store.insertAudit(Map.of("id",UUID.randomUUID().toString(),"actor",actor,"action",action,"target",target,"details",details));}
    private String hash(String... values){try{var digest=MessageDigest.getInstance("SHA-256");for(String v:values){String part=Objects.toString(v,"");digest.update((part.length()+":"+part).getBytes(StandardCharsets.UTF_8));}return HexFormat.of().formatHex(digest.digest());}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
}
