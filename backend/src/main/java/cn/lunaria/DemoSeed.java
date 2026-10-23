package cn.lunaria;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component @Profile({"demo", "test"})
public class DemoSeed implements ApplicationRunner {
    private final JdbcTemplate db;
    public DemoSeed(JdbcTemplate db) { this.db = db; }
    @Override @Transactional public void run(ApplicationArguments args) {
        if (db.queryForObject("select count(*) from shop", Integer.class) != 0) return;
        db.update("insert into shop(id,opens,closes,break_start,break_end,capacity,paused,tier_threshold) values(1,'09:00','19:00','12:00','13:00',1,false,10000)");
        db.update("insert into customers values('alice','林女士（演示）','VIP',0,0,'FAVORABLE')");
        db.update("insert into customers values('bob','陈女士（演示）','NORMAL',0,0,'UNDECIDED')");
        db.update("insert into services values('facial','舒缓面部护理',60,2500,true),('shoulder','肩颈放松',30,3500,true)");
        db.update("insert into packages values('facial5','facial','月光 · 面护五次卡',5,10000,true,'demo-v1-favorable'),('shoulder3','shoulder','轻盈 · 肩颈三次卡',3,9000,true,'demo-v1-favorable')");
        db.update("insert into tier_prices values('facial5','VIP',9500)");
        db.update("insert into ledger values('seed-alice','alice','RECHARGE',30000,0,null,null,'seed-alice','owner','独立演示本金；非真实充值',CURRENT_TIMESTAMP),('seed-bob','bob','RECHARGE',20000,0,null,null,'seed-bob','owner','独立演示本金；非真实充值',CURRENT_TIMESTAMP)");
        db.update("update customers set balance_cents=case id when 'alice' then 30000 else 20000 end");
    }
}
