package com.github.gseobi.cachinggateway.domain.conversation.controller;

import com.github.gseobi.cachinggateway.domain.conversation.dto.ConversationMetaResponse;
import com.github.gseobi.cachinggateway.domain.conversation.service.ConversationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/conversations/{conversationId}/meta")
public class ConversationController {

    private final ConversationService conversationService;

    @GetMapping
    public ConversationMetaResponse getConversationMeta(@PathVariable("conversationId") String conversationId) {
        return this.conversationService.getConversationMeta(conversationId);
    }
}
