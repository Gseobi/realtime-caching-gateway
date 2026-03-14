package com.github.gseobi.cachinggateway.infra.redis.repository;

import com.github.gseobi.cachinggateway.domain.conversation.model.ConversationMetaCacheData;
import com.github.gseobi.cachinggateway.domain.message.model.MessageCacheData;
import com.github.gseobi.cachinggateway.domain.message.model.MessageType;
import com.github.gseobi.cachinggateway.infra.redis.key.RedisKeyPolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;

@Repository
@RequiredArgsConstructor
public class MessageCacheRedisRepository implements MessageCacheRepository {

    private final StringRedisTemplate redisTemplate;

    @Value("${gateway.redis.message-ttl-seconds}")
    private long messageTtlSeconds;

    @Value("${gateway.redis.conversation-meta-ttl-seconds}")
    private long conversationMetaTtlSeconds;

    @Override
    public void saveMessage(MessageCacheData data) {
        String messageKey = RedisKeyPolicy.message(data.getMessageId());
        String indexKey = RedisKeyPolicy.conversationMessageIndex(data.getConversationId());

        Map<String, String> hashData = new HashMap<>();
        hashData.put("messageId", data.getMessageId());
        hashData.put("conversationId", data.getConversationId());
        hashData.put("senderId", data.getSenderId());
        hashData.put("messageType", data.getMessageType().name());
        hashData.put("content", data.getContent() == null ? "" : data.getContent());
        hashData.put("metadataJson", data.getMetadataJson() == null ? "" : data.getMetadataJson());
        hashData.put("sentAt", data.getSentAt().toString());

        double score = toEpochMillis(data.getSentAt());

        this.redisTemplate.opsForHash().putAll(messageKey, hashData);
        this.redisTemplate.expire(messageKey, Duration.ofSeconds(messageTtlSeconds));
        this.redisTemplate.opsForZSet().add(indexKey, data.getMessageId(), score);
    }

    @Override
    public void saveConversationMeta(ConversationMetaCacheData meta) {
        String key = RedisKeyPolicy.conversationMeta(meta.getConversationId());

        Map<String, String> hashMeta = new HashMap<>();
        hashMeta.put("conversationId", meta.getConversationId());
        hashMeta.put("lastMessageId", empty(meta.getLastMessageId()));
        hashMeta.put("lastMessagePreview", empty(meta.getLastMessagePreview()));
        hashMeta.put("lastSenderId", empty(meta.getLastSenderId()));
        hashMeta.put("lastSentAt", meta.getLastSentAt() == null ? "" : meta.getLastSentAt().toString());
        hashMeta.put("updatedAt", meta.getUpdatedAt() == null ? "" : meta.getUpdatedAt().toString());

        this.redisTemplate.opsForHash().putAll(key, hashMeta);
        this.redisTemplate.expire(key, Duration.ofSeconds(conversationMetaTtlSeconds));
    }

    @Override
    public List<String> findMessageIds(String conversationId, LocalDateTime before, LocalDateTime after, int limit) {
        String indexKey = RedisKeyPolicy.conversationMessageIndex(conversationId);

        double minimum = after == null ? Double.NEGATIVE_INFINITY : toEpochMillis(after);
        double maximum = before == null ? Double.NEGATIVE_INFINITY : toEpochMillis(before);

        Set<String> ids = redisTemplate.opsForZSet().reverseRangeByScore(indexKey, minimum, maximum, 0, limit);

        if (ids == null || ids.isEmpty()) {
            return Collections.emptyList();
        }

        return new ArrayList<>(ids);
    }

    @Override
    public List<MessageCacheData> findMessagesByIds(List<String> messageIds) {
        if (messageIds == null || messageIds.isEmpty()) {
            return Collections.emptyList();
        }

        List<MessageCacheData> result = new ArrayList<>();
        for (String id : messageIds) {
            Map<Object, Object> raw = redisTemplate.opsForHash().entries(RedisKeyPolicy.message(id));
            if (raw == null || raw.isEmpty()) continue;
            result.add(toMessage(raw));
        }
        return result;
    }

    @Override
    public List<MessageCacheData> findMessages(String conversationId, LocalDateTime before, LocalDateTime after, int limit) {
        List<String> ids = findMessageIds(conversationId, before, after, limit);
        return findMessagesByIds(ids);
    }

    @Override
    public void refreshMessages(String conversationId, List<MessageCacheData> messages) {
        for (MessageCacheData message : messages) {
            saveMessage(message);
        }
    }

    @Override
    public void markDirty(String conversationId) {
        redisTemplate.opsForSet().add(RedisKeyPolicy.dirtyConversations(), conversationId);
    }

    @Override
    public Set<String> findDirtyConversationIds() {
        Set<String> ids = redisTemplate.opsForSet().members(RedisKeyPolicy.dirtyConversations());
        return ids == null ? Collections.emptySet() : ids;
    }

    @Override
    public void clearDirty(String conversationId) {
        redisTemplate.opsForSet().remove(RedisKeyPolicy.dirtyConversations(), conversationId);
    }

    @Override
    public List<MessageCacheData> findAllCachedMessages(String conversationId) {
        List<String> ids = findMessageIds(conversationId, null, null, Integer.MAX_VALUE);
        return findMessagesByIds(ids);
    }

    @Override
    public ConversationMetaCacheData findConversationMeta(String conversationId) {
        Map<Object, Object> raw = redisTemplate.opsForHash().entries(RedisKeyPolicy.conversationMeta(conversationId));
        if (raw == null || raw.isEmpty()) return null;

        return ConversationMetaCacheData.builder()
                .conversationId(str(raw.get("conversationId")))
                .lastMessageId(str(raw.get("lastMessageId")))
                .lastMessagePreview(str(raw.get("lastMessagePreview")))
                .lastSenderId(str(raw.get("lastSenderId")))
                .lastSentAt(parseDateTime(str(raw.get("lastSentAt"))))
                .updatedAt(parseDateTime(str(raw.get("updatedAt"))))
                .build();
    }

    private MessageCacheData toMessage(Map<Object, Object> raw) {
        return MessageCacheData.builder()
                .messageId(str(raw.get("messageId")))
                .conversationId(str(raw.get("conversationId")))
                .senderId(str(raw.get("senderId")))
                .messageType(MessageType.valueOf(str(raw.get("messageType"))))
                .content(str(raw.get("content")))
                .metadataJson(str(raw.get("metadataJson")))
                .sentAt(LocalDateTime.parse(str(raw.get("sentAt"))))
                .build();
    }

    private double toEpochMillis(LocalDateTime time) {
        return time.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    private LocalDateTime parseDateTime(String value) {
        return (value == null || value.isBlank()) ? null : LocalDateTime.parse(value);
    }
    private String empty(String value) {
        return value == null ? "" : value;
    }

    private String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
