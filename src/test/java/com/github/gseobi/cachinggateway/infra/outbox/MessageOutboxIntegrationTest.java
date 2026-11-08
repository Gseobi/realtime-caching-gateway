package com.github.gseobi.cachinggateway.infra.outbox;

import com.github.gseobi.cachinggateway.StorageTenant;
import com.github.gseobi.cachinggateway.domain.message.dto.MessageSaveRequest;
import com.github.gseobi.cachinggateway.domain.message.model.MessageType;
import com.github.gseobi.cachinggateway.domain.message.service.MessageService;
import com.github.gseobi.foundation.identity.RequestContext;
import com.github.gseobi.foundation.identity.TenantId;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class MessageOutboxIntegrationTest {
    @Autowired MessageService messages;
    @Autowired JdbcMessageOutbox outbox;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;

    @Test void messageAndOutboxRollbackTogetherAndReplayDoesNotAppendAgain() {
        var tenant = tenant();
        var request = request();
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            messages.saveMessage(tenant, request, "same");
            status.setRollbackOnly();
        });
        assertThat(count("message_outbox", tenant)).isZero();
        assertThat(count("message", tenant)).isZero();
        messages.saveMessage(tenant, request, "same");
        messages.saveMessage(tenant, request, "same");
        assertThat(count("message_outbox", tenant)).isEqualTo(1);
        assertThat(count("message", tenant)).isEqualTo(1);
    }

    @Test void leaseExpiryFencesOldWorkerAndReplayDeduplicatesInbox() {
        var tenant = tenant();
        var worker = worker(tenant);
        messages.saveMessage(tenant, request());
        var first = outbox.claim(worker, 10, Duration.ofMinutes(1)).getFirst();
        assertThat(outbox.claim(worker, 10, Duration.ofMinutes(1))).isEmpty();
        assertThat(outbox.claim(worker(tenant()), 10, Duration.ofMinutes(1))).isEmpty();
        assertThatThrownBy(() -> outbox.deliverToInbox(worker(tenant()), first)).isInstanceOf(SecurityException.class);
        jdbc.update("UPDATE message_outbox SET lease_until = now() - interval '1 second' WHERE tenant_id = ?", tenant.id());
        var second = outbox.claim(worker, 10, Duration.ofMinutes(1)).getFirst();
        assertThat(second.attempt()).isEqualTo(2);
        assertThat(outbox.deliverToInbox(worker, first)).isFalse();
        assertThat(outbox.deliverToInbox(worker, second)).isTrue();
        jdbc.update("UPDATE message_outbox SET delivered_at = NULL WHERE tenant_id = ?", tenant.id());
        var replay = outbox.claim(worker, 10, Duration.ofMinutes(1)).getFirst();
        assertThat(outbox.deliverToInbox(worker, replay)).isTrue();
        assertThat(count("message_event_inbox", tenant)).isEqualTo(1);
    }

    @Test void retryDefersClaimUntilDue() {
        var tenant = tenant();
        var worker = worker(tenant);
        messages.saveMessage(tenant, request());
        var first = outbox.claim(worker, 1, Duration.ofMinutes(1)).getFirst();
        assertThat(outbox.retry(worker, first, Instant.now().plusSeconds(60), "simulated")).isTrue();
        assertThat(outbox.complete(worker, first)).isFalse();
        assertThat(outbox.claim(worker, 1, Duration.ofMinutes(1))).isEmpty();
        jdbc.update("UPDATE message_outbox SET available_at = now() WHERE tenant_id = ?", tenant.id());
        var retry = outbox.claim(worker, 1, Duration.ofMinutes(1)).getFirst();
        assertThat(retry.event().eventId()).isEqualTo(first.event().eventId());
        assertThat(outbox.deliverToInbox(worker, retry)).isTrue();
    }

    private int count(String table, StorageTenant tenant) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE tenant_id = ?", Integer.class, tenant.id());
    }

    private static StorageTenant tenant() {
        return new StorageTenant(UUID.randomUUID().toString());
    }

    private static RequestContext worker(StorageTenant tenant) {
        return new RequestContext(new TenantId(tenant.id()), "test-worker", "test-request");
    }

    private static MessageSaveRequest request() {
        var request = new MessageSaveRequest();
        request.setMessageId(UUID.randomUUID().toString());
        request.setConversationId(UUID.randomUUID().toString());
        request.setSenderId("test-user");
        request.setMessageType(MessageType.TEXT);
        return request;
    }
}
