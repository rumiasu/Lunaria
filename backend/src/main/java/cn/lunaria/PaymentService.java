package cn.lunaria;

import cn.lunaria.integration.*;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static cn.lunaria.ShopService.*;
import static cn.lunaria.BusinessException.require;

/** One-shop principal top-ups. Network calls occur after durable intents and outside SQL transactions. */
@Service
public class PaymentService {
    private final PaymentMapper pay;private final StoreMapper store;private final WechatIdentityMapper identities;
    private final PaymentGateway gateway;private final ShopService shop;private final TransactionTemplate tx;
    public PaymentService(PaymentMapper pay,StoreMapper store,WechatIdentityMapper identities,PaymentGateway gateway,ShopService shop,PlatformTransactionManager manager){this.pay=pay;this.store=store;this.identities=identities;this.gateway=gateway;this.shop=shop;tx=new TransactionTemplate(manager);}
    public void requireChannel(){if(!gateway.available())throw PaymentGateway.disabled();}
    private <T>T locked(Supplier<T> work){return tx.execute(status->{require(store.lockShop()!=null,"本店配置尚未初始化，不能处理资金订单");return work.get();});}
    private String number(){return UUID.randomUUID().toString().replace("-","");}
    private void audit(String actor,String action,String target,String details){store.insertAudit(row("id",id(),"actor",actor,"action",action,"target",target,"details",details));}
    private void key(String key){if(key==null||!key.matches("[A-Za-z0-9:_-]{8,100}"))throw new BusinessException(400,"需要有效的操作编号");}
    private String command(String actor,String key,String scope,Object payload,Supplier<String> work){
        key(key);String hash=CommandFingerprint.of(scope,payload);var previous=store.command(actor,key);
        if(previous!=null){require(scope.equals(previous.get("scope"))&&hash.equals(previous.get("fingerprint")),"操作编号已用于其他请求");return s(previous,"result_id");}
        String result=work.get();store.commandSave(actor,key,scope,hash,result);return result;
    }
    private Map<String,Object> ownOrder(String customer,String order){var value=exists(pay.order(order));if(customer!=null&&!customer.equals(value.get("customer_id")))throw new BusinessException(404,"支付订单不存在");return value;}
    private Map<String,Object> ownRefund(String customer,String refund){var value=exists(pay.refund(refund));ownOrder(customer,s(value,"order_id"));return value;}
    private PaymentGateway.Order intent(Map<String,Object> order){return new PaymentGateway.Order(s(order,"id"),s(order,"app_id"),s(order,"merchant_id"),s(order,"payer_open_id"),n(order,"amount_cents"));}
    private PaymentGateway.RefundIntent refundIntent(Map<String,Object> refund){var order=ownOrder(null,s(refund,"order_id"));return new PaymentGateway.RefundIntent(s(refund,"id"),intent(order),s(exists(pay.receipt(s(order,"id"))),"provider_transaction_id"),n(refund,"amount_cents"));}
    public Map<String,Object> order(String customer,String id){
        var result=new LinkedHashMap<>(ownOrder(customer,id));result.remove("payer_open_id");
        result.put("receipt",pay.receipt(id));result.put("refunds",pay.refunds(id));result.put("attempts",pay.attempts("ORDER",id));result.put("reconciliation",pay.notes("ORDER",id));result.put("channel_mode",gateway.mode());return result;
    }
    public List<Map<String,Object>> orders(String customer){return pay.orders(customer).stream().map(o->order(customer,s(o,"id"))).toList();}
    public Map<String,Object> refund(String customer,String id){var result=new LinkedHashMap<>(ownRefund(customer,id));result.put("business_refund",shop.refundDetail(customer,s(result,"business_refund_id")));result.put("attempts",pay.attempts("REFUND",id));result.put("reconciliation",pay.notes("REFUND",id));result.put("channel_mode",gateway.mode());return result;}
    public Map<String,Object> createOrder(String customer,long amount,String key){
        requireChannel();require(amount>0&&amount<=100_000_000,"本金金额超出范围");var context=gateway.context();
        String order=locked(()->command(customer,key,"PAY_ORDER_CREATE",amount,()->{
            exists(store.customer(customer));var bound=identities.forCustomer(context.appId(),customer);require(bound!=null,"请先完成本应用的微信身份绑定");
            String id=number();pay.insertOrder(row("id",id,"customer",customer,"app",context.appId(),"merchant",context.merchantId(),"payer",bound.get("open_id"),"amount",amount));
            audit(customer,"PAY_ORDER_CREATE",id,"principal top-up; cents="+amount);return id;
        }));return order(customer,order);
    }
    private record Start(String id,boolean call){}
    private Start begin(String actor,String customer,String kind,String target,String operation,String key){
        return locked(()->{
            key(key);String hash=CommandFingerprint.of(operation,List.of(kind,target));var previous=store.command(actor,key);
            if(previous!=null){require(operation.equals(previous.get("scope"))&&hash.equals(previous.get("fingerprint")),"操作编号已用于其他请求");return new Start(s(previous,"result_id"),false);}
            Map<String,Object> entity="ORDER".equals(kind)?ownOrder(customer,target):ownRefund(customer,target);boolean call=true;
            if("PAY_PREPARE".equals(operation)){
                String state=s(entity,"status");if(Set.of("PAID","PREPAY_READY").contains(state))call=false;
                else{require(Set.of("CREATED","AWAITING_PAYMENT").contains(state),"预支付结果未明或订单已关闭，请先查单");pay.orderStatus(target,"PREPAY_PENDING");}
            }
            if("REFUND_SUBMIT".equals(operation)){
                var business=exists(store.refund(s(entity,"business_refund_id")));
                if("SUCCESS".equals(entity.get("status")))call=false;
                else{require(Set.of("REQUESTED","READY").contains(s(entity,"status"))&&"RESERVED_NO_PAYOUT".equals(business.get("status"))&&!disputed(s(business,"id")),"仅发送已批准且无争议的原预留，未知结果不能重发");pay.refundStatus(target,"SUBMITTING");}
            }
            String id=id();pay.insertAttempt(row("id",id,"kind",kind,"target",target,"operation",operation));
            if(!call)pay.attemptStatus(id,"ACCEPTED","EXISTING_RESULT");store.commandSave(actor,key,operation,hash,id);return new Start(id,call);
        });
    }
    private Map<String,Object> result(String customer,String kind,String target,Start attempt){return row("attempt",pay.attempt(attempt.id()),"result","ORDER".equals(kind)?order(customer,target):refund(customer,target));}
    private void unknown(Start attempt,String kind,String target,String code){locked(()->{
        pay.attemptStatus(attempt.id(),"UNKNOWN",code);
        if("ORDER".equals(kind)){if(!Set.of("PAID","CLOSED").contains(s(ownOrder(null,target),"status")))pay.orderStatus(target,"PAYMENT_UNKNOWN");}
        else if(!Set.of("SUCCESS","CANCELLED","FAILED","CONFLICT_REVIEW").contains(s(exists(pay.refund(target)),"status")))pay.refundStatus(target,"UNKNOWN");
        return null;
    });}
    public Map<String,Object> prepare(String customer,String orderId,String key){
        requireChannel();Start attempt=begin(customer,customer,"ORDER",orderId,"PAY_PREPARE",key);
        if(attempt.call())try{
            String prepay=gateway.prepare(intent(ownOrder(customer,orderId)));if(prepay==null||prepay.isBlank()||prepay.length()>128)throw new PaymentGateway.UnknownResult();
            locked(()->{pay.prepay(orderId,prepay);if(Set.of("PREPAY_PENDING","CREATED","AWAITING_PAYMENT").contains(s(ownOrder(null,orderId),"status")))pay.orderStatus(orderId,"PREPAY_READY");pay.attemptStatus(attempt.id(),"ACCEPTED","PREPAY_RESPONSE_RECEIVED");return null;});
        }catch(RuntimeException error){unknown(attempt,"ORDER",orderId,"PREPAY_RESULT_UNKNOWN");}
        return result(customer,"ORDER",orderId,attempt);
    }
    private ProviderFailure mismatch(){return new ProviderFailure(409,"PAYMENT_CONTEXT_MISMATCH","渠道结果与服务端订单、商户、来源或金额不一致","wechat_payment");}
    private void checkPayment(PaymentGateway.Payment paid,Map<String,Object> order){
        if(paid==null||!s(order,"id").equals(paid.orderId())||!s(order,"app_id").equals(paid.appId())||!s(order,"merchant_id").equals(paid.merchantId())||n(order,"amount_cents")!=paid.amountCents()||!"CNY".equals(paid.currency()))throw mismatch();
        if("SUCCESS".equals(paid.state())&&(!s(order,"payer_open_id").equals(paid.payerOpenId())||paid.transactionId()==null||paid.transactionId().isBlank()||paid.transactionId().length()>80))throw mismatch();
    }
    private void paymentResult(PaymentGateway.Payment paid){
        var order=exists(pay.order(paid.orderId()));checkPayment(paid,order);String id=s(order,"id");
        if("SUCCESS".equals(paid.state())){
            var existing=pay.receipt(id);if(existing!=null){if(!paid.transactionId().equals(existing.get("provider_transaction_id")))throw mismatch();return;}
            if(pay.receiptByTransaction(paid.transactionId())!=null)throw mismatch();
            String ledger=id();require(store.balance(s(order,"customer_id"),paid.amountCents())==1,"本金入账失败");
            store.insertEntry(row("id",ledger,"customer",order.get("customer_id"),"kind","RECHARGE","amount",paid.amountCents(),"units",0,"card",null,"appointment",null,"source",id,"actor","wechat-payment","note","verified principal top-up; order="+id));
            pay.insertReceipt(row("id",id(),"order",id,"transaction",paid.transactionId(),"amount",paid.amountCents(),"ledger",ledger));pay.orderStatus(id,"PAID");
            audit("wechat-payment","PAYMENT_POSTED",id,"verified unique transaction; ledger="+ledger);
        }else if(!"PAID".equals(order.get("status")))pay.orderStatus(id,switch(Objects.toString(paid.state(),"")){case "NOT_PAID"->"AWAITING_PAYMENT";case "CLOSED"->"CLOSED";default->"PAYMENT_UNKNOWN";});
    }
    private void conflict(String kind,String target,String code){locked(()->{if(("ORDER".equals(kind)?pay.order(target):pay.refund(target))!=null)note("provider",kind,target,"PROVIDER_CONFLICT","",code);return null;});}
    public void paymentCallback(WechatPaymentAdapter.Callback callback){
        requireChannel();var paid=gateway.verifyPayment(callback);if(paid==null||!"SUCCESS".equals(paid.state()))throw mismatch();
        try{locked(()->{paymentResult(paid);return null;});}catch(ProviderFailure error){conflict("ORDER",paid.orderId(),error.code);throw error;}
    }
    public Map<String,Object> queryOrder(String actor,String customer,String id,String key){
        requireChannel();Start attempt=begin(actor,customer,"ORDER",id,"PAY_QUERY",key);
        if(attempt.call())try{var value=gateway.queryPayment(intent(ownOrder(customer,id)));locked(()->{checkPayment(value,ownOrder(customer,id));paymentResult(value);pay.attemptStatus(attempt.id(),"PAYMENT_UNKNOWN".equals(pay.order(id).get("status"))?"UNKNOWN":"ACCEPTED","QUERY_VERIFIED");return null;});}
        catch(RuntimeException error){unknown(attempt,"ORDER",id,error instanceof ProviderFailure failure?failure.code:"QUERY_RESULT_UNKNOWN");}
        return result(customer,"ORDER",id,attempt);
    }
    public Map<String,Object> requestRefund(String customer,String orderId,long amount,String reason,String key){
        requireChannel();require(amount>0&&amount<=100_000_000&&reason!=null&&!reason.isBlank()&&reason.length()<=300,"退款金额或说明无效");
        String id=locked(()->command(customer,key,"CHANNEL_REFUND_REQUEST",List.of(orderId,amount,reason),()->{
            var order=ownOrder(customer,orderId);var receipt=exists(pay.receipt(orderId));require("PAID".equals(order.get("status")),"仅处理已验收入账订单");
            require(amount<=n(order,"amount_cents")-pay.allocatedRefunds(orderId),"超过原支付尚可退款金额");
            require(amount<=n(exists(store.customer(customer)),"balance_cents"),"当前未使用余额不足，请先处理卡权益或其他预留");
            require(store.refunds(customer).stream().noneMatch(r->"BALANCE".equals(r.get("target_type"))&&"PENDING".equals(r.get("status"))),"已有待审核本金退款");
            String business=id(),refund=number();store.insertRefund(row("id",business,"customer",customer,"target","BALANCE","card",null,"reason","CONSUMER","algorithm","PRINCIPAL","evidence",reason,"amount",amount,"max",amount,"used",0,"snapshot","original online payment order="+orderId));
            pay.insertRefund(row("id",refund,"order",orderId,"receipt",receipt.get("id"),"business",business,"amount",amount));audit(customer,"CHANNEL_REFUND_REQUEST",refund,"business="+business+"; original_order="+orderId);return refund;
        }));return refund(customer,id);
    }
    public Map<String,Object> reviewRefund(String actor,String id,boolean approve,String note,String key){return locked(()->{
        var request=ownRefund(null,id);shop.reviewRefund(actor,s(request,"business_refund_id"),approve,n(request,"amount_cents"),note,key);
        // A replay must not downgrade a later channel result.
        if(Set.of("REQUESTED","READY").contains(s(request,"status")))pay.refundStatus(id,approve?"READY":"CANCELLED");return refund(null,id);
    });}
    private boolean disputed(String business){var records=store.balanceRefundRecords(business);return !records.isEmpty()&&yes(records.get(0),"disputed");}
    private boolean refundResult(PaymentGateway.Refund value){
        if(value==null)throw mismatch();var request=exists(pay.refund(value.id()));var expected=refundIntent(request);
        if(!expected.order().id().equals(value.orderId())||!expected.order().merchantId().equals(value.merchantId())||!expected.transactionId().equals(value.transactionId())||expected.order().amountCents()!=value.orderCents()||expected.amountCents()!=value.refundCents()||!"CNY".equals(value.currency()))throw mismatch();
        String id=value.id(),provider=value.providerRefundId();
        if(provider!=null&&!provider.isBlank()){
            if(provider.length()>80)throw mismatch();var other=pay.refundByProvider(provider);
            if(other!=null&&!id.equals(other.get("id"))||request.get("provider_refund_id")!=null&&!provider.equals(request.get("provider_refund_id")))throw mismatch();
            pay.refundProvider(id,provider);
        }
        if("SUCCESS".equals(request.get("status")))return true;
        if("CANCELLED".equals(request.get("status"))&&!"CLOSED".equals(value.state())){pay.refundStatus(id,"CONFLICT_REVIEW");note("provider","REFUND",id,"PROVIDER_CONFLICT","","channel result after cancellation; no automatic financial mutation");return false;}
        if("SUCCESS".equals(value.state())){
            if(provider==null||provider.isBlank())throw mismatch();var business=exists(store.refund(s(request,"business_refund_id")));
            if(!Set.of("RESERVED_NO_PAYOUT","RESERVED_DISPUTED").contains(s(business,"status"))){pay.refundStatus(id,"CONFLICT_REVIEW");note("provider","REFUND",id,"PROVIDER_CONFLICT","","success result without intact reservation; no automatic balance change");return false;}
            var reservations=store.balanceReservations(s(business,"id"));require(reservations.size()==1&&n(reservations.get(0),"amount_cents")==-expected.amountCents(),"原预留来源不一致");
            String ledger=id();store.insertEntry(row("id",ledger,"customer",business.get("customer_id"),"kind","BALANCE_REFUND_CHANNEL_SETTLED","amount",expected.amountCents(),"units",0,"card",null,"appointment",null,"source",reservations.get(0).get("id"),"actor","wechat-payment","note","verified channel refund; original_order="+expected.order().id()));
            pay.refundLedger(id,ledger);pay.refundStatus(id,"SUCCESS");store.refundStatus(s(business,"id"),disputed(s(business,"id"))?"CHANNEL_REFUNDED_DISPUTED":"CHANNEL_REFUNDED","verified channel result; refund="+id);
            audit("wechat-payment","CHANNEL_REFUND_SETTLED",id,"reservation consumed once; ledger="+ledger);return true;
        }
        if(!Set.of("CANCELLED","CONFLICT_REVIEW").contains(s(request,"status")))pay.refundStatus(id,switch(Objects.toString(value.state(),"")){case "PROCESSING"->"PROCESSING";case "CLOSED"->"FAILED";default->"UNKNOWN";});return true;
    }
    public Map<String,Object> submitRefund(String actor,String id,String key){
        requireChannel();Start attempt=begin(actor,null,"REFUND",id,"REFUND_SUBMIT",key);
        if(attempt.call())try{var value=gateway.submitRefund(refundIntent(exists(pay.refund(id))));locked(()->{if(value==null||!id.equals(value.id()))throw mismatch();boolean accepted=refundResult(value);pay.attemptStatus(attempt.id(),!accepted?"REVIEW":"UNKNOWN".equals(pay.refund(id).get("status"))?"UNKNOWN":"ACCEPTED",accepted?"CHANNEL_RESPONSE":"RESERVATION_CONFLICT");return null;});}
        catch(RuntimeException error){unknown(attempt,"REFUND",id,error instanceof ProviderFailure failure?failure.code:"REFUND_RESULT_UNKNOWN");}
        return result(null,"REFUND",id,attempt);
    }
    public Map<String,Object> queryRefund(String actor,String customer,String id,String key){
        requireChannel();Start attempt=begin(actor,customer,"REFUND",id,"REFUND_QUERY",key);
        if(attempt.call())try{var value=gateway.queryRefund(refundIntent(ownRefund(customer,id)));locked(()->{if(value==null||!id.equals(value.id()))throw mismatch();boolean accepted=refundResult(value);pay.attemptStatus(attempt.id(),!accepted?"REVIEW":"UNKNOWN".equals(pay.refund(id).get("status"))?"UNKNOWN":"ACCEPTED",accepted?"QUERY_VERIFIED":"RESERVATION_CONFLICT");return null;});}
        catch(RuntimeException error){unknown(attempt,"REFUND",id,error instanceof ProviderFailure failure?failure.code:"REFUND_QUERY_UNKNOWN");}
        return result(customer,"REFUND",id,attempt);
    }
    public void refundCallback(WechatPaymentAdapter.Callback callback){
        requireChannel();var value=gateway.verifyRefund(callback);if(value==null)throw mismatch();
        try{boolean accepted=locked(()->refundResult(value));if(!accepted)throw mismatch();}catch(ProviderFailure error){conflict("REFUND",value.id(),error.code);throw error;}
    }
    public Map<String,Object> releaseRefund(String actor,String id,String reason,String key){
        require(reason!=null&&!reason.isBlank()&&reason.length()<=500,"请填写解除预留依据");
        locked(()->command(actor,key,"CHANNEL_REFUND_RELEASE",List.of(id,reason),()->{
            var request=ownRefund(null,id);require(Set.of("REQUESTED","READY","FAILED","MANUAL_NO_REFUND").contains(s(request,"status")),"结果未知、处理中或已成功，不能恢复余额；请查单/对账");
            var business=exists(store.refund(s(request,"business_refund_id")));require(!disputed(s(business,"id")),"争议未处理，可先保存说明");
            if("PENDING".equals(business.get("status")))store.refundStatus(s(business,"id"),"REJECTED",reason);
            else if("RESERVED_NO_PAYOUT".equals(business.get("status"))){
                var reservations=store.balanceReservations(s(business,"id"));require(reservations.size()==1&&n(reservations.get(0),"amount_cents")==-n(request,"amount_cents"),"原预留来源不一致");
                String ledger=id();require(store.balance(s(business,"customer_id"),n(request,"amount_cents"))==1,"恢复余额失败");
                store.insertEntry(row("id",ledger,"customer",business.get("customer_id"),"kind","BALANCE_REFUND_RELEASED","amount",request.get("amount_cents"),"units",0,"card",null,"appointment",null,"source",reservations.get(0).get("id"),"actor",actor,"note",reason));store.refundStatus(s(business,"id"),"RELEASED",reason);
            }else require("REJECTED".equals(business.get("status")),"原预留状态不允许解除");
            pay.refundStatus(id,"CANCELLED");audit(actor,"CHANNEL_REFUND_RELEASE",id,reason);return id;
        }));return refund(null,id);
    }
    private void note(String actor,String kind,String target,String type,String reference,String text){pay.insertNote(row("id",id(),"kind",kind,"target",target,"type",type,"reference",reference,"note",text,"actor",actor));}
    public Map<String,Object> confirmNoRefund(String actor,String id,long amount,String reference,String text,String key){
        require(reference!=null&&!reference.isBlank()&&reference.length()<=250&&text!=null&&!text.isBlank()&&text.length()<=500,"人工确认必须有外部查账凭据及说明");
        locked(()->command(actor,key,"REFUND_MANUAL_NO_RESULT",List.of(id,amount,reference,text),()->{
            var request=ownRefund(null,id);require(Set.of("UNKNOWN","SUBMITTING").contains(s(request,"status")),"只处理未决未知结果，不能覆盖已知渠道结果");
            require(amount>0&&amount==n(request,"amount_cents"),"人工核对金额必须与原退款相同");
            note(actor,"REFUND",id,"MANUAL_NO_REFUND",reference,text);pay.refundStatus(id,"MANUAL_NO_REFUND");audit(actor,"REFUND_MANUAL_RECONCILIATION",id,"owner external statement; not provider verification; no balance mutation");return id;
        }));return row("refund",refund(null,id),"provider_verified",false,"financial_mutation",false);
    }
    public Map<String,Object> reconciliation(String actor,String kind,String target,String reference,String text,String key){
        require(Set.of("ORDER","REFUND").contains(kind)&&text!=null&&!text.isBlank()&&text.length()<=500&&reference!=null&&reference.length()<=250,"对账说明无效");
        locked(()->command(actor,key,"PAY_RECONCILIATION",List.of(kind,target,reference,text),()->{if("ORDER".equals(kind))ownOrder(null,target);else ownRefund(null,target);String id=id();pay.insertNote(row("id",id,"kind",kind,"target",target,"type","OWNER_NOTE","reference",reference,"note",text,"actor",actor));audit(actor,"PAY_RECONCILIATION",target,"note="+id+"; no financial mutation");return id;}));
        return row("records",pay.notes(kind,target),"financial_mutation",false);
    }
}
