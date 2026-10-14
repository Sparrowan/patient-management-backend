package com.pm.patientservice.config;

import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.flyway.FlywayDataSource;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy;

/**
 * Read/write splitting: writes go to the primary, read-only transactions go to a replica.
 *
 * <p>Reads scale by copying (cache, replicas); writes don't — every write must land on the one
 * authoritative primary. This wires the read half of that: replica reads relieve the primary so it
 * can spend its capacity on writes.
 *
 * <p><b>The two details that make or break this:</b>
 *
 * <ul>
 *   <li>{@link LazyConnectionDataSourceProxy} is <em>mandatory</em>. Spring acquires a connection when
 *       the transaction begins — <em>before</em> it marks the transaction read-only. Without the lazy
 *       proxy the routing key is evaluated too early, always resolves to the default, and every read
 *       silently lands on the primary: no error, replicas idle, and you'd believe it works. The proxy
 *       defers the real {@code getConnection()} until the first statement, by which time the read-only
 *       flag is set.</li>
 *   <li><b>Flyway is pinned to the primary</b> ({@code @FlywayDataSource}). Migrations are writes and
 *       must never be attempted against a read-only replica — the replica gets the schema change
 *       through replication, not by running the migration itself.</li>
 * </ul>
 *
 * <p><b>Local/dev default:</b> the replica properties fall back to the primary's URL, so with a single
 * database everything still works and routing is a no-op. Point {@code REPLICA_DB_URL} at a real
 * replica to actually split the traffic.
 */
@Configuration
public class DataSourceConfig {

    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource")
    public DataSourceProperties primaryDataSourceProperties() {
        return new DataSourceProperties();
    }

    /**
     * The writable primary — the only place writes and migrations are allowed to go.
     *
     * <p><b>Its pool is sized here, and that is not optional.</b> {@code
     * spring.datasource.hikari.*} binds to Boot's <em>auto-configured</em> DataSource, and this
     * service does not have one: read/write splitting replaced it with hand-built beans. So those
     * properties are inert here, and both pools silently ran on Hikari's own defaults — a maximum of
     * 10 and a 30-second acquisition timeout — no matter what the properties file said. A
     * configuration that looks declared but is ignored is worse than one that is obviously absent,
     * which is why {@code ConnectionPoolAndSchedulerConfigTest} asserts the live bean rather than
     * trusting the file.
     */
    @Bean
    @FlywayDataSource
    public DataSource primaryDataSource(
            DataSourceProperties primaryDataSourceProperties,
            @Value("${spring.datasource.hikari.maximum-pool-size:8}") int maximumPoolSize,
            @Value("${spring.datasource.hikari.minimum-idle:2}") int minimumIdle) {
        HikariDataSource dataSource = primaryDataSourceProperties
                .initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();
        dataSource.setMaximumPoolSize(maximumPoolSize);
        dataSource.setMinimumIdle(minimumIdle);
        // Fail a borrow in 3s rather than letting a request hang on an exhausted pool: that turns
        // exhaustion into a fast, attributable error instead of a pile-up. Hikari's default is 30s.
        dataSource.setConnectionTimeout(3000);
        // Recycle well inside MariaDB's wait_timeout so the pool never hands out a dead socket.
        dataSource.setMaxLifetime(600_000);
        dataSource.setPoolName("patient-service-primary");
        return dataSource;
    }

    @Bean
    @ConfigurationProperties("app.datasource.replica")
    public DataSourceProperties replicaDataSourceProperties() {
        return new DataSourceProperties();
    }

    /**
     * A read replica. Defaults to the primary's URL so a single-database setup still works.
     *
     * <p><b>Its pool is configured here, not by {@code spring.datasource.hikari.*}.</b> Those
     * properties bind only to the auto-configured primary; a hand-built {@code DataSource} gets
     * Hikari's own defaults — which is a <em>maximum pool of ten</em>, silently. That matters for the
     * connection ceiling: this service holds two pools per instance, so the fleet limit is
     * {@code replicas × (primary + replica) × max}, and leaving this one undeclared meant an
     * instance quietly reserved 10 replica connections nobody had accounted for.
     *
     * <p>Sized deliberately <b>smaller</b> than the primary's. Reads here are the cheap ones — list
     * endpoints and cache fills, already fronted by Redis — whereas the primary carries every write
     * plus the reads pinned to it for freshness. Equal pools would reserve capacity the replica does
     * not need, at the cost of instances the fleet could otherwise run.
     */
    @Bean
    public DataSource replicaDataSource(
            @Qualifier("replicaDataSourceProperties") DataSourceProperties replicaDataSourceProperties,
            @Value("${app.datasource.replica.pool.maximum-pool-size:6}") int maximumPoolSize,
            @Value("${app.datasource.replica.pool.minimum-idle:1}") int minimumIdle) {
        HikariDataSource dataSource = replicaDataSourceProperties
                .initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();
        dataSource.setMaximumPoolSize(maximumPoolSize);
        dataSource.setMinimumIdle(minimumIdle);
        dataSource.setConnectionTimeout(3000);
        dataSource.setMaxLifetime(600_000);
        dataSource.setPoolName("patient-service-replica");
        return dataSource;
    }

    /**
     * The DataSource everything else (JPA) actually uses: a lazy proxy over the router, so the
     * primary/replica decision happens once the transaction's read-only flag is known.
     */
    @Bean
    @Primary
    public DataSource dataSource(
            @Qualifier("primaryDataSource") DataSource primaryDataSource,
            @Qualifier("replicaDataSource") DataSource replicaDataSource,
            MeterRegistry meterRegistry) {

        RoutingDataSource routing = new RoutingDataSource(meterRegistry);
        routing.setTargetDataSources(Map.of(
                RoutingDataSource.PRIMARY, primaryDataSource,
                RoutingDataSource.REPLICA, replicaDataSource));
        routing.setDefaultTargetDataSource(primaryDataSource); // safe default: correct over fast
        routing.afterPropertiesSet();

        return new LazyConnectionDataSourceProxy(routing);
    }
}
