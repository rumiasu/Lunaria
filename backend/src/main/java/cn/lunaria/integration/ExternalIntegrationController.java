package cn.lunaria.integration;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import static cn.lunaria.integration.ExternalIntegrationBoundary.Feature.*;

/** Transport contracts for unavailable features. Each attempted operation fails before any business write. */
@RestController
public class ExternalIntegrationController {
    private final ExternalIntegrationBoundary boundary;
    public ExternalIntegrationController(ExternalIntegrationBoundary boundary){this.boundary=boundary;}
    public record WechatLogin(@NotBlank @Size(max=512) String code) {}
    @GetMapping("/api/integrations/status")
    public Map<String,Object> status(){return Map.of("mode","ISOLATED_DEMO","production_ready",false,"services",boundary.capabilities());}
    @PostMapping("/api/auth/wechat")
    public void login(@RequestBody @Valid WechatLogin input){throw boundary.unavailable(WECHAT_IDENTITY);}
    @PostMapping("/api/payments/wechat/prepay")
    public void prepay(){throw boundary.unavailable(WECHAT_PAYMENT);}
    @PostMapping("/api/owner/integrations/wechat/notifications")
    public void notifyAppointment(){throw boundary.unavailable(WECHAT_NOTIFICATION);}
}

@RestControllerAdvice
class ExternalIntegrationErrors {
    @ExceptionHandler(ExternalIntegrationBoundary.Unavailable.class)
    ResponseEntity<?> unavailable(ExternalIntegrationBoundary.Unavailable error){
        return ResponseEntity.status(503).body(Map.of("code","INTEGRATION_NOT_CONFIGURED","feature",error.feature.key,"enabled",false,"message",error.getMessage()));
    }
}
