package com.github.gseobi.cachinggateway.domain.crm;

import com.github.gseobi.cachinggateway.StorageTenant;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CrmConversationOwnership {
    private static final Pattern MANAGED_ID =
            Pattern.compile("crm_[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}");
    private final JdbcTemplate jdbc;

    public CrmConversationOwnership(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public String create(StorageTenant tenant, String agent, String customer, String contact,
                         Set<String> participants) {
        if (tenant.id().isEmpty()) throw new AccessDeniedException("Shared tenant mode required");
        jdbc.update("""
                INSERT INTO crm_customer (tenant_id, customer_id, owner_agent_id)
                VALUES (?, ?, ?) ON CONFLICT DO NOTHING
                """, tenant.id(), customer, agent);
        String owner = jdbc.queryForObject("""
                SELECT owner_agent_id FROM crm_customer WHERE tenant_id = ? AND customer_id = ?
                """, String.class, tenant.id(), customer);
        if (!agent.equals(owner)) throw new AccessDeniedException("Customer belongs to another agent");
        jdbc.update("""
                INSERT INTO crm_contact (tenant_id, contact_id, customer_id)
                VALUES (?, ?, ?) ON CONFLICT DO NOTHING
                """, tenant.id(), contact, customer);
        String contactCustomer = jdbc.queryForObject("""
                SELECT customer_id FROM crm_contact WHERE tenant_id = ? AND contact_id = ?
                """, String.class, tenant.id(), contact);
        if (!customer.equals(contactCustomer)) throw new AccessDeniedException("Contact belongs to another customer");

        String conversation = "crm_" + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO crm_conversation (tenant_id, conversation_id, contact_id, agent_id)
                VALUES (?, ?, ?, ?)
                """, tenant.id(), conversation, contact, agent);
        for (String participant : participants) {
            jdbc.update("""
                    INSERT INTO crm_conversation_participant (tenant_id, conversation_id, principal_id)
                    VALUES (?, ?, ?)
                    """, tenant.id(), conversation, participant);
        }
        return conversation;
    }

    public void requireManagedAccess(StorageTenant tenant, String conversation, String subject) {
        if (tenant.id().isEmpty() || !MANAGED_ID.matcher(conversation).matches()) {
            throw new AccessDeniedException("Managed conversation required");
        }
        requireAccess(tenant, conversation, subject);
    }

    public void requireAccess(StorageTenant tenant, String conversation, String subject) {
        if (tenant.id().isEmpty() || !MANAGED_ID.matcher(conversation).matches()) return;
        boolean allowed = Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM crm_conversation c WHERE c.tenant_id = ? AND c.conversation_id = ?
                    AND (c.agent_id = ? OR EXISTS (
                        SELECT 1 FROM crm_conversation_participant p
                        WHERE p.tenant_id = c.tenant_id AND p.conversation_id = c.conversation_id
                          AND p.principal_id = ?
                    ))
                )
                """, Boolean.class, tenant.id(), conversation, subject, subject));
        if (!allowed) throw new AccessDeniedException("Conversation access denied");
    }
}
