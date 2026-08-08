package no.sikt.graphitron.model.boot;

import org.h2.api.ErrorCode;
import org.h2.jdbcx.JdbcDataSource;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * The fact store's run-time bootstrap: opens an H2 database, executes the fact schema DDL into it,
 * and hands back a {@link DSLContext} over the result.
 *
 * <p>Two shapes, and the difference is only where the database lives. {@link #open()} is a private
 * in-memory database that dies with the process: the shape codegen, the tests and any caller with
 * no build directory want. {@link #openAt} is the same database in a file under the build
 * directory, so the next run starts from the previous run's rows instead of an empty schema.
 *
 * <p>Persisting changes nothing about what the store <em>is</em>. It is never state of record,
 * which is what keeps migrations out: {@code store_stamp} records the DDL the file was built from
 * and the version that built it, and any mismatch, unreadable file, or missing stamp discards the
 * file and rebuilds from the DDL. Deleting the build directory is therefore always correct and
 * never loses anything, which is the only property a {@code target/} artefact needs.
 *
 * <p>The build calls this too. {@code ModelCodegenDriver} opens a store through this same entry
 * point and points jOOQ's live H2 metadata generation at it, so codegen is a rehearsal of boot
 * rather than a second procedure kept similar by hand: a bootstrap regression or a DDL error
 * fails the build with a real H2 error before it can fail a generator run. That is why nothing
 * here may touch a generated class.
 *
 * <p>Instances are {@link AutoCloseable}. An in-memory store owns its database and drops it on
 * close; a file-backed one only releases its connection, leaving the file for the next run. The
 * in-memory name carries a fresh {@link UUID}, so concurrent stores (a parallel reactor, forked
 * test JVMs, an LSP session beside a build) never collide.
 */
public final class GraphitronModelStore implements AutoCloseable {

    /** Classpath location of the fact schema DDL; the single source the build and the run share. */
    public static final String DDL_RESOURCE = "/no/sikt/graphitron/model/graphitron-model.sql";

    /**
     * Base name of the persisted database inside the directory {@link #openAt} is given. H2 derives
     * every file it keeps from this (the store itself, a trace log, temporary files), so discarding
     * a store means removing everything in the directory that starts with it.
     */
    private static final String DATABASE = "store";

    /** Stands in for {@code store_stamp.generator_version} when no manifest declares one. */
    private static final String UNVERSIONED = "dev";

    private final Connection connection;
    private final DSLContext dsl;
    private final boolean warm;
    private final Path scratch;
    private final boolean dropOnClose;

    private GraphitronModelStore(Connection connection, boolean warm, Path scratch, boolean dropOnClose) {
        this.connection = connection;
        this.dsl = DSL.using(connection, SQLDialect.H2);
        this.warm = warm;
        this.scratch = scratch;
        this.dropOnClose = dropOnClose;
    }

    /**
     * Opens a fresh store: a private in-memory H2 database with the fact schema created in it.
     *
     * @throws IllegalStateException if the DDL resource is missing or H2 rejects it, both of
     *         which are build-time defects in this module rather than anything an author caused
     */
    public static GraphitronModelStore open() {
        String url = "jdbc:h2:mem:graphitron-model-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        Connection connection = connect(url);
        create(connection);
        stamp(connection);
        return new GraphitronModelStore(connection, false, null, true);
    }

    /**
     * Opens the store persisted under {@code directory}, creating it if there is none and
     * discarding it if there is one this build cannot read.
     *
     * <p>An existing file is kept only when it opens, carries a {@code store_stamp} row, and that
     * row names both this DDL and this generator version. Anything else, a schema from an older
     * DDL, a file left half-written by a killed build, a database H2 refuses outright, is deleted
     * and rebuilt, because there is nothing in it worth recovering that re-running capture will not
     * produce. {@link #warm()} reports which of the two happened, and it is the caller's cue that
     * the store already holds rows a refresh has to reconcile.
     *
     * <p>It never fails for want of a file. Persistence is an optimisation over an in-memory store
     * that was always correct on its own, so a directory that cannot be created, and a database
     * another process holds open, both fall back to {@link #open()} rather than failing a build
     * over a cache. The second case is the one that matters in practice: {@code graphitron:dev}
     * beside {@code mvn install} in the same project would otherwise have one of them delete the
     * file the other is using.
     */
    public static GraphitronModelStore openAt(Path directory) {
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            return open();
        }
        Attempt existing = openExisting(directory);
        if (existing.store() != null) {
            return existing.store();
        }
        if (existing.inUse()) {
            return open();
        }
        discard(directory);
        try {
            Connection connection = connect(fileUrl(directory, false));
            create(connection);
            stamp(connection);
            return new GraphitronModelStore(connection, false, null, false);
        } catch (RuntimeException e) {
            // Whatever went wrong is about the file, not the schema: a DDL this module cannot
            // execute fails identically on the in-memory store below, carrying the same message.
            return open();
        }
    }

    /**
     * Opens a read-only snapshot of the store persisted under {@code directory}, or empty when
     * there is no readable one.
     *
     * <p>Reads go through a private copy rather than through the live file, which is the whole of
     * the concurrency story. A build holds the store open for its own writes, and H2 gives a
     * database one writer; the alternatives are to make the first opener a server the rest connect
     * through, which turns a build artefact into a running service every reader has to opt into, or
     * to weaken the file lock, which trades a clean failure for a corrupt one. A copy needs no
     * protocol from either side, and it gives the reader a fixed snapshot for its whole session,
     * which a surface that labels its answers with a run identity wants anyway.
     *
     * <p>Copying a file a writer is in the middle of can of course produce something H2 will not
     * open. That is the empty return, and it is not a failure mode worth defending against: a
     * caller that cannot warm-start boots cold, which is what it did before any of this existed.
     */
    public static Optional<GraphitronModelStore> openReadOnly(Path directory) {
        Path database = directory.resolve(DATABASE + ".mv.db");
        if (!Files.isRegularFile(database)) {
            return Optional.empty();
        }
        Path scratch;
        try {
            scratch = Files.createTempDirectory("graphitron-model-read-");
            Files.copy(database, scratch.resolve(DATABASE + ".mv.db"));
        } catch (IOException e) {
            return Optional.empty();
        }
        try {
            Connection connection = connect(fileUrl(scratch, true));
            if (!stampMatches(connection)) {
                closeQuietly(connection);
                deleteRecursively(scratch);
                return Optional.empty();
            }
            return Optional.of(new GraphitronModelStore(connection, true, scratch, false));
        } catch (RuntimeException e) {
            deleteRecursively(scratch);
            return Optional.empty();
        }
    }

    /** The typed query surface over this store; the only handle capture and its readers need. */
    public DSLContext dsl() {
        return dsl;
    }

    /**
     * Whether this store opened onto rows a previous run wrote. False for every in-memory store and
     * for a persisted one that had to be rebuilt, so a caller can treat it as "the schema is empty"
     * without asking the database.
     */
    public boolean warm() {
        return warm;
    }

    /**
     * The store's own JDBC connection. Exposed for the codegen driver, which hands it to jOOQ's
     * {@code GenerationTool} so the generator reads metadata off exactly the database the
     * bootstrap just built. Callers must not close it; {@link #close()} owns the lifecycle.
     */
    public Connection connection() {
        return connection;
    }

    /**
     * Shuts the database down and releases the connection. An in-memory database goes with it; a
     * file-backed one is left on disk for the next run, and a read-only snapshot's private copy is
     * removed.
     */
    @Override
    public void close() {
        // SHUTDOWN only where the point is to drop the database. An in-memory store is held open by
        // DB_CLOSE_DELAY and needs it; a file-backed one does not, and issuing it there would close
        // the database for every other handle in the JVM, since H2 gives one process one database
        // per file however many connections reach for it.
        if (dropOnClose) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("SHUTDOWN");
            } catch (SQLException e) {
                // Nothing of record to lose, and nothing a caller could do about it either.
            }
        }
        closeQuietly(connection);
        if (scratch != null) {
            deleteRecursively(scratch);
        }
    }

    /**
     * The outcome of reaching for an existing file: the store when it was readable, and otherwise
     * the one distinction the caller has to act on. Every other way of being unreadable collapses
     * to "rebuild it", so the caller keeps one branch instead of a taxonomy kept in step with H2's.
     *
     * @param inUse another process holds the database. The one failure that must not be treated as
     *              a corrupt file, because the response to a corrupt file is to delete it
     */
    private record Attempt(GraphitronModelStore store, boolean inUse) {}

    private static Attempt openExisting(Path directory) {
        if (!Files.isRegularFile(directory.resolve(DATABASE + ".mv.db"))) {
            return new Attempt(null, false);
        }
        Connection connection;
        try {
            connection = connect(fileUrl(directory, false));
        } catch (IllegalStateException e) {
            return new Attempt(null, isAlreadyOpen(e));
        }
        if (!stampMatches(connection)) {
            closeQuietly(connection);
            return new Attempt(null, false);
        }
        return new Attempt(new GraphitronModelStore(connection, true, null, false), false);
    }

    /** Whether the open failed because someone else has the database, in H2's own vocabulary. */
    private static boolean isAlreadyOpen(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && sql.getErrorCode() == ErrorCode.DATABASE_ALREADY_OPEN_1) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether the store behind {@code connection} names this DDL and this generator version. A
     * missing relation, a missing row, or a read that throws all count as "no", which is what makes
     * an older schema indistinguishable from a corrupt one at this level: both get rebuilt.
     */
    private static boolean stampMatches(Connection connection) {
        String sql = "SELECT ddl_hash, generator_version FROM store_stamp WHERE singleton = 'X'";
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rows = statement.executeQuery()) {
            return rows.next()
                && ddlHash().equals(rows.getString(1))
                && generatorVersion().equals(rows.getString(2));
        } catch (SQLException e) {
            return false;
        }
    }

    /**
     * Removes every file H2 derived from the database name, so the next open starts from nothing.
     * Best effort: a file that will not go away leaves the create below to fail, and the fallback
     * to an in-memory store is the same answer either way.
     */
    private static void discard(Path directory) {
        try (Stream<Path> files = Files.list(directory)) {
            for (Path file : files.filter(f -> f.getFileName().toString().startsWith(DATABASE + ".")).toList()) {
                Files.deleteIfExists(file);
            }
        } catch (IOException e) {
            // Nothing to do here that the create's own failure will not do better.
        }
    }

    private static void deleteRecursively(Path directory) {
        try (Stream<Path> paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException e) {
            // A snapshot copy left behind is a temp-directory file, not a correctness problem.
        }
    }

    /**
     * Through H2's DataSource rather than DriverManager: DriverManager resolves a driver against
     * the calling class's loader, and the hosts this bootstrap runs in (the exec codegen driver,
     * the Maven plugin, the LSP) all hand it a loader the service-loaded driver was not registered
     * under. Naming the implementation removes the question.
     */
    private static Connection connect(String url) {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL(url);
        try {
            return source.getConnection();
        } catch (SQLException e) {
            throw new IllegalStateException("could not open the model store at " + url, e);
        }
    }

    private static String fileUrl(Path directory, boolean readOnly) {
        String url = "jdbc:h2:file:" + directory.toAbsolutePath().resolve(DATABASE)
            + ";DB_CLOSE_ON_EXIT=FALSE";
        return readOnly ? url + ";ACCESS_MODE_DATA=r" : url;
    }

    private static void create(Connection connection) {
        try (Statement statement = connection.createStatement()) {
            statement.execute(readDdl());
        } catch (SQLException e) {
            closeQuietly(connection);
            throw new IllegalStateException("the fact schema DDL did not execute: " + e.getMessage(), e);
        }
    }

    private static void stamp(Connection connection) {
        String sql = "INSERT INTO store_stamp (singleton, ddl_hash, generator_version) VALUES ('X', ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, ddlHash());
            statement.setString(2, generatorVersion());
            statement.executeUpdate();
        } catch (SQLException e) {
            closeQuietly(connection);
            throw new IllegalStateException("could not stamp the model store: " + e.getMessage(), e);
        }
    }

    private static String ddlHash() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(readDdl().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required of every JVM", e);
        }
    }

    private static String generatorVersion() {
        String version = GraphitronModelStore.class.getPackage().getImplementationVersion();
        return version == null ? UNVERSIONED : version;
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
