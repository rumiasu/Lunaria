package cn.lunaria;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static cn.lunaria.ShopService.*;

/** 已定规则的定向边界回归；与既有测试存在重叠，不代表穷尽全部业务。 */
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:lunaria_defined_rule_coverage;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"})
@ActiveProfiles("test") @AutoConfigureMockMvc
class DefinedRuleCoverageTest {
    @Autowired ShopService service; @Autowired JdbcTemplate sql; @Autowired DemoSeed seed; @Autowired MockMvc mvc;
    @BeforeEach void resetData() {
        for(String table:List.of("commands","audit","refund_negotiations","refunds","ledger","appointments","cards","tier_prices","packages","services","customers","shop")) sql.update("delete from "+table);
        seed.run(null);
    }
    private String key(){return UUID.randomUUID().toString();}
    private LocalDateTime next(int hour){return LocalDate.now(SHOP_ZONE).plusDays(2).atTime(hour,0);}
    private String buy(){return s(service.purchase("alice","facial5",key()),"id");}
    private String finish(String customer,String card,int day) {
        var start=LocalDate.now(SHOP_ZONE).plusDays(day+2).atTime(9,0);
        String appointment=s(service.book(customer,"facial",start,"",key()),"id");service.confirm("owner",appointment,key());
        sql.update("update appointments set starts_at=?,ends_at=? where id=?",now().minusHours(2),now().minusHours(1),appointment);
        return s(service.complete("owner",appointment,card,key()),"id");
    }
    private String dbStatus(String table,String id){return sql.queryForObject("select status from "+table+" where id=?",String.class,id);}

