package com.pm.patientservice.config;

import com.zaxxer.hikari.HikariDataSource;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
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

    /** The writable primary — the only place writes and migrations are allowed to go. */
    @Bean
    @FlywayDataSource
    public DataSource primaryDataSource(DataSourceProperties primaryDataSourceProperties) {
        return primaryDataSourceProperties
                .initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();
    }

    @Bean
    @ConfigurationProperties("app.datasource.replica")
    public DataSourceProperties replicaDataSourceProperties() {
        return new DataSourceProperties();
    }

    /** A read replica. Defaults to the primary's URL so a single-database setup still works. */
    @Bean
    public DataSource replicaDataSource(
            @Qualifier("replicaDataSourceProperties") DataSourceProperties replicaDataSourceProperties) {
        return replicaDataSourceProperties
                .initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();
    }

    /**
     * The DataSource everything else (JPA) actually uses: a lazy proxy over the router, so the
     * primary/replica decision happens once the transaction's read-only flag is known.
     */
    @Bean
    @Primary
    public DataSource dataSource(
            @Qualifier("primaryDataSource") DataSource primaryDataSource,
            @Qualifier("replicaDataSource") DataSource replicaDataSource) {

        RoutingDataSource routing = new RoutingDataSource();
        routing.setTargetDataSources(Map.of(
                RoutingDataSource.PRIMARY, primaryDataSource,
                RoutingDataSource.REPLICA, replicaDataSource));
        routing.setDefaultTargetDataSource(primaryDataSource); // safe default: correct over fast
        routing.afterPropertiesSet();

        return new LazyConnectionDataSourceProxy(routing);
    }
}
