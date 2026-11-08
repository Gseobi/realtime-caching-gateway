package com.github.gseobi.cachinggateway.domain.knowledge;

import com.github.gseobi.cachinggateway.common.exception.ResourceNotFoundException;
import com.github.gseobi.cachinggateway.common.security.GatewayStorageTenant;
import com.github.gseobi.foundation.identity.RequestContext;
import com.github.gseobi.foundation.identity.TenantId;
import com.github.gseobi.knowledge.contract.LocalKnowledge;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/api/knowledge")
public class LocalKnowledgeController {
    private final GatewayStorageTenant tenants;
    private final LocalKnowledge knowledge;

    public LocalKnowledgeController(GatewayStorageTenant tenants, LocalKnowledge knowledge) {
        this.tenants = tenants;
        this.knowledge = knowledge;
    }

    @PostMapping("/documents")
    @ResponseStatus(HttpStatus.CREATED)
    public LocalKnowledge.Job create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Input input) {
        return knowledge.create(context(jwt), input.document());
    }

    @PutMapping("/documents/{id}")
    public LocalKnowledge.Job replace(@AuthenticationPrincipal Jwt jwt, @PathVariable("id") UUID id,
                                      @Valid @RequestBody Replacement request) {
        return knowledge.replace(context(jwt), id, request.revision(), request.input().document());
    }

    @PostMapping("/documents/{id}/rebuild")
    public LocalKnowledge.Job rebuild(@AuthenticationPrincipal Jwt jwt, @PathVariable("id") UUID id,
                                      @Valid @RequestBody Revision request) {
        return knowledge.rebuild(context(jwt), id, request.revision());
    }

    @PostMapping("/documents/{id}/delete")
    public LocalKnowledge.Job delete(@AuthenticationPrincipal Jwt jwt, @PathVariable("id") UUID id,
                                     @Valid @RequestBody Revision request) {
        return knowledge.delete(context(jwt), id, request.revision());
    }

    @GetMapping("/jobs/{id}")
    public LocalKnowledge.Job job(@AuthenticationPrincipal Jwt jwt, @PathVariable("id") UUID id) {
        return knowledge.job(context(jwt), id).orElseThrow(() -> new ResourceNotFoundException("Job not found"));
    }

    @PostMapping("/search")
    public List<LocalKnowledge.Hit> search(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Query query) {
        return knowledge.search(context(jwt), query.query(), query.limit() == null ? 20 : query.limit());
    }

    private RequestContext context(Jwt jwt) {
        if (jwt == null) throw new AccessDeniedException("Authentication required");
        var tenant = tenants.resolve(jwt);
        if (tenant.id().isEmpty()) throw new AccessDeniedException("Shared tenant mode required");
        return new RequestContext(new TenantId(tenant.id()), jwt.getSubject(), UUID.randomUUID().toString());
    }

    public record Input(@NotBlank @Size(max = 500) @Pattern(regexp = "[^\\x00]*") String source, @NotBlank @Size(max = 40000) @Pattern(regexp = "[^\\x00]*") String text,
                        @NotNull @Size(max = 50) Set<@NotBlank @Size(max = 64) @Pattern(regexp = "[^\\x00]*") String> readers) {
        LocalKnowledge.Document document() { return new LocalKnowledge.Document(source, text, readers); }
    }
    public record Replacement(@Positive long revision, @NotNull @Valid Input input) {}
    public record Revision(@Positive long revision) {}
    public record Query(@NotBlank @Size(max = 512) @Pattern(regexp = "[^\\x00]*") String query, @Min(1) @Max(100) Integer limit) {}
}