    @Test void bookingHonorsQuarterHourGranularityAndThirtyDayWindow() {
        assertThatThrownBy(()->service.book("alice","facial",next(9).withMinute(5),"",key())).hasMessageContaining("整刻");
        assertThatThrownBy(()->service.book("alice","facial",next(9).withSecond(30),"",key())).hasMessageContaining("整刻");
        assertThatThrownBy(()->service.book("alice","facial",LocalDate.now(SHOP_ZONE).plusDays(31).atTime(10,0),"",key())).hasMessageContaining("30 天");
        assertThatThrownBy(()->service.book("alice","facial",LocalDate.now(SHOP_ZONE).atStartOfDay(),"",key())).hasMessageContaining("30 天");
        assertThat(s(service.book("alice","facial",LocalDate.now(SHOP_ZONE).plusDays(30).atTime(10,0),"",key()),"status")).isEqualTo("PENDING");
    }
    @Test void confirmationRechecksPauseAndBreakAfterSettingsChange() {
        String appointment=s(service.book("alice","facial",next(9),"",key()),"id");
        service.settings("owner",row("opens","09:00","closes","19:00","breakStart","12:00","breakEnd","13:00","capacity",1,"paused",true,"threshold",10000L),key());
        assertThatThrownBy(()->service.confirm("owner",appointment,key())).hasMessageContaining("暂停");
        service.settings("owner",row("opens","09:00","closes","19:00","breakStart","09:00","breakEnd","10:00","capacity",1,"paused",false,"threshold",10000L),key());
        assertThatThrownBy(()->service.confirm("owner",appointment,key())).hasMessageContaining("休息");
        assertThat(dbStatus("appointments",appointment)).isEqualTo("PENDING");
        // Characterize the current confirmation window without changing the business rule.
        service.settings("owner",row("opens","09:00","closes","19:00","breakStart","12:00","breakEnd","13:00","capacity",1,"paused",false,"threshold",10000L),key());
        var beyond=LocalDate.now(SHOP_ZONE).plusDays(31).atTime(10,0);
        sql.update("update appointments set starts_at=?,ends_at=? where id=?",beyond,beyond.plusHours(1),appointment);
        assertThatThrownBy(()->service.confirm("owner",appointment,key())).hasMessageContaining("30 天");
        assertThat(dbStatus("appointments",appointment)).isEqualTo("PENDING");
        var past=LocalDate.now(SHOP_ZONE).minusDays(1).atTime(10,0);
        sql.update("update appointments set starts_at=?,ends_at=? where id=?",past,past.plusHours(1),appointment);
        assertThatThrownBy(()->service.confirm("owner",appointment,key())).hasMessageContaining("30 天");
        assertThat(dbStatus("appointments",appointment)).isEqualTo("PENDING");
        var boundary=LocalDate.now(SHOP_ZONE).plusDays(30).atTime(10,0);
        sql.update("update appointments set starts_at=?,ends_at=? where id=?",boundary,boundary.plusHours(1),appointment);
        assertThat(s(service.confirm("owner",appointment,key()),"status")).isEqualTo("CONFIRMED");
    }
    @Test void commandKeysBindPayloadAndActorWithoutConsumingOnFailure() {
        String shared=key();
        s(service.purchase("alice","facial5",shared),"id");
        assertThatThrownBy(()->service.purchase("alice","shoulder3",shared)).hasMessageContaining("已用于其他请求");
        assertThat(s(service.purchase("bob","facial5",shared),"customer_id")).isEqualTo("bob");
        String retry=key();
        assertThatThrownBy(()->service.book("alice","facial",next(12),"",retry)).hasMessageContaining("休息");
        assertThat(s(service.book("alice","facial",next(9),"",retry),"status")).isEqualTo("PENDING");
        assertThat(sql.queryForObject("select count(*) from appointments",Integer.class)).isEqualTo(1);
    }
    @Test void completionRequiresConfirmedAppointmentWhoseServiceHasEnded() {
        String card=buy();
        String pending=s(service.book("alice","facial",next(9),"",key()),"id");
        assertThatThrownBy(()->service.complete("owner",pending,card,key())).hasMessageContaining("已确认");
        String future=s(service.book("alice","facial",next(10),"",key()),"id");service.confirm("owner",future,key());
        assertThatThrownBy(()->service.complete("owner",future,card,key())).hasMessageContaining("服务结束");
        assertThat(sql.queryForObject("select used from cards where id=?",Integer.class,card)).isZero();
    }
    @Test void overlappingOwnBookingsAreRejectedWhenBookingAndMoving() {
        s(service.book("alice","facial",next(9),"",key()),"id");
        assertThatThrownBy(()->service.book("alice","facial",next(9).plusMinutes(30),"",key())).hasMessageContaining("重叠预约");
        String second=s(service.book("alice","facial",next(10),"",key()),"id");
        assertThatThrownBy(()->service.move("owner",second,next(9).plusMinutes(30),"尝试改到重叠时段",key())).hasMessageContaining("重叠预约");
        assertThat(dbStatus("appointments",second)).isEqualTo("PENDING");
    }
    @Test void completedAppointmentsCannotBeCanceled() {
        String card=buy();String entry=finish("alice",card,0);
        String appointment=s(sql.queryForMap("select appointment_id from ledger where id=?",entry),"appointment_id");
        assertThatThrownBy(()->service.cancel("owner",null,appointment,"事后取消",key())).hasMessageContaining("不能取消");
    }
    @Test void reversalIsBlockedDuringRefundReviewAndMarksAppointmentCorrected() {
        String card=buy();String entry=finish("alice",card,0);
        String appointment=s(sql.queryForMap("select appointment_id from ledger where id=?",entry),"appointment_id");
        String refund=s(service.requestRefund("alice",card,"SHOP","PROPORTIONAL","店方原因",key()),"id");
        assertThatThrownBy(()->service.reverse("owner",entry,"退款处理中冲正",key())).hasMessageContaining("退卡处理中");
        service.reviewRefund("owner",refund,false,0,"核实后拒绝，卡恢复使用",key());
        service.reverse("owner",entry,"录错服务",key());
        assertThat(dbStatus("appointments",appointment)).isEqualTo("CORRECTED");
        assertThat(dbStatus("cards",card)).isEqualTo("ACTIVE");
        assertThat(sql.queryForObject("select used from cards where id=?",Integer.class,card)).isZero();
    }
    @Test void rejectedRefundRestoresCardAndCannotBeReenteredOrRenegotiated() {
        String card=buy();finish("alice",card,0);
        String refund=s(service.requestRefund("alice",card,"CONSUMER","PROPORTIONAL","本人原因",key()),"id");
        service.reviewRefund("owner",refund,false,0,"证据不足拒绝",key());
        assertThat(dbStatus("refunds",refund)).isEqualTo("REJECTED");
        finish("alice",card,1);
        assertThat(sql.queryForObject("select used from cards where id=?",Integer.class,card)).isEqualTo(2);
        assertThatThrownBy(()->service.negotiateRefund("owner",refund,row("note","事后补记"),key())).hasMessageContaining("申请已处理");
        assertThatThrownBy(()->service.reviewRefund("owner",refund,true,7600,"换个决定",key())).hasMessageContaining("申请已处理");
        assertThat(sql.queryForList("select kind from ledger where customer_id='alice' and kind like 'REFUND%'",String.class)).isEmpty();
        assertThat(sql.queryForObject("select balance_cents from customers where id='alice'",Long.class)).isEqualTo(20500);
    }
    @Test @WithMockUser(username="owner",roles="OWNER") void capacityAndServiceDurationBoundsAreEnforced() throws Exception {
        var base=row("opens","09:00","closes","19:00","breakStart","12:00","breakEnd","13:00","capacity",1,"paused",false,"threshold",10000L);
        base.put("capacity",0);assertThatThrownBy(()->service.settings("owner",base,key())).hasMessageContaining("容量");
        base.put("capacity",6);assertThatThrownBy(()->service.settings("owner",base,key())).hasMessageContaining("容量");
        mvc.perform(post("/api/owner/services").with(csrf()).header("Idempotency-Key",key()).contentType("application/json")
            .content("{\"id\":\"testsvc\",\"name\":\"超短项目\",\"duration\":14,\"listCents\":2500,\"active\":true}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/owner/services").with(csrf()).header("Idempotency-Key",key()).contentType("application/json")
            .content("{\"id\":\"testsvc\",\"name\":\"超长项目\",\"duration\":241,\"listCents\":2500,\"active\":true}")).andExpect(status().isBadRequest());
        assertThat(sql.queryForObject("select count(*) from services where id='testsvc'",Integer.class)).isZero();
    }
    @Test void vipPriceCannotExceedDefaultAndPausedOffersCannotBeBoughtOrBooked() {
        assertThatThrownBy(()->service.savePackage("owner",row("id","facial5","serviceId","facial","name","越界专属价","total",5,"priceCents",10000L,"vipPriceCents",11000L,"active",true),key())).hasMessageContaining("专属价格");
        service.savePackage("owner",row("id","facial5","serviceId","facial","name","暂停套餐","total",5,"priceCents",10000L,"vipPriceCents",null,"active",false),key());
        assertThatThrownBy(()->service.purchase("alice","facial5",key())).hasMessageContaining("已暂停销售");
        service.saveService("owner",row("id","facial","name","暂停项目","duration",60,"listCents",2500L,"active",false),key());
        assertThatThrownBy(()->service.book("alice","facial",next(9),"",key())).hasMessageContaining("项目暂停预约");
        assertThatThrownBy(()->service.slots("alice","facial",next(9).toLocalDate(),null)).hasMessageContaining("项目暂停预约");
    }
    @Test void balanceRefundsAreCappedByPrincipalAndKeepASinglePendingRequest() {
        assertThatThrownBy(()->service.recharge("owner","bob",0,"零额本金",key())).hasMessageContaining("本金金额");
        assertThatThrownBy(()->service.balanceRefund("bob",25000,"超过本金",key())).hasMessageContaining("超过独立储值本金余额");
        String refund=s(service.balanceRefund("bob",5000,"首次申请",key()),"id");
        assertThatThrownBy(()->service.negotiateRefund("owner",refund,row("note","储值退款不走单卡协商"),key())).hasMessageContaining("单卡协商");
        assertThatThrownBy(()->service.balanceRefund("bob",1000,"重复申请",key())).hasMessageContaining("已有储值退款申请");
        service.reviewRefund("owner",refund,true,5000,"预留线下处理，尚未付款",key());
        assertThat(dbStatus("refunds",refund)).isEqualTo("RESERVED_NO_PAYOUT");
        assertThat(sql.queryForObject("select balance_cents from customers where id='bob'",Long.class)).isEqualTo(15000);
        var summary=(Map<String,Object>)service.overview("bob").get("summary");
        assertThat(n(summary,"reserved_balance_refund_cents")).isEqualTo(5000);
        assertThat(n(summary,"recharged_principal_cents")).isEqualTo(20000);
    }
}
