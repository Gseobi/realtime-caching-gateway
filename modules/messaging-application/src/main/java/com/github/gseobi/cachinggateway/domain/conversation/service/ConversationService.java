package com.github.gseobi.cachinggateway.domain.conversation.service;

import com.github.gseobi.cachinggateway.common.exception.ResourceNotFoundException;
import com.github.gseobi.cachinggateway.domain.conversation.dto.ConversationMetaResponse;
import com.github.gseobi.cachinggateway.domain.conversation.model.ConversationMetaCacheData;
import com.github.gseobi.cachinggateway.port.ConversationStateReader;
import com.github.gseobi.cachinggateway.port.MessageCacheRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ConversationService {

    private final MessageCacheRepository messageCacheRepository;
    private final ConversationStateReader conversationStateReader;

    public ConversationMetaResponse getConversationMeta(String conversationId) {
        // Mutable state always comes from the monotonic DB projection.
        ConversationMetaCacheData dbMeta = conversationStateReader.findConversationMeta(conversationId);
        if (dbMeta != null) {
            try {
                messageCacheRepository.saveConversationMeta(dbMeta);
            } catch (RuntimeException ex) {
                // Cache availability does not decide whether durable state is readable.
            }
            return toResponse(dbMeta);
        }

        throw new ResourceNotFoundException("conversation meta not found. conversationId=" + conversationId);
    }

    private ConversationMetaResponse toResponse(ConversationMetaCacheData data) {
        return ConversationMetaResponse.builder()
                .conversationId(data.getConversationId())
                .lastMessageId(data.getLastMessageId())
                .lastMessagePreview(data.getLastMessagePreview())
                .lastSenderId(data.getLastSenderId())
                .lastSentAt(data.getLastSentAt())
                .updatedAt(data.getUpdatedAt())
                .build();
    }
}
