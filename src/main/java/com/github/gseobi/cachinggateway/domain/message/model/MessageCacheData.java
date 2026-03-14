package com.github.gseobi.cachinggateway.domain.message.model;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@Builder
public class MessageCacheData {
    private String messageId;
    private String conversationId;
    private String senderId;
    private MessageType messageType;
    private String content;
    private String metadataJson;
    private LocalDateTime sentAt;
}
