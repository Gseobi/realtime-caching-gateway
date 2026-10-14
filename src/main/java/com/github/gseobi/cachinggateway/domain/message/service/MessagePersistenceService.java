package com.github.gseobi.cachinggateway.domain.message.service;

import com.github.gseobi.cachinggateway.domain.message.model.MessageCacheData;
import com.github.gseobi.cachinggateway.infra.persistence.mybatis.MessageMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MessagePersistenceService {
    private final MessageMapper mapper;

    @Transactional
    public void persist(MessageCacheData message, String preview) {
        // A message is immutable. Reusing its global ID is rejected by the primary key.
        mapper.insertMessage(message);
        mapper.upsertConversationState(message.getConversationId(), message.getMessageId(),
            preview, message.getSenderId(), message.getSentAt());
    }
}
