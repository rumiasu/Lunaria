package cn.lunaria;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.security.Principal;
import java.time.*;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;
import static cn.lunaria.ShopService.row;

@RestController @RequestMapping("/api")
public class ApiController {
    private final ShopService shop;
    public ApiController(ShopService shop) { this.shop=shop; }
    public record Purchase(@NotBlank String packageId) {}
    public record Booking(@NotBlank @Size(max=40) String serviceId,@NotNull LocalDateTime startsAt,@NotNull @Size(max=300) String note,@Size(max=40) String cardId) {}
    public record Reason(@NotBlank @Size(max=300) String reason) {}
    public record Move(@NotNull LocalDateTime startsAt,@NotBlank @Size(max=300) String reason) {}
    public record Complete(@NotBlank String cardId) {}
    public record Recharge(@NotBlank String customerId,@Min(1) @Max(100000000) long amountCents,@NotBlank @Size(max=250) String reference) {}
    public record Refund(@NotBlank String cardId,@NotBlank String reason,@NotBlank String algorithm,@NotNull @Size(max=500) String evidence) {}
    public record BalanceRefund(@Min(1) @Max(100000000) long amountCents,@NotNull @Size(max=500) String evidence) {}
    public record Review(@NotNull Boolean approve,@NotNull @Min(0) Long amountCents,@NotBlank @Size(max=500) String note) {}
    public record Negotiation(String resolvedReason,String algorithm,@Min(0) @Max(100000000) Long principalCents,@Min(0) @Max(100000000) Long interestCents,@Size(max=500) String agreementEvidence,@NotBlank @Size(max=500) String note,Boolean customerAcknowledged) {
        Map<String,Object> values(){return row("reason",resolvedReason,"algorithm",algorithm,"principal",principalCents,"interest",interestCents,"evidence",agreementEvidence,"note",note,"acknowledged",customerAcknowledged);}
    }
    public record Settings(@NotBlank @Pattern(regexp="(?:[01][0-9]|2[0-3]):[0-5][0-9]") String opens,@NotBlank @Pattern(regexp="(?:[01][0-9]|2[0-3]):[0-5][0-9]") String closes,@NotBlank @Pattern(regexp="(?:[01][0-9]|2[0-3]):[0-5][0-9]") String breakStart,@NotBlank @Pattern(regexp="(?:[01][0-9]|2[0-3]):[0-5][0-9]") String breakEnd,@Min(1) @Max(5) int capacity,@NotNull Boolean paused,@NotNull @Min(0) @Max(100000000) Long threshold) {
        Map<String,Object> values(){return row("opens",opens,"closes",closes,"breakStart",breakStart,"breakEnd",breakEnd,"capacity",capacity,"paused",paused,"threshold",threshold);}
    }
    public record ServiceDraft(@NotBlank @Pattern(regexp="[a-zA-Z0-9_-]{1,40}") String id,@NotBlank @Size(max=80) String name,@Min(15) @Max(240) int duration,@Min(1) @Max(100000000) long listCents,@NotNull Boolean active) {
        Map<String,Object> values(){return row("id",id,"name",name,"duration",duration,"listCents",listCents,"active",active);}
    }
    public record PackageDraft(@NotBlank @Pattern(regexp="[a-zA-Z0-9_-]{1,40}") String id,@NotBlank @Size(max=40) String serviceId,@NotBlank @Size(max=80) String name,@Min(1) @Max(100) int total,@Min(1) @Max(100000000) long priceCents,@Min(1) @Max(100000000) Long vipPriceCents,@NotNull Boolean active) {
        Map<String,Object> values(){return row("id",id,"serviceId",serviceId,"name",name,"total",total,"priceCents",priceCents,"vipPriceCents",vipPriceCents,"active",active);}
    }
    @GetMapping("/session") public Map<String,Object> session(Authentication auth,CsrfToken csrf) {
        return row("authenticated",auth!=null,"username",auth==null?"":auth.getName(),"role",auth==null?"":auth.getAuthorities().stream().anyMatch(a->a.getAuthority().equals("ROLE_OWNER"))?"OWNER":"CUSTOMER","csrf",csrf.getToken(),"mode","ISOLATED_DEMO","shop_zone","Asia/Shanghai");
    }
    @GetMapping("/catalog") Object catalog(Principal p) { return shop.catalog(p.getName().equals("owner")?"alice":p.getName()); }
    @GetMapping("/slots") Object slots(Principal p,@RequestParam String serviceId,@RequestParam LocalDate date,@RequestParam(required=false) String cardId) { return shop.slots(p.getName(),serviceId,date,cardId); }
    @GetMapping("/me") Object me(Principal p){ return shop.overview(p.getName()); }
    @GetMapping("/me/cards/{id}") Object card(Principal p,@PathVariable String id){return shop.card(p.getName(),id);}
    @PostMapping("/me/purchases") Object purchase(Principal p,@RequestBody @Valid Purchase b,@RequestHeader("Idempotency-Key") String key){return shop.purchase(p.getName(),b.packageId,key);}
    @PostMapping("/me/appointments") Object book(Principal p,@RequestBody @Valid Booking b,@RequestHeader("Idempotency-Key") String key){return shop.book(p.getName(),b.serviceId,b.startsAt,b.note,b.cardId,key);}
    @PostMapping("/me/appointments/{id}/cancel") Object cancel(Principal p,@PathVariable String id,@RequestBody @Valid Reason b,@RequestHeader("Idempotency-Key") String key){return shop.cancel(p.getName(),p.getName(),id,b.reason,key);}
    @PostMapping("/me/refund-quote") Object quote(Principal p,@RequestBody @Valid Refund b){return shop.quote(p.getName(),b.cardId,b.reason,b.algorithm,b.evidence);}
    @PostMapping("/me/refunds") Object refund(Principal p,@RequestBody @Valid Refund b,@RequestHeader("Idempotency-Key") String key){return shop.requestRefund(p.getName(),b.cardId,b.reason,b.algorithm,b.evidence,key);}
    @GetMapping("/me/refunds/{id}") Object refundDetail(Principal p,@PathVariable String id){return shop.refundDetail(p.getName(),id);}
    @PostMapping("/me/balance-refunds") Object balanceRefund(Principal p,@RequestBody @Valid BalanceRefund b,@RequestHeader("Idempotency-Key") String key){return shop.balanceRefund(p.getName(),b.amountCents,b.evidence,key);}
    @GetMapping("/owner/dashboard") Object dashboard(){return shop.dashboard();}
    @GetMapping("/owner/customers/{id}") Object customer(@PathVariable String id){return shop.overview(id);}
    @PostMapping("/owner/recharges") Object recharge(Principal p,@RequestBody @Valid Recharge b,@RequestHeader("Idempotency-Key") String key){return shop.recharge(p.getName(),b.customerId,b.amountCents,b.reference,key);}
    @PostMapping("/owner/appointments/{id}/confirm") Object confirm(Principal p,@PathVariable String id,@RequestHeader("Idempotency-Key") String key){return shop.confirm(p.getName(),id,key);}
    @PostMapping("/owner/appointments/{id}/cancel") Object ownerCancel(Principal p,@PathVariable String id,@RequestBody @Valid Reason b,@RequestHeader("Idempotency-Key") String key){return shop.cancel(p.getName(),null,id,b.reason,key);}
    @PostMapping("/owner/appointments/{id}/move") Object move(Principal p,@PathVariable String id,@RequestBody @Valid Move b,@RequestHeader("Idempotency-Key") String key){return shop.move(p.getName(),id,b.startsAt,b.reason,key);}
    @PostMapping("/owner/appointments/{id}/complete") Object complete(Principal p,@PathVariable String id,@RequestBody @Valid Complete b,@RequestHeader("Idempotency-Key") String key){return shop.complete(p.getName(),id,b.cardId,key);}
    @PostMapping("/owner/ledger/{id}/reverse") Object reverse(Principal p,@PathVariable String id,@RequestBody @Valid Reason b,@RequestHeader("Idempotency-Key") String key){return shop.reverse(p.getName(),id,b.reason,key);}
    @PostMapping("/owner/refunds/{id}/review") Object review(Principal p,@PathVariable String id,@RequestBody @Valid Review b,@RequestHeader("Idempotency-Key") String key){return shop.reviewRefund(p.getName(),id,b.approve,b.amountCents,b.note,key);}
    @GetMapping("/owner/refunds/{id}") Object ownerRefundDetail(@PathVariable String id){return shop.refundDetail(null,id);}
    @PostMapping("/owner/refunds/{id}/negotiations") Object negotiate(Principal p,@PathVariable String id,@RequestBody @Valid Negotiation b,@RequestHeader("Idempotency-Key") String key){return shop.negotiateRefund(p.getName(),id,b.values(),key);}
    @PostMapping("/owner/settings") Object settings(Principal p,@RequestBody @Valid Settings b,@RequestHeader("Idempotency-Key") String key){return shop.settings(p.getName(),b.values(),key);}
    @PostMapping("/owner/services") Object service(Principal p,@RequestBody @Valid ServiceDraft b,@RequestHeader("Idempotency-Key") String key){return shop.saveService(p.getName(),b.values(),key);}
    @PostMapping("/owner/packages") Object pack(Principal p,@RequestBody @Valid PackageDraft b,@RequestHeader("Idempotency-Key") String key){return shop.savePackage(p.getName(),b.values(),key);}
}

