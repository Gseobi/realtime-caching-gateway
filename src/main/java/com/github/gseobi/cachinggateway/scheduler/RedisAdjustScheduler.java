package com.github.gseobi.cachinggateway.scheduler;

import com.github.gseobi.cachinggateway.domain.conversation.model.ConversationMetaCacheData;
import com.github.gseobi.cachinggateway.infra.persistence.mybatis.ConversationStateMapper;
import com.github.gseobi.cachinggateway.infra.redis.repository.MessageCacheRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
public class RedisAdjustScheduler {

    private final MessageCacheRepository messageCacheRepository;
    private final ConversationStateMapper conversationStateMapper;

    @Scheduled(cron = "${gateway.sync.adjust-cron}")
    public void adjustConversationMeta() {
        Set<String> dirtyConversationIds = this.messageCacheRepository.findDirtyConversationIds();

        for (String conversationId : dirtyConversationIds) {
            try {
                ConversationMetaCacheData redisMeta = this.messageCacheRepository.findConversationMeta(conversationId);
                if (redisMeta != null) continue;

                ConversationMetaCacheData dbMeta = this.conversationStateMapper.findConversationMeta(conversationId);
                if (dbMeta != null) {
                    this.messageCacheRepository.saveConversationMeta(dbMeta);
                    log.info("adjust conversation meta form DB. conversationId : {}", conversationId);
                }
            } catch (Exception ex) {
                log.error("failed to adjust conversation meta. conversationId : {}", conversationId);
            }
        }
    }
}
