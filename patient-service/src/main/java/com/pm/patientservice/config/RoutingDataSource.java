package com.pm.patientservice.config;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Sends each transaction to the primary or a read replica.
 *
 * <p>The decision, in order:
 *
 * <ol>
 *   <li>{@link ReadFreshness#fromPrimary} was used → <b>primary</b> (this read can't tolerate lag),</li>
 *   <li>the transaction is read-only → <b>replica</b>,</li>
 *   <li>otherwise → <b>primary</b> (it's a write, or we can't tell).</li>
 * </ol>
 *
 * <p>This makes {@code @Transactional(readOnly = true)} <b>load-bearing</b>. It used to be a mild
 * Hibernate hint (skip dirty-checking); now it decides which database answers. A read method that
 * forgets it silently keeps hitting the primary — no error, just no scaling. The
 * {@code patient.datasource.routing} counter is how you notice: if the {@code replica} count stays
 * near zero under read traffic, an annotation is missing (or the lazy proxy isn't in place).
 *
 * <p><b>Default is primary, never replica.</b> If routing is ever ambiguous we want to be correct and
 * slow, not fast and stale.
 */
public class RoutingDataSource extends AbstractRoutingDataSource {

    private static final Logger log = LoggerFactory.getLogger(RoutingDataSource.class);

    public static final String PRIMARY = "primary";
    public static final String REPLICA = "replica";
    private static final String METRIC = "patient.datasource.routing";

    private final MeterRegistry meterRegistry;

    public RoutingDataSource(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @Override
    protected Object determineCurrentLookupKey() {
        String target = resolveTarget();
        meterRegistry.counter(METRIC, "target", target).increment();
        log.debug("Routing this transaction to the {} datasource", target);
        return target;
    }

    private String resolveTarget() {
        if (ReadFreshness.isForcedToPrimary()) {
            return PRIMARY;
        }
        return TransactionSynchronizationManager.isCurrentTransactionReadOnly() ? REPLICA : PRIMARY;
    }
}
