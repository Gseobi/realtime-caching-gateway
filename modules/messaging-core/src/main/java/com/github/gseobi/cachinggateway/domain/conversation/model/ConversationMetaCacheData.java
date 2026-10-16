package com.github.gseobi.cachinggateway.domain.conversation.model;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@Builder
public class ConversationMetaCacheData {
    private String conversationId;
    private String lastMessageId;
    private String lastMessagePreview;
    private String lastSenderId;
    private LocalDateTime lastSentAt;
    private LocalDateTime updatedAt;
}