@RestController @Profile("demo")
class DemoLoginController {
    private final AuthenticationManager manager; private final HttpSessionSecurityContextRepository contexts;
    DemoLoginController(AuthenticationManager manager,HttpSessionSecurityContextRepository contexts){this.manager=manager;this.contexts=contexts;}
    record Login(@NotBlank String username,@NotBlank String password){}
    @PostMapping("/api/demo/login") Object login(@RequestBody @Valid Login login,HttpServletRequest request,HttpServletResponse response) {
        var auth=manager.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(login.username,login.password));
        request.getSession(true);request.changeSessionId();var context=SecurityContextHolder.createEmptyContext();context.setAuthentication(auth);SecurityContextHolder.setContext(context);contexts.saveContext(context,request,response);
        return Map.of("username",auth.getName(),"message","独立演示登录；不代表微信身份已接入");
    }
}

@RestControllerAdvice
class ApiErrors {
    @ExceptionHandler(BusinessException.class) ResponseEntity<?> business(BusinessException e){return ResponseEntity.status(e.status).body(Map.of("message",e.getMessage()));}
    @ExceptionHandler(org.springframework.security.core.AuthenticationException.class) ResponseEntity<?> auth(Exception e){return ResponseEntity.status(401).body(Map.of("message","演示账户或密码不正确"));}
    @ExceptionHandler({org.springframework.web.bind.MethodArgumentNotValidException.class,org.springframework.http.converter.HttpMessageNotReadableException.class,org.springframework.web.bind.MissingRequestHeaderException.class,java.time.DateTimeException.class,IllegalArgumentException.class})
    ResponseEntity<?> invalid(Exception e){return ResponseEntity.badRequest().body(Map.of("message","输入不正确，请核对必填项、金额分值及日期格式"));}
}
