package com.github.gseobi.cachinggateway.domain.conversation.controller;

import com.github.gseobi.cachinggateway.domain.conversation.dto.ConversationMetaResponse;
import com.github.gseobi.cachinggateway.domain.conversation.service.ConversationService;
import lombok.RequiredArgsConstructor;
import com.github.gseobi.cachinggateway.common.security.GatewayStorageTenant;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/api/conversations/{conversationId}/meta")
public class ConversationController {

    private final ConversationService conversationService;

    private final GatewayStorageTenant storageTenant;

    @GetMapping
    public ConversationMetaResponse getConversationMeta(@PathVariable("conversationId") String conversationId,
                                                        @AuthenticationPrincipal Jwt jwt) {
        return this.conversationService.getConversationMeta(storageTenant.resolve(jwt), conversationId);
    }
}
