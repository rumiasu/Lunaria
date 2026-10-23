package cn.lunaria.integration;

import cn.lunaria.StoreMapper;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** A failure survives the caller's transaction rollback. Never stores tokens, openids, payloads or provider debug. */
@Service
public class WechatNotificationAudit implements WechatSubscriptionClient.FailureRecorder {
    private final StoreMapper mapper;
    public WechatNotificationAudit(StoreMapper mapper){this.mapper=mapper;}
    @Override @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void record(String customerId,String failureCode){
        String target=customerId!=null&&customerId.matches("[A-Za-z0-9:_-]{1,40}")?customerId:"unresolved";
        String code=failureCode!=null&&failureCode.matches("[A-Z0-9_]{1,80}")?failureCode:"WECHAT_NOTICE_FAILED";
        mapper.insertAudit(Map.of("id",UUID.randomUUID().toString(),"actor","wechat-notification","action","WECHAT_NOTICE_FAILED","target",target,"details","code="+code+"; automatic_retry=false"));
    }
}
