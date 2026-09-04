package com.pm.patientservice.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Sends each transaction to the primary or a read replica.
 *
 * <p>The routing key is simply whether the current transaction is <b>read-only</b>: writes (and any
 * read that is part of a write transaction) must see the authoritative data, so they go to the
 * primary; a {@code @Transactional(readOnly = true)} method can tolerate a replica.
 *
 * <p>This makes {@code @Transactional(readOnly = true)} <b>load-bearing</b>. It used to be a mild
 * Hibernate hint (skip dirty-checking); now it decides which database answers. A read method that
 * forgets it silently keeps hitting the primary — no error, just no scaling.
 *
 * <p><b>Default is primary, never replica.</b> If routing is ever ambiguous we want to be correct and
 * slow, not fast and stale.
 */
public class RoutingDataSource extends AbstractRoutingDataSource {

    private static final Logger log = LoggerFactory.getLogger(RoutingDataSource.class);

    public static final String PRIMARY = "primary";
    public static final String REPLICA = "replica";

    @Override
    protected Object determineCurrentLookupKey() {
        String target = TransactionSynchronizationManager.isCurrentTransactionReadOnly() ? REPLICA : PRIMARY;
        log.debug("Routing this transaction to the {} datasource", target);
        return target;
    }
}
