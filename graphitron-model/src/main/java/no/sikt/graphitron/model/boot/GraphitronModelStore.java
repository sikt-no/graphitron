package no.sikt.graphitron.model.boot;

import org.h2.jdbcx.JdbcDataSource;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

/**
 * The fact store's run-time bootstrap: opens a fresh in-memory H2 database, executes the fact
 * schema DDL into it, and hands back a {@link DSLContext} over the result.
 *
 * <p>One database per generator run, created at startup, populated by capture, dead with the
 * process. No persisted state of record exists and therefore no migrations do either; the DDL
 * resource is the schema's single source and the compiler is its only compatibility surface.
 *
 * <p>The build calls this too. {@code ModelCodegenDriver} opens a store through this same entry
 * point and points jOOQ's live H2 metadata generation at it, so codegen is a rehearsal of boot
 * rather than a second procedure kept similar by hand: a bootstrap regression or a DDL error
 * fails the build with a real H2 error before it can fail a generator run. That is why nothing
 * here may touch a generated class.
 *
 * <p>Instances are {@link AutoCloseable} and own the database: closing drops it. The database
 * name carries a fresh {@link UUID}, so concurrent stores (a parallel reactor, forked test JVMs,
 * an LSP session beside a build) never collide.
 */
public final class GraphitronModelStore implements AutoCloseable {

    /** Classpath location of the fact schema DDL; the single source the build and the run share. */
    public static final String DDL_RESOURCE = "/no/sikt/graphitron/model/graphitron-model.sql";

    private final Connection connection;
    private final DSLContext dsl;

    private GraphitronModelStore(Connection connection) {
        this.connection = connection;
        this.dsl = DSL.using(connection, SQLDialect.H2);
    }

    /**
     * Opens a fresh store: a private in-memory H2 database with the fact schema created in it.
     *
     * @throws IllegalStateException if the DDL resource is missing or H2 rejects it, both of
     *         which are build-time defects in this module rather than anything an author caused
     */
    public static GraphitronModelStore open() {
        String url = "jdbc:h2:mem:graphitron-model-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        // Through H2's DataSource rather than DriverManager: DriverManager resolves a driver
        // against the calling class's loader, and the hosts this bootstrap runs in (the exec
        // codegen driver, the Maven plugin, the LSP) all hand it a loader the service-loaded
        // driver was not registered under. Naming the implementation removes the question.
        JdbcDataSource source = new JdbcDataSource();
        source.setURL(url);
        Connection connection;
        try {
            connection = source.getConnection();
        } catch (SQLException e) {
            throw new IllegalStateException("could not open the in-memory model store", e);
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute(readDdl());
        } catch (SQLException e) {
            closeQuietly(connection);
            throw new IllegalStateException("the fact schema DDL did not execute: " + e.getMessage(), e);
        }
        return new GraphitronModelStore(connection);
    }

    /** The typed query surface over this store; the only handle capture and its readers need. */
    public DSLContext dsl() {
        return dsl;
    }

    /**
     * The store's own JDBC connection. Exposed for the codegen driver, which hands it to jOOQ's
     * {@code GenerationTool} so the generator reads metadata off exactly the database the
     * bootstrap just built. Callers must not close it; {@link #close()} owns the lifecycle.
     */
    public Connection connection() {
        return connection;
    }

    /** Drops the database and releases the connection. */
    @Override
    public void close() {
        try (Statement statement = connection.createStatement()) {
            statement.execute("SHUTDOWN");
        } catch (SQLException e) {
            // A store that failed to shut down cleanly is still going away with the connection
            // below; there is no state of record to lose and nothing a caller could do about it.
        }
        closeQuietly(connection);
    }

    private static void closeQuietly(Connection connection) {
        try {
            connection.close();
        } catch (SQLException e) {
            // Same reasoning as in close(): nothing survives the connection, so a failure here
            // has no consequence worth propagating over whatever the caller is already handling.
        }
    }

    private static String readDdl() {
        try (InputStream in = GraphitronModelStore.class.getResourceAsStream(DDL_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("fact schema DDL not on the classpath at " + DDL_RESOURCE);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("could not read " + DDL_RESOURCE, e);
        }
    }
}
