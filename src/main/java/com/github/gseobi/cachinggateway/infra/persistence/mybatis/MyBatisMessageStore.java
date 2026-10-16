package com.github.gseobi.cachinggateway.infra.persistence.mybatis;

import com.github.gseobi.cachinggateway.domain.message.model.MessageCacheData;
import com.github.gseobi.cachinggateway.port.MessageStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import java.time.LocalDateTime;
import java.util.List;

@Repository
@RequiredArgsConstructor
public class MyBatisMessageStore implements MessageStore {
    private final MessageMapper mapper;

    @Override
    public List<MessageCacheData> findMessages(String id, LocalDateTime before, LocalDateTime after, Integer limit) {
        return mapper.findMessages(id, before, after, limit);
    }

    @Override
    public List<String> findMessageIds(String id, LocalDateTime before, LocalDateTime after, Integer limit) {
        return mapper.findMessageIds(id, before, after, limit);
    }

    @Override
    public void insertMessage(MessageCacheData message) { mapper.insertMessage(message); }

    @Override
    public void upsertConversationState(String id, String messageId, String preview, String senderId, LocalDateTime sentAt) {
        mapper.upsertConversationState(id, messageId, preview, senderId, sentAt);
    }
}
