package cn.lunaria;

import cn.lunaria.integration.WechatPaymentAdapter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.security.Principal;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import static cn.lunaria.ShopService.*;

@RestController @RequestMapping("/api")
public class PaymentController {
    private final PaymentService payments;
    public PaymentController(PaymentService payments){this.payments=payments;}
    public record Amount(@NotNull @Min(1) @Max(100000000) Long amountCents){}
    public record RefundRequest(@NotNull @Min(1) @Max(100000000) Long amountCents,@NotBlank @Size(max=300) String reason){}
    public record Prepay(String orderId){}
    public record Review(@NotNull Boolean approve,@NotBlank @Size(max=500) String note){}
    public record Note(@Size(max=250) String reference,@NotBlank @Size(max=500) String note){}
    public record ManualNoRefund(@NotNull @Min(1) @Max(100000000) Long amountCents,@NotBlank @Size(max=250) String reference,@NotBlank @Size(max=500) String note){}
    @PostMapping("/me/payment-orders") Object create(Principal p,@RequestBody @Valid Amount b,@RequestHeader("Idempotency-Key") String key){return payments.createOrder(p.getName(),b.amountCents(),key);}
    @GetMapping("/me/payment-orders") Object list(Principal p){return payments.orders(p.getName());}
    @GetMapping("/me/payment-orders/{id}") Object order(Principal p,@PathVariable String id){return payments.order(p.getName(),id);}
    @GetMapping("/owner/payment-orders") Object ownerOrders(){return payments.orders(null);}
    @GetMapping("/owner/payment-orders/{id}") Object ownerOrder(@PathVariable String id){return payments.order(null,id);}
    @PostMapping("/payments/wechat/prepay") Object prepay(Principal p,@RequestBody(required=false) Prepay b,@RequestHeader(value="Idempotency-Key",required=false) String key){return response(payments.prepare(p.getName(),b==null?null:b.orderId(),key));}
    @PostMapping("/me/payment-orders/{id}/query") Object query(Principal p,@PathVariable String id,@RequestHeader("Idempotency-Key") String key){return response(payments.queryOrder(p.getName(),p.getName(),id,key));}
    @PostMapping("/owner/payment-orders/{id}/query") Object ownerQuery(Principal p,@PathVariable String id,@RequestHeader("Idempotency-Key") String key){return response(payments.queryOrder(p.getName(),null,id,key));}
    @PostMapping("/me/payment-orders/{id}/refunds") Object requestRefund(Principal p,@PathVariable String id,@RequestBody @Valid RefundRequest b,@RequestHeader("Idempotency-Key") String key){return payments.requestRefund(p.getName(),id,b.amountCents(),b.reason(),key);}
    @GetMapping("/me/channel-refunds/{id}") Object refund(Principal p,@PathVariable String id){return payments.refund(p.getName(),id);}
    @GetMapping("/owner/channel-refunds/{id}") Object ownerRefund(@PathVariable String id){return payments.refund(null,id);}
    @PostMapping("/owner/channel-refunds/{id}/review") Object review(Principal p,@PathVariable String id,@RequestBody @Valid Review b,@RequestHeader("Idempotency-Key") String key){return payments.reviewRefund(p.getName(),id,b.approve(),b.note(),key);}
    @PostMapping("/owner/channel-refunds/{id}/submit") Object submit(Principal p,@PathVariable String id,@RequestHeader("Idempotency-Key") String key){return response(payments.submitRefund(p.getName(),id,key));}
    @PostMapping("/owner/channel-refunds/{id}/query") Object refundQuery(Principal p,@PathVariable String id,@RequestHeader("Idempotency-Key") String key){return response(payments.queryRefund(p.getName(),null,id,key));}
    @PostMapping("/me/channel-refunds/{id}/query") Object customerRefundQuery(Principal p,@PathVariable String id,@RequestHeader("Idempotency-Key") String key){return response(payments.queryRefund(p.getName(),p.getName(),id,key));}
    @PostMapping("/owner/channel-refunds/{id}/release") Object release(Principal p,@PathVariable String id,@RequestBody @Valid Note b,@RequestHeader("Idempotency-Key") String key){return payments.releaseRefund(p.getName(),id,b.note(),key);}
    @PostMapping("/owner/payment-orders/{id}/reconciliation") Object orderNote(Principal p,@PathVariable String id,@RequestBody @Valid Note b,@RequestHeader("Idempotency-Key") String key){return payments.reconciliation(p.getName(),"ORDER",id,Objects.toString(b.reference(),""),b.note(),key);}
    @PostMapping("/owner/channel-refunds/{id}/reconciliation") Object refundNote(Principal p,@PathVariable String id,@RequestBody @Valid Note b,@RequestHeader("Idempotency-Key") String key){return payments.reconciliation(p.getName(),"REFUND",id,Objects.toString(b.reference(),""),b.note(),key);}
    @PostMapping("/owner/channel-refunds/{id}/reconciliation/no-refund") Object manualNoRefund(Principal p,@PathVariable String id,@RequestBody @Valid ManualNoRefund b,@RequestHeader("Idempotency-Key") String key){return payments.confirmNoRefund(p.getName(),id,b.amountCents(),b.reference(),b.note(),key);}
    @PostMapping("/payments/wechat/notify") ResponseEntity<Void> notifyPayment(@RequestBody String body,@RequestHeader Map<String,String> headers){payments.paymentCallback(callback(headers,body));return ResponseEntity.noContent().build();}
    @PostMapping("/payments/wechat/refund-notify") ResponseEntity<Void> notifyRefund(@RequestBody String body,@RequestHeader Map<String,String> headers){payments.refundCallback(callback(headers,body));return ResponseEntity.noContent().build();}
    private String header(Map<String,String> headers,String name){return headers.entrySet().stream().filter(e->e.getKey().equalsIgnoreCase(name)).map(Map.Entry::getValue).findFirst().orElse(null);}
    private WechatPaymentAdapter.Callback callback(Map<String,String> headers,String body){return new WechatPaymentAdapter.Callback(header(headers,"wechatpay-serial"),header(headers,"wechatpay-timestamp"),header(headers,"wechatpay-nonce"),header(headers,"wechatpay-signature"),body);}
    @SuppressWarnings("unchecked") private ResponseEntity<?> response(Map<String,Object> result){String state=s((Map<String,Object>)result.get("attempt"),"status");return ResponseEntity.status(Set.of("STARTED","UNKNOWN","REVIEW").contains(state)?202:200).body(result);}
}
