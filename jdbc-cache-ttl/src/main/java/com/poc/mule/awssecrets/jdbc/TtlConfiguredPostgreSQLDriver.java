package com.poc.mule.awssecrets.jdbc;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Enumeration;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.amazonaws.secretsmanager.caching.SecretCacheConfiguration;
import com.amazonaws.secretsmanager.sql.AWSSecretsManagerPostgreSQLDriver;
import com.amazonaws.secretsmanager.util.JDBCSecretCacheBuilderProvider;

import software.amazon.awssdk.services.secretsmanager.SecretsManagerClientBuilder;

/**
 * Makes the aws-secretsmanager-jdbc secret cache TTL externally configurable.
 *
 * <p>Background: {@code aws-secretsmanager-jdbc} 2.1.3 exposes no configuration property for the
 * secret cache TTL. The only supported driver properties are {@code drivers.region},
 * {@code drivers.vpcEndpointUrl}, {@code drivers.vpcEndpointRegion},
 * {@code drivers.postQuantumTlsEnabled} and {@code drivers.<subprefix>.realDriverClass}. The cache
 * TTL can therefore only be set by constructing the driver with a
 * {@link SecretCacheConfiguration}, which {@link AWSSecretsManagerPostgreSQLDriver} exposes as a
 * public constructor. That is exactly what this class does.</p>
 *
 * <p>{@link AWSSecretsManagerPostgreSQLDriver} is {@code final}, so it cannot be subclassed. This
 * class therefore implements {@link Driver} and delegates to a properly configured instance. No
 * reflection is used and no private state is touched; only public JDBC and AWS APIs.</p>
 *
 * <p>Registration: loading {@link AWSSecretsManagerPostgreSQLDriver} runs its static initializer,
 * which registers an instance using the AWS default TTL of one hour. Because the Mule Database
 * Connector ultimately obtains connections through {@link DriverManager}, that default instance
 * would win. This class therefore deregisters it (which closes its cache via the registered
 * {@code DriverAction}) and registers a replacement built with the configured TTL.</p>
 *
 * <p>This class must live in the mule-db-connector classloader, not in the Mule application, which
 * is why it ships as a separate artifact wired in through {@code additionalPluginDependencies}.</p>
 *
 * <p>Nothing here reads, logs or stores secret material. Only the configured TTL is logged.</p>
 */
public final class TtlConfiguredPostgreSQLDriver implements Driver {

    /** Environment variable (or system property) holding the cache TTL in seconds. */
    public static final String TTL_VARIABLE = "AWS_SECRET_CACHE_TTL_SECONDS";

    /** Matches the AWS default, {@code SecretCacheConfiguration.DEFAULT_CACHE_ITEM_TTL} (1 hour). */
    public static final long DEFAULT_TTL_SECONDS = 3600L;

    private static final Logger LOGGER = LoggerFactory.getLogger(TtlConfiguredPostgreSQLDriver.class);

    private static final AWSSecretsManagerPostgreSQLDriver DELEGATE = install();

    /** Public no-arg constructor so {@code Class.forName(..).newInstance()} works. */
    public TtlConfiguredPostgreSQLDriver() {
        // Initialization happens once in the static initializer above.
    }

    /**
     * Resolves the TTL in seconds, preferring the environment variable and falling back to a system
     * property of the same name. Defaults to {@link #DEFAULT_TTL_SECONDS}.
     *
     * @throws IllegalStateException if the value is not a positive integer.
     */
    private static long resolveTtlSeconds() {
        String raw = System.getenv(TTL_VARIABLE);
        if (raw == null || raw.trim().isEmpty()) {
            raw = System.getProperty(TTL_VARIABLE);
        }
        if (raw == null || raw.trim().isEmpty()) {
            return DEFAULT_TTL_SECONDS;
        }

        long seconds;
        try {
            seconds = Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalStateException(
                    TTL_VARIABLE + " must be a positive whole number of seconds, but was \"" + raw + "\".", e);
        }
        if (seconds <= 0L) {
            throw new IllegalStateException(
                    TTL_VARIABLE + " must be a positive whole number of seconds, but was " + seconds + ".");
        }
        return seconds;
    }

