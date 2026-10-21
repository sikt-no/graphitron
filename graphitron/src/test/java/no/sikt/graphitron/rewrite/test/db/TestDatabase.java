package no.sikt.graphitron.rewrite.test.db;

import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The one PostgreSQL the tests of generated code run against, per test JVM: the database
 * {@code -Dtest.db.url} names, as {@code -Plocal-db} sets it, else a {@code postgres:18-alpine}
 * Testcontainers seeded from the sakila {@code init.sql}, started on first use and left to
 * Testcontainers to stop when the JVM goes.
 *
 * <p>Shared, and never reset between tests, so a test is resilient rather than isolated: it reads
 * the seeded catalog rows, which no test deletes, and a test that writes reads back only rows under
 * keys it chose. Nothing asserts on how many rows a table holds. A database a developer keeps between
 * runs, as {@code -Dtest.db.url} allows, therefore serves as well as a fresh one.
 *
 * <p>Statements run on a connection per statement from a data source, so the module's parallel
 * test classes never share one JDBC connection.
 */
public final class TestDatabase {

    private static DSLContext dsl;

    private TestDatabase() {}

    /** The shared database's {@code DSLContext}, the database started on the first call. */
    public static synchronized DSLContext dsl() {
        if (dsl == null) {
            var dataSource = new PGSimpleDataSource();
            var url = System.getProperty("test.db.url");
            if (url != null) {
                dataSource.setUrl(url);
                dataSource.setUser(System.getProperty("test.db.username", "postgres"));
                dataSource.setPassword(System.getProperty("test.db.password", "postgres"));
            } else {
                var postgres = new PostgreSQLContainer("postgres:18-alpine").withInitScript("init.sql");
                postgres.start();
                dataSource.setUrl(postgres.getJdbcUrl());
                dataSource.setUser(postgres.getUsername());
                dataSource.setPassword(postgres.getPassword());
            }
            dsl = DSL.using(dataSource, SQLDialect.POSTGRES);
        }
        return dsl;
    }
}
