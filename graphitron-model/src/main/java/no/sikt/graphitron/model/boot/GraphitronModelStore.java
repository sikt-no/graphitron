package no.sikt.graphitron.model.boot;

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
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

/**
 * The fact store's run-time bootstrap: opens an H2 database, executes the fact schema DDL into it,
 * and hands back a {@link DSLContext} over the result.
 *
 * <p>Two shapes, and the difference is only where the database lives. {@link #open()} is a private
 * in-memory database that dies with the process: the shape codegen, the tests and any caller with
 * no store home want. {@link #openAt} is the same database in a file under the home it is handed
 * (a per-user cache directory shared by one workspace's modules, or wherever a consumer pinned
 * it), so the next run starts from the previous run's rows instead of an empty schema.
 *
 * <p>Persisting changes nothing about what the store <em>is</em>. It is never state of record,
 * which is what keeps migrations out: the DDL hash and generator version name the store's own
 * subdirectory under the home, so a generator upgrade or a DDL edit opens a different file
 * rather than meeting one it cannot read, and {@code store_stamp} re-records both inside the
 * file as the integrity check for a hand-moved or hand-damaged one. A shared store is never
 * deleted by this class: any failure to open or attach falls back to {@link #open()} and leaves
 * the file alone, so cache trouble costs warmth, never correctness, and never fails a build.
 * Deleting the store's cache directory by hand is always safe and never loses anything.
 *
 * <p>A file-backed store opens in H2's mixed mode ({@code AUTO_SERVER}): the first process holds
 * the file and later processes attach through it transparently, which is what lets a parallel
 * reactor build share one workspace store instead of handing every module but the first a cold
 * in-memory fallback.
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
 *
 * <p>A consumer that reads while somebody else writes asks this store for a {@link StoreReader}
 * rather than sharing {@link #dsl()}. That the URL stays private is the reason the mint exists here:
 * see {@link #reader()}.
 */
public final class GraphitronModelStore implements AutoCloseable {

    /** Classpath location of the fact schema DDL; the single source the build and the run share. */
    public static final String DDL_RESOURCE = "/no/sikt/graphitron/model/graphitron-model.sql";

    /**
     * Base name of the persisted database inside the stamped directory {@link #openAt} resolves.
     * H2 derives every file it keeps from this (the store itself, a trace log, temporary files),
     * so a hand cleanup means removing everything in the directory that starts with it.
     */
    private static final String DATABASE = "store";

    /** Stands in for {@code store_stamp.generator_version} when no manifest declares one. */
    private static final String UNVERSIONED = "dev";

    private final Connection connection;
    private final DSLContext dsl;
    private final boolean warm;
    private final Path location;
    private final boolean dropOnClose;
    private final String url;

    private GraphitronModelStore(Connection connection, boolean warm, Path location,
                                 boolean dropOnClose, String url) {
        this.connection = connection;
        this.dsl = DSL.using(connection, SQLDialect.H2);
        this.warm = warm;
        this.location = location;
        this.dropOnClose = dropOnClose;
        this.url = url;
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
        return new GraphitronModelStore(connection, false, null, true, url);
    }

