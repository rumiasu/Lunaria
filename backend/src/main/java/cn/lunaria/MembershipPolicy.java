package cn.lunaria;

import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import static cn.lunaria.ShopService.*;
import static cn.lunaria.BusinessException.require;

/** One explicit boundary, not a membership rule engine. The production recognition policy is still undecided. */
@Component
public class MembershipPolicy {
    public enum Mode { TRACK_ONLY, DEMO_FULFILLED_VALUE }
    private final StoreMapper db;
    private final Mode mode;
    public MembershipPolicy(StoreMapper db,@Value("${lunaria.membership.mode:TRACK_ONLY}") String mode) {
        this.db=db;this.mode=Mode.valueOf(mode);
    }
    public void record(String customer,long delta) {
        require(db.fulfillment(customer,delta)==1,"履约参考金额更新失败");
        if(mode==Mode.DEMO_FULFILLED_VALUE&&delta>0&&n(db.customer(customer),"fulfilled_cents")>=n(db.shop(),"tier_threshold")) db.promote(customer);
    }
    public String version() { return "membership-v1:"+mode; }
    public Map<String,Object> description() {
        return row("mode",mode.name(),"version",version(),"upgrades_enabled",mode==Mode.DEMO_FULFILLED_VALUE,
            "notice","累计认定和资格时点待定；fulfilled_cents 是已履约实付价值参考，充值与购卡不重复计入，当前不构成正式会员承诺");
    }
}
