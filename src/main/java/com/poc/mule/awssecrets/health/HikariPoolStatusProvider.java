package com.poc.mule.awssecrets.health;

import java.util.LinkedHashMap;
import java.util.Map;

import com.poc.mule.awssecrets.jdbc.TtlConfiguredPostgreSQLDriver;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;

/**
 * Read-only view of the HikariCP pool for the {@code /health} and {@code /db-test} endpoints.
 *
 * <p>Wired as a Spring bean around the existing {@code hikariDataSource} bean (see
 * {@code src/main/resources/spring-beans.xml}) and invoked from the Mule flows through the Java
 * Module, so the introspection lives in one place instead of being spread across DataWeave.</p>
 *
 * <p>This class only reads: it never opens a connection, never triggers pool initialization and
 * never mutates pool configuration. Calling {@code /health} therefore does not touch the
 * database.</p>
 *
 * <p>Exposure is an explicit whitelist of operational settings. The JDBC URL, the Secrets Manager
 * secret id (carried as the Hikari "username"), the resolved database credentials, any AWS
 * credentials and the raw environment are deliberately absent.</p>
 */
public final class HikariPoolStatusProvider {

    private final HikariDataSource dataSource;

    public HikariPoolStatusProvider(HikariDataSource dataSource) {
        if (dataSource == null) {
            throw new IllegalArgumentException("dataSource is required.");
        }
        this.dataSource = dataSource;
    }

    /**
     * Returns the current pool state plus the effective pool configuration, shaped as the
     * {@code pool} object of the HTTP responses.
     *
     * <p>Never throws. While the pool is still starting up, or once the data source has been
     * closed, there is no {@link HikariPoolMXBean}; the four runtime counters are then reported as
     * {@code null} rather than as zeros, so "not available yet" stays distinguishable from "no
     * connections". The configuration block remains readable in both cases.</p>
     */
    public Map<String, Object> status() {
        Map<String, Object> pool = new LinkedHashMap<>();
        pool.put("name", dataSource.getPoolName());

        HikariPoolMXBean mxBean = poolMxBean();
        pool.put("total", mxBean == null ? null : mxBean.getTotalConnections());
        pool.put("active", mxBean == null ? null : mxBean.getActiveConnections());
        pool.put("idle", mxBean == null ? null : mxBean.getIdleConnections());
        pool.put("waiting", mxBean == null ? null : mxBean.getThreadsAwaitingConnection());
        pool.put("config", config());

        return pool;
    }

    /** {@code null} until the pool has been created, and again after the data source is closed. */
    private HikariPoolMXBean poolMxBean() {
        try {
            return dataSource.getHikariPoolMXBean();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private Map<String, Object> config() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("maximumPoolSize", dataSource.getMaximumPoolSize());
        config.put("minimumIdle", dataSource.getMinimumIdle());
        config.put("maxLifetimeMs", dataSource.getMaxLifetime());
        config.put("connectionTimeoutMs", dataSource.getConnectionTimeout());
        config.put("validationTimeoutMs", dataSource.getValidationTimeout());
        // Reported as 0 by Hikari when idle eviction is disabled, which is this PoC's setup
        // (minimumIdle == maximumPoolSize).
        config.put("idleTimeoutMs", dataSource.getIdleTimeout());
        config.put("secretCacheTtlSeconds", secretCacheTtlSeconds());
        return config;
    }

    /**
     * Resolves the secret cache TTL the same way {@link TtlConfiguredPostgreSQLDriver} does:
     * environment variable first, then system property, then the driver's default.
     *
     * <p>Both constants below are compile-time constants, so reading them does not load or
     * initialize the driver class from this thread.</p>
     *
     * @return the TTL in seconds, or {@code null} if the configured value is not a number, which is
     *         a state the driver itself rejects at startup.
     */
    private Long secretCacheTtlSeconds() {
        String raw = System.getenv(TtlConfiguredPostgreSQLDriver.TTL_VARIABLE);
        if (raw == null || raw.trim().isEmpty()) {
            raw = System.getProperty(TtlConfiguredPostgreSQLDriver.TTL_VARIABLE);
        }
        if (raw == null || raw.trim().isEmpty()) {
            return TtlConfiguredPostgreSQLDriver.DEFAULT_TTL_SECONDS;
        }
        try {
            return Long.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
