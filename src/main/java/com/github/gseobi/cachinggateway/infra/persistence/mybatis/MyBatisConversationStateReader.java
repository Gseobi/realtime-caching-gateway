package com.github.gseobi.cachinggateway.infra.persistence.mybatis;

import com.github.gseobi.cachinggateway.domain.conversation.model.ConversationMetaCacheData;
import com.github.gseobi.cachinggateway.port.ConversationStateReader;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class MyBatisConversationStateReader implements ConversationStateReader {
    private final ConversationStateMapper mapper;

    @Override
    public ConversationMetaCacheData findConversationMeta(String conversationId) {
        return mapper.findConversationMeta(conversationId);
    }
}
