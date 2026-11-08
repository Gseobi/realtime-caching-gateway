package com.github.gseobi.cachinggateway.infra.outbox;

import com.github.gseobi.cachinggateway.StorageTenant;
import com.github.gseobi.foundation.identity.RequestContext;
import com.github.gseobi.foundation.identity.TenantId;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "gateway.outbox.relay.enabled", havingValue = "true")
public class MessageOutboxRelay {
    private static final Logger log = LoggerFactory.getLogger(MessageOutboxRelay.class);
    private final JdbcMessageOutbox outbox;
    private final List<TenantId> tenants;

    public MessageOutboxRelay(JdbcMessageOutbox outbox, @Value("${gateway.outbox.relay.tenants}") String tenantList) {
        this.outbox = outbox;
        tenants = Arrays.stream(tenantList.split(",", -1)).map(String::trim)
                .map(StorageTenant::new).map(tenant -> new TenantId(tenant.id())).distinct().toList();
    }

    @Scheduled(fixedDelayString = "${gateway.outbox.relay.delay-ms:1000}")
    public void relay() {
        for (TenantId tenant : tenants) {
            var worker = new RequestContext(tenant, "message-outbox-relay", UUID.randomUUID().toString());
            try {
                for (var lease : outbox.claim(worker, 20, Duration.ofMinutes(1))) {
                    try {
                        outbox.deliverToInbox(worker, lease);
                    } catch (RuntimeException ex) {
                        long seconds = 1L << Math.min(lease.attempt(), 8);
                        outbox.retry(worker, lease, Instant.now().plusSeconds(seconds), "inbox_write_failed");
                        log.warn("Outbox delivery failed for event {}", lease.event().eventId(), ex);
                    }
                }
            } catch (RuntimeException ex) {
                // Lease expiry allows recovery if even recording the retry fails.
                log.warn("Outbox relay failed for tenant {}", tenant.value(), ex);
            }
        }
    }
}
