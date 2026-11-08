package com.github.gseobi.cachinggateway.domain.message.controller;

import com.github.gseobi.cachinggateway.domain.message.dto.MessageQueryRequest;
import com.github.gseobi.cachinggateway.domain.message.dto.MessageResponse;
import com.github.gseobi.cachinggateway.domain.message.dto.MessageSaveRequest;
import com.github.gseobi.cachinggateway.domain.message.service.MessageService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import com.github.gseobi.cachinggateway.domain.crm.CrmConversationOwnership;
import com.github.gseobi.cachinggateway.common.security.GatewayStorageTenant;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Base64;
import java.time.LocalDateTime;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;

@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/api/conversations/{conversationId}/messages")
public class MessageController {

    private final MessageService messageService;

    private final GatewayStorageTenant storageTenant;
    private final CrmConversationOwnership ownership;
    private final ObjectMapper objectMapper;

    @PostMapping
    public ResponseEntity<Void> saveMessage(@NotBlank @PathVariable("conversationId") String conversationId,
                            @Valid @RequestBody MessageSaveRequest messageSaveRequest,
                            @AuthenticationPrincipal Jwt jwt,
                            @RequestHeader(value = "Idempotency-Key", required = false) String retryKey) {
        if (jwt != null && !jwt.getSubject().equals(messageSaveRequest.getSenderId())) {
            return ResponseEntity.status(403).build();
        }
        var tenant = storageTenant.resolve(jwt);
        ownership.requireAccess(tenant, conversationId, jwt == null ? null : jwt.getSubject());
        messageSaveRequest.setConversationId(conversationId);
        if (retryKey != null && !retryKey.matches("[A-Za-z0-9._~-]{1,128}")) {
            return ResponseEntity.badRequest().build();
        }
        this.messageService.saveMessage(tenant, messageSaveRequest, retryKey);
        return ResponseEntity.ok().build();
    }

    @GetMapping
    public List<MessageResponse> getMessages(@PathVariable("conversationId") String conversationId,
                                             @Valid @ModelAttribute MessageQueryRequest messageQueryRequest,
                                             @AuthenticationPrincipal Jwt jwt) {
        var tenant = storageTenant.resolve(jwt);
        ownership.requireAccess(tenant, conversationId, jwt == null ? null : jwt.getSubject());
        return this.messageService.getMessages(tenant, conversationId, messageQueryRequest);
    }
    @GetMapping("/history")
    public ResponseEntity<HistoryPage> history(@PathVariable("conversationId") String conversationId,
                                               @RequestParam(name = "limit", defaultValue = "50") String size,
                                               @RequestParam(name = "cursor", required = false) String token,
                                               @AuthenticationPrincipal Jwt jwt) throws JsonProcessingException {
        var tenant = storageTenant.resolve(jwt);
        ownership.requireAccess(tenant, conversationId, jwt == null ? null : jwt.getSubject());
        int limit;
        LocalDateTime sentAt = null;
        String messageId = null;
        try {
            limit = Integer.parseInt(size);
            if (limit < 1 || limit > 200) return ResponseEntity.badRequest().build();
            if (token != null) {
                if (!token.startsWith("v1.") || token.length() > 2048) return ResponseEntity.badRequest().build();
                var cursor = objectMapper.readValue(Base64.getUrlDecoder().decode(token.substring(3)), Cursor.class);
                if (cursor == null || !tenant.id().equals(cursor.tenant()) || !conversationId.equals(cursor.conversation())
                        || cursor.sentAt() == null || cursor.id() == null || cursor.id().isEmpty() || cursor.id().length() > 64
                        || cursor.id().indexOf(0) >= 0) {
                    return ResponseEntity.badRequest().build();
                }
                sentAt = LocalDateTime.parse(cursor.sentAt());
                if (sentAt.getYear() < 1 || sentAt.getYear() > 9999) return ResponseEntity.badRequest().build();
                messageId = cursor.id();
            }
        } catch (IllegalArgumentException | java.time.DateTimeException | java.io.IOException e) {
            return ResponseEntity.badRequest().build();
        }

        var result = messageService.getHistory(tenant, conversationId, sentAt, messageId, limit + 1);
        if (result.size() <= limit) return ResponseEntity.ok(new HistoryPage(result, null));
        var last = result.get(limit - 1);
        String next = "v1." + Base64.getUrlEncoder().withoutPadding().encodeToString(objectMapper.writeValueAsBytes(
                new Cursor(tenant.id(), conversationId, last.getSentAt().toString(), last.getMessageId())));
        return ResponseEntity.ok(new HistoryPage(result.subList(0, limit), next));
    }

    public record HistoryPage(List<MessageResponse> messages, String nextCursor) {}
    private record Cursor(String tenant, String conversation, String sentAt, String id) {}
}
