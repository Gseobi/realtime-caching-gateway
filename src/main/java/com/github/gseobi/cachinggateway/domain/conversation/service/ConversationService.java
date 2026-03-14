package com.github.gseobi.cachinggateway.domain.conversation.service;

import com.github.gseobi.cachinggateway.common.exception.ResourceNotFoundException;
import com.github.gseobi.cachinggateway.domain.conversation.dto.ConversationMetaResponse;
import com.github.gseobi.cachinggateway.domain.conversation.model.ConversationMetaCacheData;
import com.github.gseobi.cachinggateway.infra.persistence.mybatis.ConversationStateMapper;
import com.github.gseobi.cachinggateway.infra.redis.repository.MessageCacheRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ConversationService {

    private final MessageCacheRepository messageCacheRepository;
    private final ConversationStateMapper conversationStateMapper;

    public ConversationMetaResponse getConversationMeta(String conversationId) {
        ConversationMetaCacheData cached = this.messageCacheRepository.findConversationMeta(conversationId);
        if (cached != null) {
            return toResponse(cached);
        }

        ConversationMetaCacheData dbMeta = this.conversationStateMapper.findConversationMeta(conversationId);
        if (dbMeta != null) {
            this.messageCacheRepository.saveConversationMeta(dbMeta);
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
