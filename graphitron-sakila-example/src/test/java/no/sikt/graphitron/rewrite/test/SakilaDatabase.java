package no.sikt.graphitron.rewrite.test;

import org.jooq.CloseableDSLContext;
import org.jooq.impl.DSL;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;

/**
 * Where an execution test gets its database: the one place that reads {@code test.db.*},
 * constructs a {@link PostgreSQLContainer} or composes a JDBC URL.
 *
 * <p>One database per test JVM, shared by every class and never reset. With {@code test.db.url}
 * set (the {@code local-db} profile) it is the database that property names; otherwise one
 * container is started on first use, seeded from {@code init.sql}, and stopped with the JVM.
 * Other classes write to it concurrently, so a test reads only rows it can name and writes only
 * rows it created; {@code docs/architecture/how-to/testing.adoc} states the rule.
 */
public final class SakilaDatabase {

    private static final String RUN_TOKEN = UUID.randomUUID().toString().replace("-", "").substring(0, 8);

    private static volatile Server server;

    private record Server(String url, String user, String password) {}

    private SakilaDatabase() {}

    /** The JDBC URL of the shared database. */
    public static String url() {
        return server().url();
    }

    public static String user() {
        return server().user();
    }

    public static String password() {
        return server().password();
    }

    /** A fresh autocommit {@link DSLContext} over the shared database, for out-of-band setup, assertions and cleanup. */
    public static CloseableDSLContext dsl() {
        var s = server();
        return DSL.using(s.url(), s.user(), s.password());
    }

    /** A plain JDBC connection to {@code url}, for a test that opens connections itself (a counting {@code DataSource}, a probe role). */
    public static Connection connect(String url, String user, String password) throws SQLException {
        return DriverManager.getConnection(url, user, password);
    }

    /**
     * The name of the database a test knows as {@code stem}: {@code <stem>_<run token>}, so two
     * runs against one server do not collide on a fixed cluster-scoped name.
     */
    public static String tenantName(String stem) {
        return stem + "_" + RUN_TOKEN;
    }

    /** The JDBC URL of the database {@link #tenantName} names. */
    public static String tenantUrl(String stem) {
        return withDatabase(url(), tenantName(stem));
    }

    /** Creates the database {@link #tenantName} names, empty, replacing one a dead run left behind. */
    public static void createTenantDatabase(String stem) {
        try (var admin = dsl()) {
            admin.execute("drop database if exists " + tenantName(stem) + " with (force)");
            admin.execute("create database " + tenantName(stem));
        }
    }

    /** Drops the database {@link #tenantName} names; the class that created it calls this. */
    public static void dropTenantDatabase(String stem) {
        try (var admin = dsl()) {
            admin.execute("drop database if exists " + tenantName(stem) + " with (force)");
        }
    }

    private static String withDatabase(String jdbcUrl, String database) {
        return jdbcUrl.replaceFirst("(jdbc:postgresql://[^/]+/)[^?]*", "$1" + database);
    }

    private static Server server() {
        var s = server;
        if (s == null) {
            synchronized (SakilaDatabase.class) {
                s = server;
                if (s == null) {
                    server = s = start();
                }
            }
        }
        return s;
    }

    private static Server start() {
        var localUrl = System.getProperty("test.db.url");
        if (localUrl != null) {
            return new Server(localUrl,
                System.getProperty("test.db.username", "postgres"),
                System.getProperty("test.db.password", "postgres"));
        }
        var container = new PostgreSQLContainer("postgres:18-alpine").withInitScript("init.sql");
        container.start();
        Runtime.getRuntime().addShutdownHook(new Thread(container::stop));
        return new Server(container.getJdbcUrl(), container.getUsername(), container.getPassword());
    }
}