    /**
     * Replaces the auto-registered default-TTL driver with one using the configured TTL, and returns
     * it. The AWS Secrets Manager client is built through {@link JDBCSecretCacheBuilderProvider} so
     * that the driver's own region resolution (AWS_SECRET_JDBC_REGION, PrivateLink endpoint
     * overrides, then the AWS default region provider chain) behaves exactly as it does by default.
     */
    private static AWSSecretsManagerPostgreSQLDriver install() {
        long ttlSeconds = resolveTtlSeconds();
        long ttlMillis = TimeUnit.SECONDS.toMillis(ttlSeconds);

        // Force AWSSecretsManagerPostgreSQLDriver's static initializer to run BEFORE we look for
        // drivers to remove. Its static block is what registers the default-TTL instance, and
        // merely referencing the class (for example via instanceof) links it without initializing
        // it. Without this, deregisterDefaultDrivers() would find nothing, and the default
        // instance would then be registered ahead of ours when we construct the configured driver.
        forceAwsDriverClassInitialization();

        // Must happen before registering ours, otherwise we would deregister our own instance too.
        int removed = deregisterDefaultDrivers();

        SecretsManagerClientBuilder clientBuilder = new JDBCSecretCacheBuilderProvider().build();
        SecretCacheConfiguration cacheConfig = new SecretCacheConfiguration()
                .withClient(clientBuilder.build())
                .withCacheItemTTL(ttlMillis);

        // The AWSSecretsManagerDriver constructor registers this instance with the DriverManager.
        AWSSecretsManagerPostgreSQLDriver configured = new AWSSecretsManagerPostgreSQLDriver(cacheConfig);

        LOGGER.info("AWS Secrets Manager JDBC secret cache TTL set to {} seconds ({} ms); "
                        + "replaced {} default-TTL driver registration(s). Source: {}",
                ttlSeconds, ttlMillis, removed,
                System.getenv(TTL_VARIABLE) != null ? "environment variable " + TTL_VARIABLE
                        : System.getProperty(TTL_VARIABLE) != null ? "system property " + TTL_VARIABLE
                        : "built-in default");

        return configured;
    }

    /**
     * Runs the static initializer of {@link AWSSecretsManagerPostgreSQLDriver}, which is where the
     * AWS default-TTL driver registers itself.
     */
    private static void forceAwsDriverClassInitialization() {
        try {
            Class.forName(AWSSecretsManagerPostgreSQLDriver.class.getName(),
                    true,
                    AWSSecretsManagerPostgreSQLDriver.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            // Not expected: the class is a compile-time dependency and is referenced above.
            throw new IllegalStateException(
                    "Could not initialize " + AWSSecretsManagerPostgreSQLDriver.class.getName() + ".", e);
        }
    }

    /**
     * Deregisters every {@link AWSSecretsManagerPostgreSQLDriver} currently registered. Deregistration
     * triggers the {@code DriverAction} the AWS driver registered with itself, which closes the
     * associated {@code SecretCache} and its Secrets Manager client.
     *
     * @return how many registrations were removed.
     */
    private static int deregisterDefaultDrivers() {
        int removed = 0;
        Enumeration<Driver> registered = DriverManager.getDrivers();
        while (registered.hasMoreElements()) {
            Driver candidate = registered.nextElement();
            if (candidate instanceof AWSSecretsManagerPostgreSQLDriver) {
                try {
                    DriverManager.deregisterDriver(candidate);
                    removed++;
                } catch (SQLException e) {
                    throw new IllegalStateException(
                            "Could not deregister the default AWS Secrets Manager PostgreSQL driver; "
                                    + "the configured cache TTL would not take effect.", e);
                }
            }
        }
        return removed;
    }

    @Override
    public Connection connect(String url, Properties info) throws SQLException {
        return DELEGATE.connect(url, info);
    }

    @Override
    public boolean acceptsURL(String url) throws SQLException {
        return DELEGATE.acceptsURL(url);
    }

    @Override
    public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) throws SQLException {
        return DELEGATE.getPropertyInfo(url, info);
    }

    @Override
    public int getMajorVersion() {
        return DELEGATE.getMajorVersion();
    }

    @Override
    public int getMinorVersion() {
        return DELEGATE.getMinorVersion();
    }

    @Override
    public boolean jdbcCompliant() {
        return DELEGATE.jdbcCompliant();
    }

    @Override
    public java.util.logging.Logger getParentLogger() throws SQLFeatureNotSupportedException {
        return DELEGATE.getParentLogger();
    }
}
