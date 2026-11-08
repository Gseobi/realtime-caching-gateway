package com.github.gseobi.cachinggateway.domain.message.service;

import com.github.gseobi.cachinggateway.StorageTenant;

import com.github.gseobi.cachinggateway.domain.message.dto.MessageQueryRequest;
import com.github.gseobi.cachinggateway.domain.message.dto.MessageResponse;
import com.github.gseobi.cachinggateway.domain.message.dto.MessageSaveRequest;
import com.github.gseobi.cachinggateway.domain.message.model.MessageCacheData;
import com.github.gseobi.cachinggateway.port.MessageStore;
import com.github.gseobi.cachinggateway.port.MessageEventPublisher;
import com.github.gseobi.cachinggateway.port.MessageCacheRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class MessageService {

    private final MessageCacheRepository messageCacheRepository;
    private final MessageStore messageStore;
    private final MessageEventPublisher messagePublisher;
    private final MessagePersistenceService persistence;

    public void saveMessage(StorageTenant tenant, MessageSaveRequest messageSaveRequest) {
        saveMessage(tenant, messageSaveRequest, null);
    }

    public void saveMessage(StorageTenant tenant, MessageSaveRequest messageSaveRequest, String retryKey) {
        if (retryKey != null && !retryKey.matches("[A-Za-z0-9._~-]{1,128}")) {
            throw new IllegalArgumentException("Invalid Idempotency-Key");
        }
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

        String fingerprint = retryKey == null ? null : fingerprint(messageSaveRequest);
        if (!persistence.persist(tenant, message, preview(message.getContent(), message.getMessageType().name()),
                retryKey, fingerprint)) return;
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            projectCommittedMessage(tenant, message);
        } else if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                private final Set<Object> laterSavepoints =
                        Collections.newSetFromMap(new IdentityHashMap<>());
                private boolean discarded;

                @Override
                public void savepoint(Object savepoint) {
                    laterSavepoints.add(savepoint);
                }

                @Override
                public void savepointRollback(Object savepoint) {
                    // An older savepoint can undo this write, even if the outer transaction commits.
                    if (!laterSavepoints.contains(savepoint)) discarded = true;
                }

                @Override
                public void afterCommit() {
                    if (!discarded) projectCommittedMessage(tenant, message);
                }
            });
        } else {
            log.warn("Skipping projection without transaction synchronization. messageId={}", message.getMessageId());
        }
    }

    private void projectCommittedMessage(StorageTenant tenant, MessageCacheData message) {
        try {
            messageCacheRepository.saveMessage(tenant, message);
        } catch (RuntimeException ex) {
            log.warn("Cache projection failed after durable commit. messageId={}", message.getMessageId(), ex);
        }
        try {
            messagePublisher.publish(tenant, message);
        } catch (RuntimeException ex) {
            // Pub/Sub is best effort; clients recover committed messages through GET.
            log.warn("Live publication failed after durable commit. messageId={}", message.getMessageId(), ex);
        }
    }

    public List<MessageResponse> getMessages(StorageTenant tenant, String conversationId, MessageQueryRequest query) {
        int limit = query.getLimit() == null ? 50 : query.getLimit();
        // A transaction can read its own uncommitted writes. Keep its snapshot out of shared cache.
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            return messageStore.findMessages(tenant, conversationId, query.getBefore(), query.getAfter(), limit)
                    .stream().map(this::toResponse).toList();
        }
        // The DB owns membership and ordering: a partly warmed index is not a complete page.
        List<String> ids = messageStore.findMessageIds(tenant, conversationId, query.getBefore(), query.getAfter(), limit);
        if (ids.isEmpty()) return List.of();
        try {
            List<MessageCacheData> cached = messageCacheRepository.findMessagesByIds(tenant, ids);
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
        List<MessageCacheData> durable = messageStore.findMessages(tenant, conversationId, query.getBefore(), query.getAfter(), limit);
        try {
            messageCacheRepository.refreshMessages(tenant, conversationId, durable);
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
    public List<MessageResponse> getHistory(StorageTenant tenant, String conversationId, LocalDateTime sentAt,
                                            String messageId, int limit) {
        return messageStore.findHistory(tenant, conversationId, sentAt, messageId, limit)
                .stream().map(this::toResponse).toList();
    }

    private static String fingerprint(MessageSaveRequest request) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            for (String field : new String[]{"v1", request.getConversationId(), request.getMessageId(),
                    request.getSenderId(), request.getMessageType().name(), request.getContent(),
                    request.getMetadataJson(), request.getSentAt() == null ? null : request.getSentAt().toString()}) {
                byte[] bytes = field == null ? null : field.getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(4).putInt(bytes == null ? -1 : bytes.length).array());
                if (bytes != null) digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
