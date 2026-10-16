package com.github.gseobi.crm.contract;

import com.github.gseobi.foundation.identity.RequestContext;
import java.util.Optional;

/** Adapters must authorize the principal, scope SQL by tenant, and enforce optimistic version checks. */
public interface CustomerRepository {
    Optional<Customer> find(RequestContext context, String customerId);
    void save(RequestContext context, Customer customer, long expectedVersion);
}
