package com.github.gseobi.cachinggateway.scheduler;

import com.github.gseobi.cachinggateway.domain.message.model.MessageCacheData;
import com.github.gseobi.cachinggateway.infra.persistence.mybatis.MessageMapper;
import com.github.gseobi.cachinggateway.infra.redis.repository.MessageCacheRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class RedisSyncScheduler {

    private final MessageCacheRepository messageCacheRepository;
    private final MessageMapper messageMapper;

    @Scheduled(
            initialDelayString = "${gateway.sync.initial-delay-ms}",
            fixedDelayString = "${gateway.sync.fixed-delay-ms}"
    )
    public void syncRedisToDb() {
        Set<String> conversationIds = this.messageCacheRepository.findDirtyConversationIds();

        if (conversationIds.isEmpty()) {
            log.debug("No dirty conversations found for sync.");
            return;
        }

        for (String conversationId : conversationIds) {
            try {
                List<MessageCacheData> messages = this.messageCacheRepository.findAllCachedMessages(conversationId);
                if (messages.isEmpty()) {
                    this.messageCacheRepository.clearDirty(conversationId);
                    log.info("No cached messages found. dirty flag cleared. conversationId={}", conversationId);
                    continue;
                }

                this.messageMapper.upsertMessages(messages);

                MessageCacheData latest = messages.stream()
                        .max(Comparator.comparing(MessageCacheData::getSentAt))
                        .orElse(null);

                if (latest != null) {
                    this.messageMapper.upsertConversationState(
                            conversationId,
                            latest.getMessageId(),
                            preview(latest),
                            latest.getSenderId(),
                            latest.getSentAt()
                    );
                }

                this.messageCacheRepository.clearDirty(conversationId);
                log.info("Sync completed. conversationId={}, messageCount={}", conversationId, messages.size());
            } catch (Exception ex) {
                log.error("Failed to sync conversationId={}", conversationId, ex);
            }
        }
    }

    private String preview(MessageCacheData latestMsg) {
        if ("TEXT".equals(latestMsg.getMessageType().name()) && latestMsg.getContent() != null) {
            return latestMsg.getContent().length() > 30 ? latestMsg.getContent().substring(0, 30) : latestMsg.getContent();
        }
        return "[" + latestMsg.getMessageType().name() + "]";
    }
}
