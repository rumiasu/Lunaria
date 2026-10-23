package cn.lunaria;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static cn.lunaria.ShopService.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:lunaria_tests;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"})
@ActiveProfiles("test") @AutoConfigureMockMvc
class BusinessFlowTest {
    @Autowired ShopService service; @Autowired JdbcTemplate sql; @Autowired DemoSeed seed; @Autowired MockMvc mvc;
    @MockitoSpyBean StoreMapper mapper;
    @BeforeEach void resetData() {
        reset(mapper);
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
    @Test void purchaseIsAtomicIdempotentAndSnapshotProtected() {
        String key=key();var card=service.purchase("alice","facial5",key);
        assertThat(n(card,"paid_cents")).isEqualTo(9500);assertThat(service.purchase("alice","facial5",key).get("id")).isEqualTo(card.get("id"));
        assertThat(n(mapper.customer("alice"),"balance_cents")).isEqualTo(20500);
        assertThat(mapper.cards("alice")).hasSize(1);assertThat(mapper.ledger("alice")).filteredOn(x->x.get("kind").equals("PURCHASE")).hasSize(1);
        assertThatThrownBy(()->service.purchase("alice","shoulder3",key)).isInstanceOf(BusinessException.class);
        service.savePackage("owner",row("id","facial5","serviceId","facial","name","新名字","total",8,"priceCents",20000L,"vipPriceCents",null,"active",true),key());
        assertThat(n(mapper.card(s(card,"id")),"total")).isEqualTo(5);assertThat(n(mapper.card(s(card,"id")),"paid_cents")).isEqualTo(9500);
        assertThat(n(service.purchase("alice","shoulder3",key()),"paid_cents")).isEqualTo(9000); // VIP only has explicitly configured facial price.
    }
    @Test void failureBetweenDeductAndIssueRollsBackBoth() {
        doThrow(new IllegalStateException("injected issue failure")).when(mapper).insertCard(anyMap());
        assertThatThrownBy(()->service.purchase("alice","facial5",key())).isInstanceOf(IllegalStateException.class);
        assertThat(n(mapper.customer("alice"),"balance_cents")).isEqualTo(30000);assertThat(mapper.cards("alice")).isEmpty();
        assertThat(sql.queryForObject("select count(*) from commands",Integer.class)).isZero();
    }
    @Test void concurrentPurchaseRetryOnlyIssuesOnce() throws Exception {
        String key=key();try(varExecutorClose ignored=new varExecutorClose()) {
            var pool=ignored.pool;var gate=new CountDownLatch(1);
            Callable<String> task=()->{gate.await();return s(service.purchase("alice","facial5",key),"id");};
            var a=pool.submit(task);var b=pool.submit(task);gate.countDown();assertThat(a.get(10,TimeUnit.SECONDS)).isEqualTo(b.get(10,TimeUnit.SECONDS));
        }
        assertThat(mapper.cards("alice")).hasSize(1);assertThat(n(mapper.customer("alice"),"balance_cents")).isEqualTo(20500);
    }
    static class varExecutorClose implements AutoCloseable { final ExecutorService pool=Executors.newFixedThreadPool(2);public void close(){pool.shutdownNow();} }
    @Test void confirmationRechecksCapacityAndCancellationNeverDeducts() {
        String card=buy();String a=s(service.book("alice","facial",next(9),"",key()),"id"),b=s(service.book("bob","facial",next(9),"",key()),"id");
        service.confirm("owner",a,key());assertThatThrownBy(()->service.confirm("owner",b,key())).hasMessageContaining("已满");
        service.cancel("alice","alice",a,"改期",key());service.confirm("owner",b,key());assertThat(n(mapper.card(card),"used")).isZero();
        assertThatThrownBy(()->service.book("alice","facial",next(12),"",key())).hasMessageContaining("休息");
    }
    @Test void capacityTwoCountsConcurrentIntervalsRatherThanAllIntersections() {
        sql.update("update shop set capacity=2");
        String a=s(service.book("alice","facial",next(9),"",key()),"id");String b=s(service.book("alice","facial",next(10),"",key()),"id");
        service.confirm("owner",a,key());service.confirm("owner",b,key());sql.update("update services set duration=120 where id='facial'");
        String c=s(service.book("bob","facial",next(9),"",key()),"id");assertThat(service.confirm("owner",c,key()).get("status")).isEqualTo("CONFIRMED");
    }
    @Test void settingsNeverEraseBookingsAndMovesAreAudited() {
        String a=s(service.book("alice","facial",next(9),"",key()),"id");service.confirm("owner",a,key());
        service.settings("owner",row("opens","10:00","closes","18:00","breakStart","12:00","breakEnd","13:00","capacity",1,"paused",true,"threshold",10000L),key());
        assertThat(mapper.appointment(a).get("status")).isEqualTo("CONFIRMED");assertThatThrownBy(()->service.book("bob","facial",next(14),"",key())).hasMessageContaining("暂停");
        sql.update("update shop set paused=false");service.move("owner",a,next(14),"顾客同意下午到店",key());
        assertThat(mapper.audit()).anyMatch(x->x.get("action").equals("MOVE")&&s(x,"details").contains("原="));
    }
    @Test void redemptionAndReversalAreOnceAndTrackActualFulfilledValue() {
        String card=buy();String entry=finish("alice",card,0);
        assertThat(n(mapper.card(card),"used")).isEqualTo(1);assertThat(n(mapper.customer("alice"),"fulfilled_cents")).isEqualTo(1900);
        String appointment=s(mapper.entry(entry),"appointment_id");assertThatThrownBy(()->service.complete("owner",appointment,card,key())).hasMessageContaining("尚未核销");
        String k=key();service.reverse("owner",entry,"录错服务",k);service.reverse("owner",entry,"录错服务",k);
        assertThat(n(mapper.card(card),"used")).isZero();assertThat(n(mapper.customer("alice"),"fulfilled_cents")).isZero();
        assertThatThrownBy(()->service.reverse("owner",entry,"再次冲正",key())).hasMessageContaining("冲正一次");
    }
    @Test void ninetyFiveFiveSessionsTwoUsedRefundsFiftySevenToOriginalBalance() {
        String card=buy();finish("alice",card,0);finish("alice",card,1);
        assertThat(n(service.quote("alice",card,"CONSUMER","PROPORTIONAL",""),"amount_cents")).isEqualTo(5700);
        String refund=s(service.requestRefund("alice",card,"CONSUMER","PROPORTIONAL","本人申请",key()),"id");
        assertThatThrownBy(()->finish("alice",card,2)).hasMessageContaining("正在退款");
        String key=key();service.reviewRefund("owner",refund,true,5700,"已核对更有利约定及利息，演示内部余额登记",key);service.reviewRefund("owner",refund,true,5700,"已核对更有利约定及利息，演示内部余额登记",key);
        assertThat(n(mapper.customer("alice"),"balance_cents")).isEqualTo(26200);assertThat(mapper.card(card).get("status")).isEqualTo("RETURNED");
        assertThat(mapper.ledger("alice")).filteredOn(l->l.get("kind").equals("REFUND_TO_BALANCE")).hasSize(1);
        assertThat(n(mapper.customer("alice"),"fulfilled_cents")).isEqualTo(3800);
    }
    @Test void disputedAndNonConsumerRefundsCannotBeForcedToOriginalPrice() {
        String card=buy();finish("alice",card,0);
        assertThatThrownBy(()->service.quote("alice",card,"SHOP","ORIGINAL_PRICE","proof")).hasMessageContaining("不得按原价");
        assertThatThrownBy(()->service.quote("alice",card,"CONSUMER","ORIGINAL_PRICE","proof")).hasMessageContaining("快照");
        String r=s(service.requestRefund("alice",card,"DISPUTED","PROPORTIONAL","原因存在争议",key()),"id");
        assertThatThrownBy(()->service.reviewRefund("owner",r,true,7600,"店长单方面认定",key())).hasMessageContaining("争议");
        assertThat(mapper.refund(r).get("status")).isEqualTo("PENDING");
    }
    @Test void originalPriceRequiresImmutableEvidenceAndNeverCreatesDebt() {
        String card=buy();finish("alice",card,0);finish("alice",card,1);
        sql.update("update cards set qualification_snapshot='NEW_WITH_EVIDENCE',rule_snapshot='ORIGINAL_PRICE_DISCLOSED:已提供售前约定及合理原价记录',list_cents=6000 where id=?",card);
        assertThat(n(service.quote("alice",card,"CONSUMER","ORIGINAL_PRICE","合理原价交易记录编号"),"amount_cents")).isZero();
    }
    @Test void centRemaindersAreConservedAndInsufficientFundsCannotIssueCard() {
        sql.update("update tier_prices set price_cents=9501");String card=buy();long total=0;
        for(int i=0;i<5;i++) total+=n(mapper.entry(finish("alice",card,i)),"amount_cents");
        assertThat(total).isEqualTo(9501);assertThat(n(service.quote("alice",card,"SHOP","PROPORTIONAL",""),"amount_cents")).isZero();
        sql.update("update customers set balance_cents=0 where id='bob'");assertThatThrownBy(()->service.purchase("bob","facial5",key())).hasMessageContaining("余额不足");assertThat(mapper.cards("bob")).isEmpty();
    }
    @Test void principalRechargeAndBalanceRefundAreSeparateAndIdempotent() {
        String key=key();service.recharge("owner","bob",10000,"demo-receipt",key);service.recharge("owner","bob",10000,"demo-receipt",key);
        String refund=s(service.balanceRefund("bob",5000,"本金退款申请",key()),"id");service.reviewRefund("owner",refund,true,5000,"预留线下处理，尚未付款",key());
        assertThat(n(mapper.customer("bob"),"balance_cents")).isEqualTo(25000);assertThat(mapper.refund(refund).get("status")).isEqualTo("RESERVED_NO_PAYOUT");assertThat(n(mapper.customer("bob"),"fulfilled_cents")).isZero();
    }
    @Test @WithMockUser(username="bob",roles="CUSTOMER") void customersCannotReadOtherAssetsOrOwnerApis() throws Exception {
        String card=buy();mvc.perform(get("/api/me/cards/"+card)).andExpect(status().isNotFound());
        mvc.perform(get("/api/owner/dashboard")).andExpect(status().isForbidden());
        mvc.perform(post("/api/me/purchases").contentType("application/json").content("{\"packageId\":\"facial5\"}").header("Idempotency-Key",key())).andExpect(status().isForbidden());
        mvc.perform(post("/api/me/purchases").with(csrf()).contentType("application/json").content("{\"packageId\":\"facial5\"}").header("Idempotency-Key",key())).andExpect(status().isOk());
    }
    @Test void anonymousIsRejectedAndProductionModeFailsClosed() throws Exception {
        mvc.perform(get("/api/me")).andExpect(status().isUnauthorized());
        var env=new org.springframework.mock.env.MockEnvironment();env.setActiveProfiles("prod","demo");env.setProperty("server.address","127.0.0.1");
        assertThatThrownBy(()->new DevelopmentOnly(env)).hasMessageContaining("禁止生产启动");
        env.setActiveProfiles("demo");env.setProperty("server.address","0.0.0.0");assertThatThrownBy(()->new DevelopmentOnly(env)).hasMessageContaining("127.0.0.1");
    }
    @Test void reversalOrderAndRepeatedReuseCannotManufactureACent() {
        sql.update("update tier_prices set price_cents=9501");String card=buy();var entries=new ArrayList<String>();
        for(int i=0;i<5;i++)entries.add(finish("alice",card,i));
        assertThat(mapper.cardFulfilled(card)).isEqualTo(9501);
        String original=entries.get(0);
        for(int i=0;i<3;i++) {
            long restored=n(mapper.entry(original),"amount_cents");service.reverse("owner",original,"更正早期核销",key());
            assertThat(n(service.quote("alice",card,"SHOP","PROPORTIONAL",""),"amount_cents")).isEqualTo(restored);
            original=finish("alice",card,6+i);
            assertThat(mapper.cardFulfilled(card)).isEqualTo(9501);assertThat(n(mapper.customer("alice"),"fulfilled_cents")).isEqualTo(9501);
        }
    }
    @Test void oldCardDurationIsProtectedWhenFutureServiceConfigurationIsShortened() {
        String card=buy();String oldName=s(mapper.card(card),"service_name");
        service.saveService("owner",row("id","facial","name","新的短时护理","duration",30,"listCents",5000L,"active",true),key());
        assertThat(n(mapper.card(card),"duration")).isEqualTo(60);assertThat(n(mapper.card(card),"list_cents")).isEqualTo(2500);
        assertThat(service.slots("alice","facial",next(9).toLocalDate(),null)).contains(next(11).withMinute(30).toString());
        assertThat(service.slots("alice","facial",next(9).toLocalDate(),card)).doesNotContain(next(11).withMinute(30).toString());
        String shortAppointment=s(service.book("alice","facial",next(9),"",key()),"id");service.confirm("owner",shortAppointment,key());
        sql.update("update appointments set starts_at=?,ends_at=? where id=?",now().minusHours(2),now().minusHours(1),shortAppointment);
        assertThatThrownBy(()->service.complete("owner",shortAppointment,card,key())).hasMessageContaining("时长不足");
        var full=service.book("alice","facial",next(10),"使用旧卡时长",card,key());assertThat(n(full,"duration")).isEqualTo(60);assertThat(full.get("service_name")).isEqualTo(oldName);
        service.confirm("owner",s(full,"id"),key());sql.update("update appointments set starts_at=?,ends_at=? where id=?",now().minusHours(2),now().minusHours(1),full.get("id"));
        service.complete("owner",s(full,"id"),card,key());assertThat(n(mapper.card(card),"used")).isEqualTo(1);
        assertThatThrownBy(()->service.slots("bob","facial",next(9).toLocalDate(),card)).isInstanceOf(BusinessException.class);
    }
    @Test void disputeNotesRemainSavableAndSettlementChecksStaySeparate() {
        String card=buy();finish("alice",card,0);finish("alice",card,1);
        String refund=s(service.requestRefund("alice",card,"DISPUTED","PROPORTIONAL","原因待双方确认",key()),"id");
        var noteOnly=row("note","已联系顾客，原因仍在核对，无需金额或确认字段也可保存");
        String command=key();var negotiation=service.negotiateRefund("owner",refund,noteOnly,command);assertThat(service.negotiateRefund("owner",refund,noteOnly,command).get("id")).isEqualTo(negotiation.get("id"));
        assertThat(mapper.negotiations(refund)).hasSize(1);assertThatThrownBy(()->service.reviewRefund("owner",refund,true,5700,"原因仍争议",key())).hasMessageContaining("争议");
        var agreed=row("reason","SHOP","algorithm","PROPORTIONAL","principal",5800L,"interest",100L,"note","店长人工核定本金更有利安排，利息单独登记，不强制顾客确认标志");service.negotiateRefund("owner",refund,agreed,key());
        assertThat(n(mapper.refund(refund),"amount_cents")).isEqualTo(5700); // Original trial remains immutable.
        assertThatThrownBy(()->service.reviewRefund("owner",refund,true,5700,"旧试算不可冒充新协商",key())).hasMessageContaining("不一致");
        String approve=key();service.reviewRefund("owner",refund,true,5800,"x".repeat(500),approve);service.reviewRefund("owner",refund,true,5800,"x".repeat(500),approve);
        assertThat(n(mapper.customer("alice"),"balance_cents")).isEqualTo(26400);assertThat(n(mapper.customer("alice"),"fulfilled_cents")).isEqualTo(3800);
        var summary=(Map<String,Object>)service.overview("alice").get("summary");assertThat(n(summary,"card_refund_cents")).isEqualTo(5800);assertThat(n(summary,"refund_interest_cents")).isEqualTo(100);assertThat(n(summary,"recharged_principal_cents")).isEqualTo(30000);
        assertThat(mapper.ledger("alice")).filteredOn(e->"REFUND_TO_BALANCE".equals(e.get("kind"))).hasSize(1);assertThat(mapper.negotiations(refund)).hasSize(2);
    }
    @Test void negotiationCannotReduceNonConsumerRightsOrErasePriorEvidence() {
        String card=buy();finish("alice",card,0);
        String refund=s(service.requestRefund("alice",card,"SHOP","PROPORTIONAL","店方原因",key()),"id");
        var reduced=row("reason","SHOP","algorithm","PROPORTIONAL","principal",7500L,"interest",0L,"evidence","说明","note","尝试少退","acknowledged",true);
        service.negotiateRefund("owner",refund,reduced,key());assertThatThrownBy(()->service.reviewRefund("owner",refund,true,7500,"该方案不能结算",key())).hasMessageContaining("低于适用规则保障");
        reduced.put("algorithm","ORIGINAL_PRICE");service.negotiateRefund("owner",refund,reduced,key());assertThatThrownBy(()->service.reviewRefund("owner",refund,true,7500,"保留错误算法争议记录",key())).hasMessageContaining("不得按原价");
        assertThat(mapper.negotiations(refund)).hasSize(2);assertThat(mapper.refund(refund).get("reason")).isEqualTo("SHOP");
    }
    @Test void failureDuringNegotiatedInterestCreditRollsBackPrincipalAndCardTogether() {
        String card=buy();String refund=s(service.requestRefund("alice",card,"SHOP","PROPORTIONAL","",key()),"id");
        service.negotiateRefund("owner",refund,row("reason","SHOP","algorithm","PROPORTIONAL","principal",9500L,"interest",100L,"evidence","书面约定","note","利息人工核定","acknowledged",true),key());
        doThrow(new IllegalStateException("injected interest failure")).when(mapper).insertEntry(org.mockito.ArgumentMatchers.argThat(entry->"REFUND_INTEREST".equals(entry.get("kind"))));
        assertThatThrownBy(()->service.reviewRefund("owner",refund,true,9500,"模拟中途失败",key())).isInstanceOf(IllegalStateException.class);
        assertThat(n(mapper.customer("alice"),"balance_cents")).isEqualTo(20500);assertThat(mapper.card(card).get("status")).isEqualTo("REVIEW");assertThat(mapper.refund(refund).get("status")).isEqualTo("PENDING");
        assertThat(mapper.ledger("alice")).noneMatch(e->s(e,"kind").startsWith("REFUND_"));
    }
    @Test void referenceConsumptionAndMembershipAreIndependentByDefault() {
        sql.update("update shop set tier_threshold=1");String card=s(service.purchase("bob","facial5",key()),"id");finish("bob",card,0);
        assertThat(n(mapper.customer("bob"),"fulfilled_cents")).isEqualTo(2000);assertThat(mapper.customer("bob").get("tier")).isEqualTo("NORMAL");
        var result=service.overview("bob");var summary=(Map<String,Object>)result.get("summary");var policy=(Map<String,Object>)result.get("membership_policy");
        assertThat(n(summary,"recharged_principal_cents")).isEqualTo(20000);assertThat(n(summary,"purchased_card_cents")).isEqualTo(10000);assertThat(n(summary,"fulfilled_reference_cents")).isEqualTo(2000);assertThat(policy.get("mode")).isEqualTo("TRACK_ONLY");
    }
    @Test void promotionNeedsExplicitDemoPolicySelection() {
        StoreMapper policyDb=mock(StoreMapper.class);when(policyDb.fulfillment("bob",123)).thenReturn(1);when(policyDb.customer("bob")).thenReturn(row("fulfilled_cents",123L));when(policyDb.shop()).thenReturn(row("tier_threshold",100L));
        new MembershipPolicy(policyDb,"TRACK_ONLY").record("bob",123);verify(policyDb,never()).promote("bob");
        new MembershipPolicy(policyDb,"DEMO_FULFILLED_VALUE").record("bob",123);verify(policyDb).promote("bob");assertThatThrownBy(()->new MembershipPolicy(policyDb,"UNDECIDED_RULE_ENGINE")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void concurrentConfirmationsCannotOverbookTheSingleBeautician() throws Exception {
        String first=s(service.book("alice","facial",next(9),"",key()),"id"),second=s(service.book("bob","facial",next(9),"",key()),"id");
        try(varExecutorClose executor=new varExecutorClose()) {
            var gate=new CountDownLatch(1);var results=new ArrayList<Future<Boolean>>();
            for(String appointment:List.of(first,second)) results.add(executor.pool.submit(()->{gate.await();try {service.confirm("owner",appointment,key());return true;}catch(BusinessException e){return false;}}));
            gate.countDown();int confirmed=0;for(var result:results)if(result.get(10,TimeUnit.SECONDS))confirmed++;assertThat(confirmed).isEqualTo(1);
        }
        assertThat(mapper.appointments(null,true)).filteredOn(a->"CONFIRMED".equals(a.get("status"))).hasSize(1);
    }
    @Test @WithMockUser(username="bob",roles="CUSTOMER") void refundAndAppointmentWritesCannotCrossCustomerBoundary() throws Exception {
        String card=buy();String refund=s(service.requestRefund("alice",card,"SHOP","PROPORTIONAL","",key()),"id");String appointment=s(service.book("alice","facial",next(9),"",key()),"id");
        mvc.perform(get("/api/me/refunds/"+refund)).andExpect(status().isNotFound());
        mvc.perform(post("/api/me/appointments/"+appointment+"/cancel").with(csrf()).header("Idempotency-Key",key()).contentType("application/json").content("{\"reason\":\"spoofed\"}")).andExpect(status().isNotFound());
        mvc.perform(post("/api/owner/refunds/"+refund+"/negotiations").with(csrf()).header("Idempotency-Key",key()).contentType("application/json").content("{}")).andExpect(status().isForbidden());
        mvc.perform(get("/api/slots").param("serviceId","facial").param("date",next(9).toLocalDate().toString()).param("cardId",card)).andExpect(status().isNotFound());
    }
    @Test @WithMockUser(username="owner",roles="OWNER") void invalidConfigurationIsBadRequestAndOwnerCannotImpersonateCustomer() throws Exception {
        mvc.perform(post("/api/owner/services").with(csrf()).header("Idempotency-Key",key()).contentType("application/json").content("{\"name\":\"test\",\"duration\":60,\"listCents\":2500,\"active\":true}")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/me")).andExpect(status().isForbidden());
        mvc.perform(post("/api/me/purchases").with(csrf()).header("Idempotency-Key",key()).contentType("application/json").content("{\"packageId\":\"facial5\"}")).andExpect(status().isForbidden());
    }
    @Test @WithMockUser(username="owner",roles="OWNER") void negotiatedRefundApiBindsExplicitDecisionAndRecordsSeparateInterest() throws Exception {
        String card=buy();finish("alice",card,0);finish("alice",card,1);String refund=s(service.requestRefund("alice",card,"DISPUTED","PROPORTIONAL","",key()),"id");
        mvc.perform(post("/api/owner/refunds/"+refund+"/negotiations").with(csrf()).header("Idempotency-Key",key()).contentType("application/json").content("{\"note\":\"dispute contact recorded without extra fields\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.interest_cents").value(0));
        mvc.perform(post("/api/owner/refunds/"+refund+"/negotiations").with(csrf()).header("Idempotency-Key",key()).contentType("application/json")
            .content("{\"resolvedReason\":\"SHOP\",\"algorithm\":\"PROPORTIONAL\",\"principalCents\":5800,\"interestCents\":100,\"agreementEvidence\":\"demo signed record\",\"note\":\"manual verification\",\"customerAcknowledged\":true}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.principal_cents").value(5800)).andExpect(jsonPath("$.customer_acknowledged").value(true));
        mvc.perform(post("/api/owner/refunds/"+refund+"/negotiations").with(csrf()).header("Idempotency-Key",key()).contentType("application/json").content("{\"note\":\"extra note preserves the previous proposal\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.principal_cents").value(5800)).andExpect(jsonPath("$.resolved_reason").value("SHOP")).andExpect(jsonPath("$.interest_cents").value(100));
        mvc.perform(post("/api/owner/refunds/"+refund+"/review").with(csrf()).header("Idempotency-Key",key()).contentType("application/json").content("{\"amountCents\":5800,\"note\":\"missing decision\"}"))
            .andExpect(status().isBadRequest());assertThat(mapper.refund(refund).get("status")).isEqualTo("PENDING");
        mvc.perform(post("/api/owner/refunds/"+refund+"/review").with(csrf()).header("Idempotency-Key",key()).contentType("application/json").content("{\"approve\":true,\"amountCents\":5800,\"note\":\"manual verified agreement\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REGISTERED"));
        mvc.perform(get("/api/owner/refunds/"+refund)).andExpect(status().isOk()).andExpect(jsonPath("$.negotiations[0].interest_cents").value(100));
    }
    @Test @WithMockUser(username="alice",roles="CUSTOMER") void bookingApiUsesOptionalOwnedCardDurationSnapshot() throws Exception {
        String card=buy();service.saveService("owner",row("id","facial","name","short future service","duration",30,"listCents",2500L,"active",true),key());
        mvc.perform(post("/api/me/appointments").with(csrf()).header("Idempotency-Key",key()).contentType("application/json")
            .content("{\"serviceId\":\"facial\",\"startsAt\":\""+next(9)+"\",\"note\":\"use purchased rights\",\"cardId\":\""+card+"\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.duration").value(60));
        mvc.perform(get("/api/slots").param("serviceId","facial").param("date",next(9).toLocalDate().toString()).param("cardId",card)).andExpect(status().isOk());
    }
}
