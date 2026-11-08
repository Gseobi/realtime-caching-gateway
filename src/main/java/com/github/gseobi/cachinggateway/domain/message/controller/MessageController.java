package com.github.gseobi.cachinggateway.domain.message.controller;

import com.github.gseobi.cachinggateway.domain.message.dto.MessageQueryRequest;
import com.github.gseobi.cachinggateway.domain.message.dto.MessageResponse;
import com.github.gseobi.cachinggateway.domain.message.dto.MessageSaveRequest;
import com.github.gseobi.cachinggateway.domain.message.service.MessageService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import com.github.gseobi.cachinggateway.common.security.GatewayStorageTenant;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/api/conversations/{conversationId}/messages")
public class MessageController {

    private final MessageService messageService;

    private final GatewayStorageTenant storageTenant;

    @PostMapping
    public ResponseEntity<Void> saveMessage(@NotBlank @PathVariable("conversationId") String conversationId,
                            @Valid @RequestBody MessageSaveRequest messageSaveRequest,
                            @AuthenticationPrincipal Jwt jwt) {
        if (jwt != null && !jwt.getSubject().equals(messageSaveRequest.getSenderId())) {
            return ResponseEntity.status(403).build();
        }
        messageSaveRequest.setConversationId(conversationId);
        this.messageService.saveMessage(storageTenant.resolve(jwt), messageSaveRequest);
        return ResponseEntity.ok().build();
    }

    @GetMapping
    public List<MessageResponse> getMessages(@PathVariable("conversationId") String conversationId,
                                             @Valid @ModelAttribute MessageQueryRequest messageQueryRequest,
                                             @AuthenticationPrincipal Jwt jwt) {
        return this.messageService.getMessages(storageTenant.resolve(jwt), conversationId, messageQueryRequest);
    }
}