    /**
     * Opens the store persisted under the home {@code storeDirectory}, creating it if there is
     * none, attaching to it if another process already holds it, and leaving it strictly alone if
     * it cannot be used.
     *
     * <p>{@code storeDirectory} is the store's <em>home</em>, at every layer that passes it; the
     * store itself appends a {@code <ddl-hash>-<version>} segment and keeps its file there. The
     * segment is appended here and not by callers because every opener in every process (the
     * build mojos, {@code graphitron:dev}'s reader, the LSP, MCP) would have to reproduce it
     * byte-identically, and one that computed it even slightly differently would not fail: it
     * would look in the wrong directory, find nothing, and silently boot cold beside a warm
     * store. The hash is also this class's own ({@link #ddlHash()} reads the DDL this class
     * boots from), so the path is an enforced invariant rather than a published one. The stamped
     * path is what makes never discarding safe: a generator upgrade or a DDL edit opens a
     * different file instead of meeting a shared store other modules' builds are still warm on.
     *
     * <p>The file opens in H2's mixed mode, so concurrent module builds of one workspace share
     * it: the first process holds the file, later ones attach transparently, and an attached
     * process survives the holder closing first. An existing file is kept only when it opens and
     * its {@code store_stamp} row names this DDL and this generator version, which under the
     * stamped path can only fail for a hand-moved or hand-damaged file. {@link #warm()} reports
     * whether previous rows were found, and it is the caller's cue that the store already holds
     * rows a refresh has to reconcile.
     *
     * <p>It never fails, and it never deletes. Persistence is an optimisation over an in-memory
     * store that was always correct on its own, and one file is now every module's warmth, so
     * any failure at all (no resolvable home, a read-only location, H2 server trouble, a file H2
     * refuses for reasons it will not name) falls back to {@link #open()} and leaves the file
     * for the run that can read it. Cache trouble costs warmth, never correctness, and never
     * fails a build.
     */
    public static GraphitronModelStore openAt(Path storeDirectory) {
        Path directory = storeDirectory.resolve(stampSegment());
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            return open();
        }
        boolean existing = Files.isRegularFile(directory.resolve(DATABASE + ".mv.db"));
        String url = fileUrl(directory);
        try {
            Connection connection = connect(url);
            if (stampMatches(connection)) {
                return new GraphitronModelStore(connection, true, directory, false, url);
            }
            if (existing) {
                // A file at the stamped path whose stamp still mismatches was moved or damaged
                // by hand. Not this run's to repair, and never its to delete.
                closeQuietly(connection);
                return open();
            }
            create(connection);
            stamp(connection);
            return new GraphitronModelStore(connection, false, directory, false, url);
        } catch (RuntimeException e) {
            // Whatever went wrong is about the file, not the schema: a DDL this module cannot
            // execute fails identically on the in-memory store below, carrying the same message.
            // This also catches the cold-start race, two processes creating the store at once:
            // whichever executes the DDL first completes and stamps it, the other fails fast on
            // the first CREATE and takes the fallback, losing warmth for one build and nothing
            // else.
            return open();
        }
    }

    /**
     * The typed query surface over this store's own connection: what capture writes through, and
     * what a caller reads through when it is the same turn-based party that writes. A consumer
     * answering requests while another party writes wants {@link #reader()} instead, which is a
     * connection of its own rather than a share of this one.
     */
    public DSLContext dsl() {
        return dsl;
    }

    /**
     * Mints a second connection onto this same database, for a consumer that reads while this
     * store's owner writes. The store mints it rather than publishing a URL for a reader to open
     * itself: the in-memory name carries a private {@link UUID} that nothing outside this class can
     * reproduce, and a file-backed reader recomputing the stamped path would be one edit away from
     * opening a directory that does not exist, booting empty, and reporting a schema with no facts
     * in it as a schema with no facts.
     *
     * <p>Both shapes admit a second connection, and the fallback needs no special case. An
     * in-memory database is named and lives in this JVM, so a reader attaches to it by URL like any
     * other; a file-backed one is in mixed mode and hands out connections to the same process
     * freely. That is what makes the in-memory fallback a full answer surface rather than a
     * degraded one: a session whose cache directory was unusable still captures into its private
     * store, and its reader still sees every row that session wrote. There is no configuration
     * under which a caller holds a store it cannot read.
     *
     * <p>Each call mints a fresh reader, and the caller owns it: readers do not pool, and closing
     * one leaves this store and any sibling reader untouched. Closing <em>this</em> store while a
     * reader is open is the one ordering that matters, since an in-memory database goes with its
     * owner.
     *
     * @throws IllegalStateException if the second connection cannot be opened, which means this
     *         store's own database is gone rather than that the caller asked for the wrong thing
     */
    public StoreReader reader() {
        return new StoreReader(connect(url));
    }

    /**
     * Whether this store opened onto rows a previous run wrote. False for every in-memory store
     * and for a persisted one created fresh by this open, so a caller can treat it as "the schema
     * is empty" without asking the database.
     */
    public boolean warm() {
        return warm;
    }

    /**
     * The directory this store actually opened in (the home plus the stamp segment), empty for
     * the in-memory shape. Reporting where the store landed is not publishing the segment: a
     * caller learns the path after the fact rather than rebuilding it in advance, so the
     * cold-boot-beside-a-warm-store failure the private hash prevents stays unreachable. Without
     * this no test could address the database file at all, the stamped segment being exactly
     * what a test cannot name.
     */
    public Optional<Path> location() {
        return Optional.ofNullable(location);
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
     * file-backed one is left on disk for the next run.
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
    }

    /**
     * Whether the store behind {@code connection} names this DDL and this generator version. A
     * missing relation, a missing row, or a read that throws all count as "no", which is what
     * makes an older schema indistinguishable from a corrupt one at this level: neither is this
     * run's to use, and the caller decides between creating the schema and falling back.
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

    /**
     * Mixed mode, and nothing else but a lock budget. H2 refuses {@code AUTO_SERVER=TRUE} in
     * combination with {@code DB_CLOSE_ON_EXIT=FALSE}, and dropping that flag costs nothing this
     * store relies on: it suppressed H2's shutdown hook, and the only store that needs its
     * database to outlive a handle is the in-memory one, held open by {@code DB_CLOSE_DELAY=-1}
     * on a different URL. A file-backed store is meant to be left on disk, so H2 closing it at
     * JVM exit is what it wanted anyway. The lock timeout is raised from H2's one-second default
     * because concurrent module builds sharing the file serialize on rows a whole capture
     * transaction holds, and a writer that waits its turn beats one that falls back cold.
     */
    private static String fileUrl(Path directory) {
        return "jdbc:h2:file:" + directory.toAbsolutePath().resolve(DATABASE)
            + ";AUTO_SERVER=TRUE;LOCK_TIMEOUT=60000";
    }

    /**
     * The compatibility segment the store keeps its file under: a prefix of the DDL hash plus the
     * generator version. Any DDL edit or version change moves the path, which is what
     * {@link #openAt} leans on to never meet a file it cannot read; the prefix keeps the whole
     * path short enough for platforms that cap it, and sixteen hex digits lose nothing a cache
     * path needs (a colliding edit would still be caught by {@code store_stamp}).
     */
    private static String stampSegment() {
        return ddlHash().substring(0, 16) + "-" + generatorVersion();
    }

    /**
     * Executes the DDL one statement at a time rather than as a single script: H2 evaluates a
     * multi-statement command as a {@code CommandList} whose execution recurses once per
     * remaining statement, so a script past roughly a thousand statements overflows the thread
     * stack, at a depth that varies with the caller's own stack. Per-statement execution keeps
     * the boot flat regardless of how the schema grows, and a boot failure names the exact
     * statement instead of the script.
     *
     * <p>Commits afterwards, which is load-bearing rather than defensive. H2 implicitly commits a
     * schema statement, so a file of nothing but {@code CREATE} and {@code COMMENT ON} needed
     * none, but the file also seeds the authored {@code meta_} rows with an {@code INSERT}, which
     * is ordinary DML. An in-memory store keeps the one connection and would never notice; a
     * file-backed one closes this connection and would reopen to find the schema present and the
     * seeded rows gone.
     */
    private static void create(Connection connection) {
        String current = null;
        try (Statement statement = connection.createStatement()) {
            for (String sql : splitStatements(readDdl())) {
                current = sql;
                statement.execute(sql);
            }
            if (!connection.getAutoCommit()) {
                connection.commit();
            }
        } catch (SQLException e) {
            closeQuietly(connection);
            throw new IllegalStateException("the fact schema DDL did not execute: " + e.getMessage()
                + (current == null ? "" : " (statement: " + current + ")"), e);
        }
    }

    /**
     * Splits the DDL on its top-level semicolons, tracking single-quote string state (with the
     * doubled-quote escape, whose adjacent quotes just toggle through) and {@code --} line
     * comments, so a semicolon inside a {@code COMMENT ON} literal or inside prose commentary
     * never splits a statement. The comment text stays in the emitted fragments; H2 ignores it,
     * and a fragment that is only commentary strips to nothing and is dropped.
     */
    private static java.util.List<String> splitStatements(String ddl) {
        var statements = new java.util.ArrayList<String>();
        var current = new StringBuilder();
        boolean inQuote = false;
        boolean inLineComment = false;
        for (int i = 0; i < ddl.length(); i++) {
            char c = ddl.charAt(i);
            if (inLineComment) {
                if (c == '\n') {
                    inLineComment = false;
                }
            } else if (c == '\'') {
                inQuote = !inQuote;
            } else if (!inQuote && c == '-' && i + 1 < ddl.length() && ddl.charAt(i + 1) == '-') {
                inLineComment = true;
            } else if (!inQuote && c == ';') {
                addNonComment(statements, current);
                continue;
            }
            current.append(c);
        }
        addNonComment(statements, current);
        return statements;
    }

    /** Adds the buffered fragment when it holds more than commentary, and resets the buffer. */
    private static void addNonComment(java.util.List<String> statements, StringBuilder current) {
        String sql = current.toString().strip();
        current.setLength(0);
        if (sql.isEmpty() || sql.lines().allMatch(l -> l.isBlank() || l.stripLeading().startsWith("--"))) {
            return;
        }
        statements.add(sql);
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
