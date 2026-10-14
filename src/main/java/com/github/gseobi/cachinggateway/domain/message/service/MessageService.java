package com.github.gseobi.cachinggateway.domain.message.service;

import com.github.gseobi.cachinggateway.domain.message.dto.MessageQueryRequest;
import com.github.gseobi.cachinggateway.domain.message.dto.MessageResponse;
import com.github.gseobi.cachinggateway.domain.message.dto.MessageSaveRequest;
import com.github.gseobi.cachinggateway.domain.message.model.MessageCacheData;
import com.github.gseobi.cachinggateway.infra.persistence.mybatis.MessageMapper;
import com.github.gseobi.cachinggateway.infra.redis.publisher.MessagePublisher;
import com.github.gseobi.cachinggateway.infra.redis.repository.MessageCacheRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class MessageService {

    private final MessageCacheRepository messageCacheRepository;
    private final MessageMapper messageMapper;
    private final MessagePublisher messagePublisher;
    private final MessagePersistenceService persistence;

    public void saveMessage(MessageSaveRequest messageSaveRequest) {
        LocalDateTime sentAt = messageSaveRequest.getSentAt() == null ? LocalDateTime.now() : messageSaveRequest.getSentAt();

        MessageCacheData message = MessageCacheData.builder()
                .messageId(messageSaveRequest.getMessageId())
                .conversationId(messageSaveRequest.getConversationId())
                .senderId(messageSaveRequest.getSenderId())
                .messageType(messageSaveRequest.getMessageType())
                .content(messageSaveRequest.getContent())
                .metadataJson(messageSaveRequest.getMetadataJson())
                .sentAt(sentAt)
                .build();

        // This call returns only after the message and conversation state commit.
        persistence.persist(message, preview(message.getContent(), message.getMessageType().name()));
        try {
            messageCacheRepository.saveMessage(message);
        } catch (RuntimeException ex) {
            log.warn("Cache projection failed after durable commit. messageId={}", message.getMessageId(), ex);
        }
        try {
            messagePublisher.publish(message);
        } catch (RuntimeException ex) {
            // Pub/Sub is best effort; clients recover committed messages through GET.
            log.warn("Live publication failed after durable commit. messageId={}", message.getMessageId(), ex);
        }
    }

    public List<MessageResponse> getMessages(String conversationId, MessageQueryRequest query) {
        int limit = query.getLimit() == null ? 50 : query.getLimit();
        // The DB owns membership and ordering: a partly warmed index is not a complete page.
        List<String> ids = messageMapper.findMessageIds(conversationId, query.getBefore(), query.getAfter(), limit);
        if (ids.isEmpty()) return List.of();
        try {
            List<MessageCacheData> cached = messageCacheRepository.findMessagesByIds(ids);
            if (cached.size() == ids.size()) {
                java.util.Map<String, MessageCacheData> byId = cached.stream().collect(
                    java.util.stream.Collectors.toMap(MessageCacheData::getMessageId, m -> m));
                if (ids.stream().allMatch(id -> byId.containsKey(id)
                        && conversationId.equals(byId.get(id).getConversationId()))) {
                    return ids.stream().map(byId::get).map(this::toResponse).toList();
                }
            }
        } catch (RuntimeException ex) {
            log.warn("Cache read failed; using durable messages. conversationId={}", conversationId, ex);
        }
        List<MessageCacheData> durable = messageMapper.findMessages(
            conversationId, query.getBefore(), query.getAfter(), limit);
        try {
            messageCacheRepository.refreshMessages(conversationId, durable);
        } catch (RuntimeException ex) {
            log.warn("Cache refresh failed. conversationId={}", conversationId, ex);
        }
        return durable.stream().map(this::toResponse).toList();
    }

    private MessageResponse toResponse(MessageCacheData data) {
        return MessageResponse.builder()
                .messageId(data.getMessageId())
                .conversationId(data.getConversationId())
                .senderId(data.getSenderId())
                .messageType(data.getMessageType())
                .content(data.getContent())
                .metadataJson(data.getMetadataJson())
                .sentAt(data.getSentAt())
                .build();
    }

    private String preview(String content, String type) {
        if ("TEXT".equals(type) && content != null) {
            return content.length() > 30 ? content.substring(0, 30) : content;
        }
        return "[" + type + "]";
    }
}
