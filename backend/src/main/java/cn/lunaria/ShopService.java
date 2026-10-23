package cn.lunaria;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static cn.lunaria.BusinessException.require;

@Service
public class ShopService {
    private final StoreMapper db;
    private final MembershipPolicy membership;
    public static final ZoneId SHOP_ZONE = ZoneId.of("Asia/Shanghai");
    public ShopService(StoreMapper db,MembershipPolicy membership) { this.db=db;this.membership=membership; }
    static String id() { return UUID.randomUUID().toString(); }
    static long n(Map<String,Object> row,String key) { return ((Number)row.get(key)).longValue(); }
    static String s(Map<String,Object> row,String key) { return String.valueOf(row.get(key)); }
    static boolean yes(Map<String,Object> row,String key) { Object value=row.get(key);return Boolean.TRUE.equals(value)||(value instanceof Number number&&number.longValue()==1); }
    static LocalDateTime time(Object value) { return value instanceof java.sql.Timestamp t ? t.toLocalDateTime() : value instanceof LocalDateTime t ? t : LocalDateTime.parse(value.toString()); }
    static LocalDateTime now() { return LocalDateTime.now(SHOP_ZONE); }
    static Map<String,Object> row(Object... fields) { var m = new LinkedHashMap<String,Object>(); for(int i=0;i<fields.length;i+=2) m.put(fields[i].toString(),fields[i+1]); return m; }
    static Map<String,Object> exists(Map<String,Object> result) { if(result==null) throw new BusinessException(404,"记录不存在"); return result; }
    private Map<String,Object> ownedCard(String customer,String card) { var c=exists(db.card(card)); if(!customer.equals(c.get("customer_id"))) throw new BusinessException(404,"卡不存在"); return c; }
    private Map<String,Object> ownedAppointment(String customer,String appointment) { var a=exists(db.appointment(appointment)); if(customer!=null&&!customer.equals(a.get("customer_id"))) throw new BusinessException(404,"预约不存在"); return a; }
    private void audit(String actor,String action,String target,String details) { db.insertAudit(row("id",id(),"actor",actor,"action",action,"target",target,"details",details)); }
    private String command(String actor,String key,String scope,Object payload,Supplier<String> work) {
        require(key!=null&&key.matches("[A-Za-z0-9:_-]{8,100}"),"需要 8–100 位唯一操作编号");
        String hash;
        try { hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((scope+":"+payload).getBytes(StandardCharsets.UTF_8))); }
        catch(Exception e) { throw new IllegalStateException(e); }
        db.lockShop(); // One shop, deliberately serialize writes; shared DB lock also protects multiple app instances.
        var previous=db.command(actor,key);
        if(previous!=null) { require(scope.equals(previous.get("scope"))&&hash.equals(previous.get("fingerprint")),"操作编号已用于其他请求，请核对结果"); return s(previous,"result_id"); }
        String result=work.get(); db.commandSave(actor,key,scope,hash,result); return result;
    }
    private String entry(String customer,String kind,long amount,int units,String card,String appointment,String source,String actor,String note) {
        String entryId=id(); db.insertEntry(row("id",entryId,"customer",customer,"kind",kind,"amount",amount,"units",units,"card",card,"appointment",appointment,"source",source,"actor",actor,"note",note)); return entryId;
    }
    public Map<String,Object> catalog(String customer) {
        var c=exists(db.customer(customer));
        return row("services",db.services().stream().filter(x->yes(x,"active")).toList(),"packages",db.packages(s(c,"tier")).stream().filter(x->yes(x,"active")).toList(),"shop",db.shop(),"membership_policy",membership.description());
    }
    public Map<String,Object> overview(String customer) {
        var account=exists(db.customer(customer));var ledger=db.ledger(customer);
        return row("customer",account,"cards",db.cards(customer),"appointments",db.appointments(customer,false),"ledger",ledger,
            "refunds",db.refunds(customer).stream().map(r->refundView(s(r,"id"))).toList(),"membership_policy",membership.description(),
            "summary",row("recharged_principal_cents",sum(ledger,"RECHARGE"),"purchased_card_cents",-sum(ledger,"PURCHASE"),
                "fulfilled_reference_cents",sum(ledger,"SERVICE")+sum(ledger,"REVERSAL"),"card_refund_cents",sum(ledger,"REFUND_TO_BALANCE"),
                "refund_interest_cents",sum(ledger,"REFUND_INTEREST"),"reserved_balance_refund_cents",-sum(ledger,"BALANCE_REFUND_RESERVED")));
    }
    private long sum(List<Map<String,Object>> ledger,String kind) {return ledger.stream().filter(l->kind.equals(l.get("kind"))).mapToLong(l->n(l,"amount_cents")).sum();}
    private Map<String,Object> refundView(String refundId) {var refund=new LinkedHashMap<>(exists(db.refund(refundId)));refund.put("negotiations",db.negotiations(refundId));return refund;}
    public Map<String,Object> refundDetail(String customer,String refundId) {
        var refund=exists(db.refund(refundId));if(customer!=null&&!customer.equals(refund.get("customer_id")))throw new BusinessException(404,"退款申请不存在");return refundView(refundId);
    }
    public Map<String,Object> card(String customer,String card) { return ownedCard(customer,card); }
    public Map<String,Object> dashboard() {
        var customers=db.customers();
        var analysis=customers.stream().map(c->{
            var ledger=db.ledger(s(c,"id")); var cards=db.cards(s(c,"id"));
            long visits=ledger.stream().filter(l->"SERVICE".equals(l.get("kind"))&&db.reversed(s(l,"id"))==0).count();
            var frequency=new HashMap<String,Long>();
            ledger.stream().filter(l->"SERVICE".equals(l.get("kind"))&&db.reversed(s(l,"id"))==0).forEach(l->{ var card=db.card(s(l,"card_id"));frequency.merge(s(card,"service_name"),1L,Long::sum); });
            String usual=frequency.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("暂无到店");
            long remaining=cards.stream().filter(a->"ACTIVE".equals(a.get("status"))).mapToLong(a->n(a,"total")-n(a,"used")).sum();
            return row("customer",c,"visits",visits,"usual",usual,"remaining",remaining,"contact_hint",remaining<=1?"可人工联系确认需求":"按需联系");
        }).toList();
        return row("appointments",db.appointments(null,true),"customers",analysis,"refunds",db.refunds(null).stream().map(r->refundView(s(r,"id"))).toList(),"services",db.services(),"packages",db.packages("VIP"),"shop",db.shop(),"audit",db.audit(),"membership_policy",membership.description());
    }
    @Transactional public Map<String,Object> purchase(String customer,String packageId,String key) {
        String cardId=command(customer,key,"PURCHASE",packageId,()->{
            var c=exists(db.customer(customer)); var p=exists(db.offer(packageId,s(c,"tier"))); var service=exists(db.service(s(p,"service_id")));
            require(yes(p,"active")&&yes(service,"active"),"套餐或项目已暂停销售");
            long paid=p.get("tier_price_cents")==null?n(p,"price_cents"):n(p,"tier_price_cents");
            require(db.balance(customer,-paid)==1,"余额不足，请联系店长登记本金充值");
            String newId=id();
            db.insertCard(row("id",newId,"customer_id",customer,"package_id",packageId,"service_id",p.get("service_id"),"name",p.get("name"),"service_name",p.get("service_name"),"duration",p.get("duration"),"total",p.get("total"),"paid_cents",paid,"list_cents",p.get("list_cents"),"tier_snapshot",c.get("tier"),"qualification_snapshot",c.get("qualification"),"rule_snapshot",p.get("rule_version")+";price="+(p.get("tier_price_cents")==null?"DEFAULT":"EXPLICIT_TIER")+";default_price_cents="+p.get("price_cents")+";"+membership.version()+"；默认实付比例退，资格时点待定，无原价追扣授权"));
            entry(customer,"PURCHASE",-paid,0,newId,null,newId,customer,"余额购卡并发卡；非新增充值或履约收入");
            audit(customer,"PURCHASE",newId,"套餐="+packageId+", 实付分="+paid); return newId;
        }); return ownedCard(customer,cardId);
    }
    @Transactional public Map<String,Object> recharge(String actor,String customer,long amount,String reference,String key) {
        require(amount>0&&amount<=100_000_000,"本金金额超出范围"); require(reference!=null&&!reference.isBlank(),"请填写演示收款来源");
        String ledgerId=command(actor,key,"RECHARGE",List.of(customer,amount,reference),()->{
            exists(db.customer(customer));require(db.balance(customer,amount)==1,"余额更新失败");
            String result=entry(customer,"RECHARGE",amount,0,null,null,id(),actor,"演示本金登记："+reference);audit(actor,"RECHARGE",result,reference);return result;
        });return db.entry(ledgerId);
    }
    private void validWindow(LocalDateTime start,LocalDateTime end,Map<String,Object> shop,boolean future) {
        require(!yes(shop,"paused"),"店铺暂停接单");
        require(start.toLocalDate().equals(end.toLocalDate()),"服务不可跨天");
        require(start.getSecond()==0&&start.getNano()==0&&start.getMinute()%15==0,"请选择整刻时段");
        if(future) require(start.isAfter(now())&&!start.toLocalDate().isAfter(now().toLocalDate().plusDays(30)),"可预约未来 30 天内时段");
        LocalTime open=LocalTime.parse(s(shop,"opens")),close=LocalTime.parse(s(shop,"closes")),bs=LocalTime.parse(s(shop,"break_start")),be=LocalTime.parse(s(shop,"break_end"));
        require(!start.toLocalTime().isBefore(open)&&!end.toLocalTime().isAfter(close),"超出营业时间");
        require(!start.toLocalTime().isBefore(be)||!end.toLocalTime().isAfter(bs),"该时段是休息时间");
    }
    /** Sweep concurrent intervals; counting all intersecting bookings incorrectly rejects adjacent bookings at capacity > 1. */
    private void capacity(LocalDateTime start,LocalDateTime end,String exclude,Map<String,Object> shop) {
        var events=new TreeMap<LocalDateTime,Integer>();
        for(var a:db.overlaps(start,end,exclude)) {
            events.merge(time(a.get("starts_at")).isBefore(start)?start:time(a.get("starts_at")),1,Integer::sum);
            events.merge(time(a.get("ends_at")).isAfter(end)?end:time(a.get("ends_at")),-1,Integer::sum);
        }
        int concurrent=0; for(int change:events.values()) { concurrent+=change;require(concurrent<n(shop,"capacity"),"该时段已满，请联系顾客调整时间"); }
    }
    public List<String> slots(String serviceId,LocalDate date) {return slots(null,serviceId,date,null);}
    public List<String> slots(String customer,String serviceId,LocalDate date,String cardId) {
        var svc=bookingService(customer,serviceId,cardId);var shop=db.shop();var result=new ArrayList<String>();
        for(int minutes=0;minutes<24*60;minutes+=15) {var start=date.atStartOfDay().plusMinutes(minutes);var end=start.plusMinutes(n(svc,"duration"));try { validWindow(start,end,shop,true);capacity(start,end,"",shop);result.add(start.toString()); } catch(BusinessException ignored) {} }
        return result;
    }
    private Map<String,Object> bookingService(String customer,String serviceId,String cardId) {
        var svc=exists(db.service(serviceId));require(yes(svc,"active"),"项目暂停预约");
        if(cardId==null)return svc;
        var card=ownedCard(customer,cardId);require(serviceId.equals(card.get("service_id")),"预约与套餐卡项目不一致");
        require("ACTIVE".equals(card.get("status"))&&n(card,"used")<n(card,"total"),"套餐卡不可用于新预约");
        return row("name",card.get("service_name"),"duration",card.get("duration"));
    }
    private void ownConflict(String customer,LocalDateTime start,LocalDateTime end,String exclude) {
        require(db.appointments(customer,false).stream().noneMatch(a->!exclude.equals(a.get("id"))&&Set.of("PENDING","CONFIRMED").contains(s(a,"status"))&&time(a.get("starts_at")).isBefore(end)&&time(a.get("ends_at")).isAfter(start)),"您已有重叠预约，请先取消或调整原预约");
    }
    @Transactional public Map<String,Object> book(String customer,String serviceId,LocalDateTime start,String note,String key) {
        return book(customer,serviceId,start,note,null,key);
    }
    @Transactional public Map<String,Object> book(String customer,String serviceId,LocalDateTime start,String note,String cardId,String key) {
        String appointment=command(customer,key,"BOOK",cardId==null?List.of(serviceId,start,note):List.of(serviceId,start,note,cardId),()->{
            var svc=bookingService(customer,serviceId,cardId);var end=start.plusMinutes(n(svc,"duration"));var shop=db.shop();
            validWindow(start,end,shop,true);capacity(start,end,"",shop);ownConflict(customer,start,end,"");
            String newId=id();db.insertAppointment(row("id",newId,"customer",customer,"service",serviceId,"name",svc.get("name"),"duration",svc.get("duration"),"start",start,"end",end,"note",note));
            audit(customer,"BOOK",newId,start+" 待店长确认；权益卡="+cardId);return newId;
        });return ownedAppointment(customer,appointment);
    }
    @Transactional public Map<String,Object> confirm(String actor,String appointment,String key) {
        String result=command(actor,key,"CONFIRM",appointment,()->{
            var a=exists(db.appointment(appointment));require("PENDING".equals(a.get("status")),"只有待确认预约可以确认");var shop=db.shop();
            validWindow(time(a.get("starts_at")),time(a.get("ends_at")),shop,true);capacity(time(a.get("starts_at")),time(a.get("ends_at")),appointment,shop);
            db.appointmentStatus(appointment,"CONFIRMED");audit(actor,"CONFIRM",appointment,"确认时重新校验容量");return appointment;
        });return db.appointment(result);
    }
    @Transactional public Map<String,Object> cancel(String actor,String customer,String appointment,String reason,String key) {
        require(reason!=null&&!reason.isBlank(),"请填写取消原因");
        String result=command(actor,key,"CANCEL",List.of(appointment,reason),()->{
            var a=ownedAppointment(customer,appointment);require(Set.of("PENDING","CONFIRMED").contains(s(a,"status")),"当前预约不能取消");
            db.appointmentStatus(appointment,"CANCELED");audit(actor,"CANCEL",appointment,"不扣次；"+reason);return appointment;
        });return db.appointment(result);
    }
    @Transactional public Map<String,Object> move(String actor,String appointment,LocalDateTime start,String reason,String key) {
        require(reason!=null&&!reason.isBlank(),"调整必须保留原因");
        String result=command(actor,key,"MOVE",List.of(appointment,start,reason),()->{
            var a=exists(db.appointment(appointment));require(Set.of("PENDING","CONFIRMED").contains(s(a,"status")),"当前预约不能调整");var end=start.plusMinutes(n(a,"duration"));var shop=db.shop();
            validWindow(start,end,shop,true);capacity(start,end,appointment,shop);ownConflict(s(a,"customer_id"),start,end,appointment);
            db.moveAppointment(appointment,start,end);audit(actor,"MOVE",appointment,"原="+a.get("starts_at")+"，新="+start+"；"+reason);return appointment;
        });return db.appointment(result);
    }
    @Transactional public Map<String,Object> complete(String actor,String appointment,String cardId,String key) {
        String ledgerId=command(actor,key,"COMPLETE",List.of(appointment,cardId),()->{
            var a=exists(db.appointment(appointment));var c=ownedCard(s(a,"customer_id"),cardId);
            require("CONFIRMED".equals(a.get("status")),"预约必须已确认且尚未核销");
            require(!time(a.get("ends_at")).isAfter(now()),"服务结束后才能核销");
            require(a.get("service_id").equals(c.get("service_id")),"套餐项目与服务不一致");
            require(n(a,"duration")>=n(c,"duration"),"本次服务时长不足已售卡权益；请按卡快照重新安排，不可缩短权益");
            long remaining=n(c,"total")-n(c,"used"),unfulfilled=n(c,"paid_cents")-db.cardFulfilled(cardId);
            require(remaining>0&&unfulfilled>=0,"余次或履约分摊记录不一致，请人工核对");
            long value=unfulfilled/remaining; // Reallocate only unused cents; reversal order cannot create or destroy money.
            require(db.useCard(cardId,1)==1,"卡余次不足或正在退款");db.appointmentStatus(appointment,"DONE");
            membership.record(s(a,"customer_id"),value);
            String result=entry(s(a,"customer_id"),"SERVICE",value,-1,cardId,appointment,appointment,actor,membership.version()+"；履约参考值，正式累计认定待定");audit(actor,"COMPLETE",result,"已完成服务扣一次");return result;
        });return db.entry(ledgerId);
    }
    @Transactional public Map<String,Object> reverse(String actor,String entryId,String reason,String key) {
        require(reason!=null&&!reason.isBlank(),"纠错必须留原因");
        String result=command(actor,key,"REVERSE",List.of(entryId,reason),()->{
            var old=exists(db.entry(entryId));require("SERVICE".equals(old.get("kind"))&&db.reversed(entryId)==0,"仅允许冲正一次尚未冲正的核销");
            String cardId=s(old,"card_id");require(db.useCard(cardId,-1)==1,"退卡处理中或已退卡不可直接冲正，请先处理争议");
            membership.record(s(old,"customer_id"),-n(old,"amount_cents"));
            db.appointmentStatus(s(old,"appointment_id"),"CORRECTED");
            String correction=entry(s(old,"customer_id"),"REVERSAL",-n(old,"amount_cents"),1,cardId,s(old,"appointment_id"),entryId,actor,reason);audit(actor,"REVERSE",entryId,reason);return correction;
        });return db.entry(result);
    }
    public Map<String,Object> quote(String customer,String cardId,String reason,String algorithm,String evidence) {
        var c=ownedCard(customer,cardId);require(Set.of("ACTIVE","REVIEW").contains(s(c,"status")),"该卡已退");
        require(Set.of("CONSUMER","SHOP","OTHER_NON_CONSUMER","DISPUTED").contains(reason),"退款原因无效");
        long proportional=n(c,"paid_cents")-db.cardFulfilled(cardId);
        require(proportional>=0&&proportional<=n(c,"paid_cents"),"卡履约分摊记录不一致，请人工核对");
        boolean disputed=reason.equals("DISPUTED");
        require(Set.of("PROPORTIONAL","ORIGINAL_PRICE").contains(algorithm),"退款算法无效");
        long amount=proportional;
        if(algorithm.equals("ORIGINAL_PRICE")) {
            require(reason.equals("CONSUMER"),"非消费者原因与争议不得按原价追扣");
            require("NEW_WITH_EVIDENCE".equals(c.get("qualification_snapshot"))&&s(c,"rule_snapshot").contains("ORIGINAL_PRICE_DISCLOSED"),"售卡权益快照没有原价规则证据；本版默认更有利的实付比例退款");
            require(evidence!=null&&!evidence.isBlank(),"请提供合理原价交易记录及事先规则证据");
            amount=Math.max(0,n(c,"paid_cents")-n(c,"list_cents")*n(c,"used"));
        }
        return row("amount_cents",amount,"proportional_cents",proportional,"algorithm",algorithm,"requires_manual",true,"disputed",disputed,"used",c.get("used"),"rule_snapshot",c.get("rule_snapshot"),"notice","本金试算；利息、更有利约定及其他法定责任需人工核对，不自动付款");
    }
    @Transactional public Map<String,Object> requestRefund(String customer,String cardId,String reason,String algorithm,String evidence,String key) {
        String result=command(customer,key,"REFUND_REQUEST",List.of(cardId,reason,algorithm,evidence),()->{
            var c=ownedCard(customer,cardId);require("ACTIVE".equals(c.get("status")),"已有退款处理或卡已退");var quote=quote(customer,cardId,reason,algorithm,evidence);
            String refund=id();db.insertRefund(row("id",refund,"customer",customer,"target","CARD","card",cardId,"reason",reason,"algorithm",algorithm,"evidence",evidence,"amount",quote.get("amount_cents"),"max",quote.get("proportional_cents"),"used",c.get("used"),"snapshot",c.get("rule_snapshot")+"；原因="+reason+"；算法="+algorithm));
            db.cardStatus(cardId,"REVIEW");audit(customer,"REFUND_REQUEST",refund,quote.toString());return refund;
        });return refundView(result);
    }
    @Transactional public Map<String,Object> balanceRefund(String customer,long amount,String evidence,String key) {
        require(amount>0&&amount<=100_000_000,"金额无效");
        String result=command(customer,key,"BALANCE_REFUND_REQUEST",List.of(amount,evidence),()->{
            require(amount<=n(exists(db.customer(customer)),"balance_cents"),"超过独立储值本金余额");
            require(db.refunds(customer).stream().noneMatch(r->"BALANCE".equals(r.get("target_type"))&&"PENDING".equals(r.get("status"))),"已有储值退款申请");
            String refund=id();db.insertRefund(row("id",refund,"customer",customer,"target","BALANCE","card",null,"reason","CONSUMER","algorithm","PRINCIPAL","evidence",evidence,"amount",amount,"max",amount,"used",0,"snapshot","独立本金退款入口，仅申请与登记，无外部付款能力"));audit(customer,"BALANCE_REFUND_REQUEST",refund,"本金分="+amount);return refund;
        });return refundView(result);
    }
    @Transactional public Map<String,Object> negotiateRefund(String actor,String refundId,Map<String,Object> values,String key) {
        String result=command(actor,key,"REFUND_NEGOTIATE",List.of(refundId,values),()->{
            var refund=exists(db.refund(refundId));require("PENDING".equals(refund.get("status")),"申请已处理，不可改写协商记录");
            require("CARD".equals(refund.get("target_type")),"本入口仅处理单卡协商；储值退款仍按本金预留流程");
            var card=exists(db.card(s(refund,"card_id")));
            var history=db.negotiations(refundId);var previous=history.isEmpty()?null:history.get(0);
            String reason=values.get("reason")==null?(previous==null?s(refund,"reason"):s(previous,"resolved_reason")):s(values,"reason"),algorithm=values.get("algorithm")==null?(previous==null?s(refund,"algorithm"):s(previous,"algorithm")):s(values,"algorithm");
            String evidence=values.get("evidence")==null?(previous==null?"":s(previous,"agreement_evidence")):s(values,"evidence");
            long principal=values.get("principal")==null?(previous==null?n(refund,"amount_cents"):n(previous,"principal_cents")):n(values,"principal"),interest=values.get("interest")==null?(previous==null?0:n(previous,"interest_cents")):n(values,"interest");
            require(Set.of("CONSUMER","SHOP","OTHER_NON_CONSUMER","DISPUTED").contains(reason)&&Set.of("PROPORTIONAL","ORIGINAL_PRICE").contains(algorithm),"记录的原因或算法无效");
            require(principal>=0&&principal<=n(card,"paid_cents"),"拟议本金须在原预付本金范围内；保存记录不代表认可该方案可结算");
            require(interest>=0&&interest<=100_000_000,"利息金额超出人工登记范围");
            require(!s(values,"note").isBlank(),"请填写处理或争议说明");
            String negotiation=id();int revision=history.size()+1;
            boolean acknowledged=values.get("acknowledged")==null?(previous!=null&&yes(previous,"customer_acknowledged")):Boolean.TRUE.equals(values.get("acknowledged"));
            db.insertNegotiation(row("id",negotiation,"refund",refundId,"revision",revision,"actor",actor,"reason",reason,"algorithm",algorithm,"principal",principal,"interest",interest,"evidence",evidence,"note",values.get("note"),"acknowledged",acknowledged));
            audit(actor,"REFUND_NEGOTIATE",refundId,"协商记录="+negotiation+"；版本="+revision+"；"+values);return negotiation;
        });return db.negotiation(result);
    }
    @Transactional public Map<String,Object> reviewRefund(String actor,String refundId,boolean approve,long amount,String note,String key) {
        require(note!=null&&!note.isBlank(),"必须登记核对依据、利息及更有利约定处理结果");
        String result=command(actor,key,"REFUND_REVIEW",List.of(refundId,approve,amount,note),()->{
            var r=exists(db.refund(refundId));require("PENDING".equals(r.get("status")),"申请已处理");String cardId=(String)r.get("card_id");
            if(!approve) { db.refundStatus(refundId,"REJECTED",note);if(cardId!=null)db.cardStatus(cardId,"ACTIVE"); }
            else {
                var history=db.negotiations(refundId);var agreement=history.isEmpty()?null:history.get(0);
                if(agreement!=null) require(!"DISPUTED".equals(agreement.get("resolved_reason")),"原因仍有争议，可继续保存处理记录；结算保留待处理");
                else require(!"DISPUTED".equals(r.get("reason")),"争议申请保留待处理；先核实原因与双方证据，不自动选择少退");
                long expected=agreement==null?n(r,"amount_cents"):n(agreement,"principal_cents"),interest=agreement==null?0:n(agreement,"interest_cents");
                require(amount==expected,"审核本金与最新协商或原试算不一致，请核对");
                if(cardId!=null) {
                    var c=exists(db.card(cardId));require("REVIEW".equals(c.get("status"))&&n(c,"used")==n(r,"card_used"),"卡状态或余次已变化，请重新试算");
                    if(agreement!=null) {
                        var protectedQuote=quote(s(r,"customer_id"),cardId,s(agreement,"resolved_reason"),s(agreement,"algorithm"),s(agreement,"agreement_evidence"));
                        require(amount>=n(protectedQuote,"amount_cents"),"拟议本金低于适用规则保障，不能结算；原记录仍保留，可继续联系店长处理");
                    }
                    db.cardStatus(cardId,"RETURNED");require(db.balance(s(r,"customer_id"),amount+interest)==1,"原余额退回失败");
                    entry(s(r,"customer_id"),"REFUND_TO_BALANCE",amount,0,cardId,null,refundId,actor,note);
                    if(interest>0)entry(s(r,"customer_id"),"REFUND_INTEREST",interest,0,cardId,null,refundId,actor,"人工协商利息；不计充值或履约参考值");
                    db.refundStatus(refundId,"REGISTERED",note);
                } else {
                    // Reserve the principal in the isolated ledger. No API calls or bank transfers are made.
                    require(db.balance(s(r,"customer_id"),-amount)==1,"当前本金余额不足，请重新申请");
                    entry(s(r,"customer_id"),"BALANCE_REFUND_RESERVED",-amount,0,null,null,refundId,actor,note);
                    db.refundStatus(refundId,"RESERVED_NO_PAYOUT",note);
                }
            }
            audit(actor,"REFUND_REVIEW",refundId,"批准="+approve+"；协商版本="+db.negotiations(refundId).stream().findFirst().map(v->v.get("id")).orElse("原试算")+"；"+note);return refundId;
        });return refundView(result);
    }
    @Transactional public Map<String,Object> settings(String actor,Map<String,Object> values,String key) {
        String result=command(actor,key,"SETTINGS",values,()->{
            LocalTime open=LocalTime.parse(s(values,"opens")),close=LocalTime.parse(s(values,"closes")),bs=LocalTime.parse(s(values,"breakStart")),be=LocalTime.parse(s(values,"breakEnd"));
            require(open.isBefore(close)&&!bs.isBefore(open)&&bs.isBefore(be)&&!be.isAfter(close),"营业及休息时间顺序不正确");
            require(n(values,"capacity")>=1&&n(values,"capacity")<=5&&n(values,"threshold")>=0,"容量或演示门槛不正确");
            String before=db.shop().toString();db.updateShop(values);audit(actor,"SETTINGS","1","原="+before+"；新="+values+"；已有预约保留，需人工逐项协调");return "1";
        });return row("shop",db.shop(),"preserved_appointments",db.appointments(null,true).stream().filter(a->Set.of("PENDING","CONFIRMED").contains(s(a,"status"))).toList(),"message","设置已更新；已有预约全部保留，请逐项协调冲突；历史售卡权益及已累计等级值不重算");
    }
    @Transactional public Map<String,Object> saveService(String actor,Map<String,Object> values,String key) {
        String result=command(actor,key,"SAVE_SERVICE",values,()->{
            String serviceId=s(values,"id");if(db.service(serviceId)==null)db.insertService(values);else db.updateService(values);audit(actor,"SAVE_SERVICE",serviceId,values.toString());return serviceId;
        });return db.service(result);
    }
    @Transactional public Map<String,Object> savePackage(String actor,Map<String,Object> values,String key) {
        String result=command(actor,key,"SAVE_PACKAGE",values,()->{
            String packageId=s(values,"id");exists(db.service(s(values,"serviceId")));require(n(values,"priceCents")>=n(values,"total"),"每次至少分摊 1 分");
            if(db.offer(packageId,"VIP")==null)db.insertPackage(values);else db.updatePackage(values);
            db.clearTierPrice(packageId);if(values.get("vipPriceCents")!=null){long vip=n(values,"vipPriceCents");require(vip>=n(values,"total")&&vip<=n(values,"priceCents"),"专属价格应在套餐分摊下限与默认价之间");db.insertTierPrice(packageId,vip);}
            audit(actor,"SAVE_PACKAGE",packageId,values.toString());return packageId;
        });return db.offer(result,"VIP");
    }
}
