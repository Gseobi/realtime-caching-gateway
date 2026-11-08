package com.github.gseobi.cachinggateway.domain.crm;

import com.github.gseobi.cachinggateway.common.security.GatewayStorageTenant;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/api/crm/conversations")
public class CrmConversationController {
    private final CrmConversationOwnership ownership;
    private final GatewayStorageTenant tenants;

    public CrmConversationController(CrmConversationOwnership ownership, GatewayStorageTenant tenants) {
        this.ownership = ownership;
        this.tenants = tenants;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Created create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Create request) {
        if (jwt == null) throw new AccessDeniedException("Authentication required");
        return new Created(ownership.create(tenants.resolve(jwt), jwt.getSubject(),
                request.customerId(), request.contactId(), request.participants()));
    }

    public record Create(@NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String customerId,
                         @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String contactId,
                         @NotNull @Size(max = 50) Set<@NotBlank @Size(max = 64)
                                 @Pattern(regexp = "[^\\p{Cntrl}]+") String> participants) {}
    public record Created(String conversationId) {}
}
