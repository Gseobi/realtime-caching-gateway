package com.github.gseobi.cachinggateway.domain.assistant;

import com.github.gseobi.assistant.contract.AssistantResult;
import com.github.gseobi.assistant.contract.AssistantUseCase;
import com.github.gseobi.assistant.contract.ModelInference;
import com.github.gseobi.cachinggateway.common.security.GatewayStorageTenant;
import com.github.gseobi.foundation.identity.RequestContext;
import com.github.gseobi.foundation.identity.TenantId;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@ConditionalOnProperty(name = "gateway.assistant.enabled", havingValue = "true")
@RequestMapping("/v1/api/crm/conversations/{id}/assistant")
public class GatewayAssistantController {
    private final AssistantUseCase assistant;
    private final GatewayStorageTenant tenants;

    public GatewayAssistantController(AssistantUseCase assistant, GatewayStorageTenant tenants) {
        this.assistant = assistant;
        this.tenants = tenants;
    }

    @PostMapping
    public Answer answer(@PathVariable("id") String id, @AuthenticationPrincipal Jwt jwt,
                         @Valid @RequestBody Question input) {
        if (jwt == null || jwt.getExpiresAt() == null) throw new AccessDeniedException("Authentication required");
        var tenant = tenants.resolve(jwt);
        if (tenant.id().isEmpty()) throw new AccessDeniedException("Shared tenant mode required");
        var context = new RequestContext(new TenantId(tenant.id()), jwt.getSubject(), UUID.randomUUID().toString());
        return switch (assistant.answer(context, id, input.question(), jwt.getExpiresAt())) {
            case AssistantResult.Grounded value -> new Answer("GROUNDED", value.reply(), null);
            case AssistantResult.Abstained value -> new Answer("ABSTAINED", null, value.reason());
        };
    }

    public record Question(@NotBlank @Size(max = 512) @Pattern(regexp = "[^\\x00]*") String question) {}
    public record Answer(String status, ModelInference.Reply reply, AssistantResult.Reason reason) {}
}
