package cn.lunaria.integration;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;
import cn.lunaria.WechatIdentityBindingService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.security.Principal;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import static cn.lunaria.integration.ExternalIntegrationBoundary.Feature.*;

/** Identity transport is implemented; money and notification HTTP operations remain deliberately closed. */
@RestController
public class ExternalIntegrationController {
    private final ExternalIntegrationBoundary boundary;
    private final WechatCodeClient codes;
    private final WechatIdentityBindingService bindings;
    private final HttpSessionSecurityContextRepository contexts;
    public ExternalIntegrationController(ExternalIntegrationBoundary boundary,WechatCodeClient codes,WechatIdentityBindingService bindings,HttpSessionSecurityContextRepository contexts){this.boundary=boundary;this.codes=codes;this.bindings=bindings;this.contexts=contexts;}
    public record WechatLogin(@NotBlank @Size(max=512) String code) {}
    @GetMapping("/api/integrations/status")
    public Map<String,Object> status(){return Map.of("mode","ISOLATED_DEMO","production_ready",false,"services",boundary.capabilities());}
    @PostMapping("/api/auth/wechat")
    public ResponseEntity<?> login(@RequestBody @Valid WechatLogin input,HttpServletRequest request,HttpServletResponse response){
        var binding=bindings.resolve(codes.exchange(input.code()));
        if(binding.customerId()==null)return ResponseEntity.status(409).body(Map.of("code","WECHAT_BINDING_REQUIRED","binding_id",binding.bindingId(),"message","身份已验证，请联系店长核对并绑定顾客档案"));
        var auth=UsernamePasswordAuthenticationToken.authenticated(binding.customerId(),null,java.util.List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER")));
        request.getSession(true);request.changeSessionId();var context=SecurityContextHolder.createEmptyContext();context.setAuthentication(auth);SecurityContextHolder.setContext(context);contexts.saveContext(context,request,response);
        return ResponseEntity.ok(Map.of("username",binding.customerId(),"role","CUSTOMER","identity_source","WECHAT_VERIFIED"));
    }
    public record Bind(@NotBlank @Size(max=40) String customerId,@NotBlank @Size(max=300) String reason) {}
    @PostMapping("/api/owner/wechat-bindings/{id}")
    public Object bind(Principal actor,@PathVariable String id,@RequestBody @Valid Bind input,@RequestHeader("Idempotency-Key") String key){return bindings.bind(actor.getName(),id,input.customerId(),input.reason(),key);}
    @PostMapping("/api/payments/wechat/prepay")
    public void prepay(){throw boundary.unavailable(WECHAT_PAYMENT);}
    @PostMapping("/api/owner/integrations/wechat/notifications")
    public void notifyAppointment(){throw boundary.unavailable(WECHAT_NOTIFICATION);}
}

@RestControllerAdvice
class ExternalIntegrationErrors {
    @ExceptionHandler(ProviderFailure.class)
    ResponseEntity<?> provider(ProviderFailure error){return ResponseEntity.status(error.status).body(Map.of("code",error.code,"feature","wechat_identity","enabled",error.status!=503,"message",error.getMessage()));}
    @ExceptionHandler(ExternalIntegrationBoundary.Unavailable.class)
    ResponseEntity<?> unavailable(ExternalIntegrationBoundary.Unavailable error){
        return ResponseEntity.status(503).body(Map.of("code","INTEGRATION_NOT_CONFIGURED","feature",error.feature.key,"enabled",false,"message",error.getMessage()));
    }
}
