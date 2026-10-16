package com.github.gseobi.cachinggateway.message;

import com.github.gseobi.cachinggateway.domain.message.dto.MessageSaveRequest;
import com.github.gseobi.cachinggateway.domain.message.model.MessageType;
import com.github.gseobi.cachinggateway.domain.message.service.MessageService;
import com.github.gseobi.cachinggateway.infra.persistence.mybatis.MessageMapper;
import com.github.gseobi.cachinggateway.infra.persistence.mybatis.ConversationStateMapper;
import com.github.gseobi.cachinggateway.port.MessageCacheRepository;
import com.github.gseobi.cachinggateway.infra.redis.publisher.MessagePublisher;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import java.time.LocalDateTime;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest
@ActiveProfiles("test")
class DurableMessageIntegrationTest {
    @Autowired MessageService service;
    @MockitoSpyBean MessageMapper mapper;
    @Autowired ConversationStateMapper stateMapper;
    @MockitoBean MessageCacheRepository cache;
    @MockitoBean MessagePublisher publisher;

    private MessageSaveRequest request() {
        MessageSaveRequest r = new MessageSaveRequest();
        r.setMessageId("m-" + UUID.randomUUID());
        r.setConversationId("c-" + UUID.randomUUID());
        r.setSenderId("user"); r.setMessageType(MessageType.TEXT);
        r.setContent("durable"); r.setSentAt(LocalDateTime.of(2026, 10, 14, 12, 0));
        return r;
    }

    @Test void acknowledgedMessageSurvivesCompleteCacheLoss() {
        MessageSaveRequest r = request();
        service.saveMessage(r);
        // Cache mock retains nothing: unlike the old fixture, no pre-seeding of DB.
        assertThat(mapper.findMessages(r.getConversationId(), null, null, 50))
            .extracting("messageId").containsExactly(r.getMessageId());
        assertThat(stateMapper.findConversationMeta(r.getConversationId()).getLastMessageId())
            .isEqualTo(r.getMessageId());
    }

    @Test void cacheWriteFailureDoesNotTurnCommittedMessageIntoFailedRequest() {
        MessageSaveRequest r = request();
        doThrow(new IllegalStateException("cache unavailable")).when(cache).saveMessage(any());
        assertThatCode(() -> service.saveMessage(r)).doesNotThrowAnyException();
        assertThat(mapper.findMessages(r.getConversationId(), null, null, 50)).hasSize(1);
    }

    @Test void publicationFailureDoesNotUndoDurableWrite() {
        MessageSaveRequest r = request();
        doThrow(new IllegalStateException("pubsub unavailable")).when(publisher).publish(any());
        assertThatCode(() -> service.saveMessage(r)).doesNotThrowAnyException();
        assertThat(mapper.findMessages(r.getConversationId(), null, null, 50)).hasSize(1);
    }

    @Test void stateWriteFailureRollsBackMessageAndNeverPublishes() {
        MessageSaveRequest r = request();
        doThrow(new IllegalStateException("state unavailable")).when(mapper)
            .upsertConversationState(anyString(), anyString(), anyString(), anyString(), any());
        assertThatThrownBy(() -> service.saveMessage(r)).isInstanceOf(IllegalStateException.class);
        assertThat(mapper.findMessages(r.getConversationId(), null, null, 50)).isEmpty();
        verifyNoInteractions(cache, publisher);
    }

    @Test void missingTimestampIsNormalizedBeforePersistenceAndCaching() {
        MessageSaveRequest r = request(); r.setSentAt(null);
        service.saveMessage(r);
        assertThat(mapper.findMessages(r.getConversationId(), null, null, 50).getFirst().getSentAt())
            .isNotNull();
        verify(cache).saveMessage(argThat(m -> m.getSentAt() != null));
    }

    @Test void lateArrivalCannotMoveConversationStateBackwards() {
        MessageSaveRequest newer = request(); service.saveMessage(newer);
        MessageSaveRequest older = request(); older.setConversationId(newer.getConversationId());
        older.setSentAt(newer.getSentAt().minusMinutes(1)); service.saveMessage(older);
        assertThat(stateMapper.findConversationMeta(newer.getConversationId()).getLastMessageId())
            .isEqualTo(newer.getMessageId());
    }
    @Test void cacheReadAndRefreshFailureStillReturnsDurableMessages() {
        MessageSaveRequest r = request(); service.saveMessage(r);
        doThrow(new IllegalStateException("cache unavailable")).when(cache).findMessagesByIds(anyList());
        doThrow(new IllegalStateException("cache unavailable")).when(cache).refreshMessages(anyString(), anyList());
        assertThat(service.getMessages(r.getConversationId(),
            new com.github.gseobi.cachinggateway.domain.message.dto.MessageQueryRequest()))
            .extracting("messageId").containsExactly(r.getMessageId());
    }

    @Test void databaseInsertFailureNeverWritesCacheOrPublishes() {
        MessageSaveRequest r = request();
        doThrow(new IllegalStateException("DB unavailable")).when(mapper).insertMessage(any());
        assertThatThrownBy(() -> service.saveMessage(r)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(cache, publisher);
    }

    @Test void concurrentArrivalsKeepNewestState() throws Exception {
        MessageSaveRequest newer = request();
        MessageSaveRequest older = request(); older.setConversationId(newer.getConversationId());
        older.setSentAt(newer.getSentAt().minusMinutes(1));
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> { start.await(); service.saveMessage(newer); return null; });
            var z = pool.submit(() -> { start.await(); service.saveMessage(older); return null; });
            start.countDown(); a.get(10, java.util.concurrent.TimeUnit.SECONDS);
            z.get(10, java.util.concurrent.TimeUnit.SECONDS);
        }
        assertThat(mapper.findMessages(newer.getConversationId(), null, null, 50)).hasSize(2);
        assertThat(stateMapper.findConversationMeta(newer.getConversationId()).getLastMessageId())
            .isEqualTo(newer.getMessageId());
    }

}
