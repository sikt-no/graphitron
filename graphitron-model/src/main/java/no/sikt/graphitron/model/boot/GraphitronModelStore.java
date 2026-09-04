package no.sikt.graphitron.model.boot;

import no.sikt.graphitron.model.boot.StoreReaper.Reaped;
import no.sikt.graphitron.model.derive.MaterializeDependencies;
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
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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
 * file as the integrity check for a hand-moved or hand-damaged one. Any failure to open falls back
 * to {@link #open()} and leaves the file alone, so cache trouble costs warmth, never correctness,
 * and never fails a build. Deleting the store's cache directory by hand is always safe and never
 * loses anything.
 *
 * <p>This class deletes only what {@link StoreReaper} has proved nobody held at the instant it
 * asked, and never the directory it opened: the stamped path accumulates one directory per stamp a
 * home has ever seen, so {@link #openAt} sweeps the home it opened in and keeps the
 * {@link #RETAINED_STAMPS} most recently used. {@link #reaped()} is what it released.
 *
 * <p>A file-backed store is held by one process at a time. Every connection inside that process
 * shares it, which covers a reactor build's modules (they run in the Maven JVM) and a session's
 * readers alike; a <em>second process</em> that meets a held file learns so from the operating
 * system in well under a second and takes the in-memory fallback. Sharing one file across
 * processes is deliberately not offered: {@link #fileUrl} says why the flag that offered it is
 * refused.
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
 * see {@link #reader(ReadBudget)}.
 */
public final class GraphitronModelStore implements AutoCloseable {

    /** Classpath location of the fact schema DDL; the single source the build and the run share. */
    public static final String DDL_RESOURCE = "/no/sikt/graphitron/model/graphitron-model.sql";

    /**
     * Base name of the persisted database inside the stamped directory {@link #openAt} resolves.
     * H2 derives every file it keeps from this (the store itself, a trace log, temporary files),
     * so a hand cleanup means removing everything in the directory that starts with it.
     *
     * <p>{@link StoreReaper} reads it too: the prefix is what its candidate recognition admits, so
     * the documented hand cleanup and the automatic sweep agree about one set of files rather than
     * two that can drift.
     */
    static final String DATABASE = "store";

    /**
     * The recency marker a successful file-backed open rewrites in its own stamped directory: the
     * fact {@link StoreReaper} orders the retention by, rather than an inference from H2's file
     * times, which answer "when was this store last written" and so miss a session that only reads.
     *
     * <p>Named under {@link #DATABASE}'s prefix deliberately. Under the prefix it is inside the set
     * the hand cleanup above removes, so cleanup and recognition stay aligned, and the reaper's
     * "nothing else" clause stays one prefix rather than a prefix plus an allowlist every future
     * file has to be added to. A marker outside the prefix is precisely what survives the documented
     * cleanup, leaving a directory the reaper would have to reason about with no database to lock.
     */
    static final String MARKER = DATABASE + ".last-used";

    /**
     * How many stamped directories a home keeps, the one the current run opened included. Three is
     * the deepest alternation that shows up: the stamp this build wants, the stamp a long-running
     * {@code graphitron:dev} session in the same checkout is holding, and one branch's worth of
     * history. A constant rather than a parameter a consumer sets, for the reason a knob whose only
     * effect is unbounded disk growth is not a knob.
     */
    static final int RETAINED_STAMPS = 3;

    /**
     * The homes this JVM has already swept, normalised. A reactor build opens the store once per
     * module and the second sweep of a home has nothing left to find, so the sweep runs once per
     * home per JVM. The set's check-and-set has to be atomic because CI builds the reactor with
     * {@code -T 1C} and two modules in one Maven JVM can reach {@link #openAt} concurrently: nothing
     * unsafe follows from a double sweep, every deletion race being caught, but the count and byte
     * total would be split across two reports, and the report is the feature's whole user surface on
     * an ordinary build.
     */
    private static final Set<Path> SWEPT_HOMES = ConcurrentHashMap.newKeySet();

    /**
     * How many live stores this JVM holds on each file-backed location, so the last one to let go
     * can compact it. H2 gives one process one database per file however many connections reach for
     * it, so the file is JVM-wide state and the count that guards it has to be too.
     *
     * <p>A slot is reserved before the connection is opened rather than when the store is
     * constructed, because the window between the two is exactly where a concurrent close would
     * otherwise find no holders and compact the database out from under an opener that has already
     * connected. Every arm of {@link #openAt} that does not go on to hand back a file-backed store
     * releases its reservation.
     *
     * <p>Keyed on the absolute normalised path rather than on the {@link Path} a caller handed in.
     * Two spellings of one directory are two keys but one file, and that arithmetic is the failure
     * this count exists to prevent: each spelling would reach zero on its own and compact while the
     * other still held the database open. {@link #openAt} is public, so the spelling is not this
     * class's to assume.
     *
     * <p>{@link #close} decrements inside {@link Map#compute}, and compacts inside the same call
     * rather than after it. The lambda holds the map's lock for that key, which is what makes
     * "nobody else holds this file" still true while the compaction runs: an opener reserving the
     * same location blocks until it finishes and then connects to a compacted file.
     */
    private static final Map<Path, Integer> OPEN_HANDLES = new ConcurrentHashMap<>();

    /** Stands in for {@code store_stamp.generator_version} when no manifest declares one. */
    private static final String UNVERSIONED = "dev";

    /**
     * The lock budget a file-backed connection opens with, in milliseconds: how long a writer waits
     * for a row another writer holds before giving up. Generous on purpose, for the reason
     * {@link #fileUrl} states, and public because a writer that narrows it for one row has to be
     * able to restore <em>this</em> number rather than a copy of it that can drift from it.
     */
    public static final long FILE_LOCK_MILLIS = 60_000;

    private final Connection connection;
    private final DSLContext dsl;
    private final boolean warm;
    private final Path location;
    private final boolean dropOnClose;
    private final String url;
    private final Reaped reaped;
    private boolean closed;
    private Compaction compaction;

    private GraphitronModelStore(Connection connection, boolean warm, Path location,
                                 boolean dropOnClose, String url, Reaped reaped) {
        this.connection = connection;
        this.dsl = DSL.using(connection, SQLDialect.H2);
        this.warm = warm;
        this.location = location;
        this.dropOnClose = dropOnClose;
        this.url = url;
        this.reaped = reaped;
    }

    /**
     * What the last handle's release cost: how long the compaction took, and the file's size on
     * either side of it. Reported rather than logged, so the caller that knows which run this was
     * decides whether an ordinary build hears it.
     *
     * @param millis      wall-clock time the compaction took
     * @param bytesBefore the file's size before it, or {@code -1} where it could not be read
     * @param bytesAfter  the file's size after it, or {@code -1} where it could not be read
     */
    public record Compaction(long millis, long bytesBefore, long bytesAfter) {

        /**
         * One line naming the store and what it cost, or empty where the sizes could not be read
         * and the line would say nothing a reader could act on.
         */
        public Optional<String> report(Path home) {
            if (bytesBefore < 0 || bytesAfter < 0) {
                return Optional.empty();
            }
            return Optional.of("Compacted the fact store under " + home + " in " + millis
                + " ms: " + humanBytes(bytesBefore) + " -> " + humanBytes(bytesAfter));
        }

        private static String humanBytes(long bytes) {
            if (bytes < 1024) {
                return bytes + " B";
            }
            if (bytes < 1024 * 1024) {
                return Math.round(bytes / 1024.0) + " KB";
            }
            if (bytes < 1024L * 1024L * 1024L) {
                return Math.round(bytes / (1024.0 * 1024.0)) + " MB";
            }
            return Math.round(bytes / (1024.0 * 1024.0 * 1024.0) * 10) / 10.0 + " GB";
        }
    }

    /**
     * What this store's close spent compacting, present only on the handle that was the last one
     * on a file-backed location and only after {@link #close} has run.
     */
    public Optional<Compaction> compaction() {
        return Optional.ofNullable(compaction);
    }

    /**
     * Opens a fresh store: a private in-memory H2 database with the fact schema created in it.
     *
     * @throws IllegalStateException if the DDL resource is missing or H2 rejects it, both of
     *         which are build-time defects in this module rather than anything an author caused
     */
    public static GraphitronModelStore open() {
        return open(Reaped.none());
    }

    /**
     * {@link #open()} carrying a sweep report, for {@link #openAt}'s fallback arms: a run whose
     * cache home was unusable still swept that home, and the report is the caller's to log whichever
     * store it ended up with. Separate from {@link #open()} so the public entry point keeps its
     * current meaning and the field stays final.
     */
    private static GraphitronModelStore open(Reaped reaped) {
        String url = "jdbc:h2:mem:graphitron-model-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        Connection connection = connect(url);
        create(connection);
        stamp(connection);
        deriveDependencies(connection);
        return new GraphitronModelStore(connection, false, null, true, url, reaped);
    }

    /**
     * Opens the store persisted under the home {@code storeDirectory}, creating it if there is
     * none, falling back to a private in-memory store if another process already holds it, and
     * leaving it strictly alone if it cannot be used.
     *
     * <p>{@code storeDirectory} is the store's <em>home</em>, at every layer that passes it; the
     * store itself appends a {@code <ddl-hash>-<version>} segment and keeps its file there. The
     * segment is appended here and not by callers because every opener in every process (the
     * build mojos, {@code graphitron:dev}'s reader, the LSP, MCP) would have to reproduce it
     * byte-identically, and one that computed it even slightly differently would not fail: it
     * would look in the wrong directory, find nothing, and silently boot cold beside a warm
     * store. The hash is also this class's own ({@link #ddlHash()} reads the DDL this class
     * boots from), so the path is an enforced invariant rather than a published one. The stamped
     * path is what makes discarding safe: a generator upgrade or a DDL edit opens a
     * different file instead of meeting a shared store other modules' builds are still warm on,
     * which is also why a directory nobody holds any more can be released without reasoning about
     * what might still open it.
     *
     * <p>Concurrent module builds of one workspace share the file because they share a JVM: H2
     * gives one process one database per file and hands every further connection off it, so a
     * reactor build's modules and a session's readers all open in milliseconds onto the rows the
     * others committed. A second <em>process</em> is refused instead, and falls back rather than
     * waiting; {@link #fileUrl} carries the reason that is the better trade.
     *
     * <p>An existing file is kept only when it opens and its
     * {@code store_stamp} row names this DDL and this generator version, which under the
     * stamped path can only fail for a hand-moved or hand-damaged file. {@link #warm()} reports
     * whether previous rows were found, and it is the caller's cue that the store already holds
     * rows a refresh has to reconcile.
     *
     * <p>It never fails. Persistence is an optimisation over an in-memory
     * store that was always correct on its own, and one file is now every module's warmth, so
     * any failure at all (no resolvable home, a read-only location, a file another process holds,
     * a file H2 refuses for reasons it will not name) falls back to {@link #open()} and leaves the file
     * for the run that can read it. Cache trouble costs warmth, never correctness, and never
     * fails a build.
     *
     * <p>It also sweeps: every arm hands the home to {@link StoreReaper}, once per home per JVM,
     * which releases the stamped directories under it that nothing holds and recency does not keep.
     * The sweep lives behind this method rather than at its callers because the stamp segment is
     * this class's private knowledge, which is the same reason the segment is appended here, and
     * because a caller-driven sweep would be a rule every opener has to remember. The fallback arms
     * sweep too: a home whose live stamp is held by a dev session is precisely a home whose older
     * stamps nobody is looking at, and the live segment is spared by name there rather than by the
     * lock probe, since this run holds nothing. {@link #reaped()} reports what was released.
     */
    public static GraphitronModelStore openAt(Path storeDirectory) {
        String segment = stampSegment();
        // Before the open rather than after it, so every arm below carries the same report without
        // each one asking for it. Releasing a directory is a handful of unlinks whatever the file's
        // size, and the live segment is spared by name, so nothing here depends on the open having
        // happened first.
        Reaped reaped = sweepOnce(storeDirectory, segment);
        Path directory = storeDirectory.resolve(segment);
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            return open(reaped);
        }
        boolean existing = Files.isRegularFile(directory.resolve(DATABASE + ".mv.db"));
        String url = fileUrl(directory);
        // Reserved before the connection exists, for the reason OPEN_HANDLES states. Every exit
        // below that does not hand back a file-backed store releases it again.
        reserve(directory);
        try {
            Connection connection = connect(url);
            if (stampMatches(connection)) {
                markUsed(directory);
                return new GraphitronModelStore(connection, true, directory, false, url, reaped);
            }
            if (existing) {
                // A file at the stamped path whose stamp still mismatches was moved or damaged
                // by hand. Not this run's to repair, and never its to delete: the sweep above
                // spares the live segment by name, so this directory is not a candidate either.
                closeQuietly(connection);
                release(directory);
                return open(reaped);
            }
            create(connection);
            stamp(connection);
            deriveDependencies(connection);
            markUsed(directory);
            return new GraphitronModelStore(connection, false, directory, false, url, reaped);
        } catch (RuntimeException e) {
            release(directory);
            // Whatever went wrong is about the file, not the schema: a DDL this module cannot
            // execute fails identically on the in-memory store below, carrying the same message.
            // The ordinary case here is a file another process holds, which H2 refuses in well
            // under a second. This also catches the cold-start race, two processes creating the
            // store at once: whichever executes the DDL first completes and stamps it, the other
            // fails fast on the first CREATE and takes the fallback, losing warmth for one build
            // and nothing else.
            return open(reaped);
        }
    }

    /**
     * Sweeps {@code home} unless this JVM already has, per {@link #SWEPT_HOMES}.
     *
     * <p>Swallows its own trouble for the same reason the reaper does: a home whose path cannot even
     * be normalised is a home with nothing to sweep, and {@link #openAt} promises never to fail.
     */
    private static Reaped sweepOnce(Path home, String liveSegment) {
        Path normalised;
        try {
            normalised = home.toAbsolutePath().normalize();
        } catch (RuntimeException e) {
            return Reaped.none();
        }
        if (!SWEPT_HOMES.add(normalised)) {
            return Reaped.none();
        }
        return StoreReaper.sweep(normalised, liveSegment, RETAINED_STAMPS);
    }

    /**
     * Records this open as the stamped directory's most recent use, which is what the retention
     * orders by. Written strictly after a successful open, never before, so a directory carrying a
     * marker always carries a database H2 has opened; a directory this run abandoned keeps whatever
     * marker it already had.
     *
     * <p>Swallows everything, including the unchecked failures {@link Files#writeString} can raise.
     * Letting one escape would land in {@link #openAt}'s own {@code catch (RuntimeException)} and
     * demote a perfectly good warm store to memory, and the one state where that is reachable, a
     * full disk, is exactly this mechanism's audience. A marker that could not be written costs the
     * sweep its recorded recency for this directory, which falls back to the database's own
     * modification time.
     */
    private static void markUsed(Path directory) {
        try {
            Files.writeString(directory.resolve(MARKER), Instant.now().toString());
        } catch (IOException | RuntimeException e) {
            // Nothing a caller could do, and nothing correctness depends on.
        }
    }

    /**
     * The typed query surface over this store's own connection: what capture writes through, and
     * what a caller reads through when it is the same turn-based party that writes. A consumer
     * answering requests while another party writes wants {@link #reader(ReadBudget)} instead, which is a
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
     * other; a file-backed one is already open in this process, and H2 hands a second connection off
     * the database this store holds without consulting the file lock at all. That is a property of
     * being in the holding process rather than of any URL flag, so it survives this store refusing
     * cross-process sharing. That is what makes the in-memory fallback a full answer surface rather than a
     * degraded one: a session whose cache directory was unusable still captures into its private
     * store, and its reader still sees every row that session wrote. There is no configuration
     * under which a caller holds a store it cannot read.
     *
     * <p>Each call mints a fresh reader, and the caller owns it: readers do not pool, and closing
     * one leaves this store and any sibling reader untouched. Closing <em>this</em> store while a
     * reader is open is the one ordering that matters, and it matters in both shapes for different
     * reasons: an in-memory database goes with its owner, and a file-backed one is compacted by
     * {@link #close()} when this is the last store handle on the file, which shuts the database
     * under any reader still on it. A reader is a connection off the same database and not a store
     * handle, so it does not hold that compaction off. Close the readers first.
     *
     * <p>Every reader states a {@link ReadBudget}, and there is deliberately no overload that
     * defaults one. A caller minting a reader knows what its reads answer for (a keystroke, a
     * whole-workspace recalculation, one turn of a tool call, a fixture), and that is precisely the
     * knowledge a default would discard; the compiler asking is cheaper than discovering later that
     * an interactive surface inherited a fixture's budget. A caller that means no limit at all says
     * {@link ReadBudget.Unbounded} and says it structurally.
     *
     * @param budget how long any one statement this reader issues may spend before the database
     *        aborts it, which is a property of the session and is installed here once
     * @throws IllegalStateException if the second connection cannot be opened, which means this
     *         store's own database is gone rather than that the caller asked for the wrong thing
     */
    public StoreReader reader(ReadBudget budget) {
        return new StoreReader(connect(url), budget);
    }

    /**
     * Mints a read-only SQL console onto this store, served over the PostgreSQL wire protocol on a
     * loopback port, so a developer can query the rows a live session is answering from. The store
     * mints it for the reason it mints {@link #reader(ReadBudget)}, and one more: the console's link
     * statements need this store's URL, which is private, and a console assembled outside this class
     * would have to reconstruct a stamped path or a {@link UUID} name and would fail exactly the way
     * that method describes.
     *
     * <p>{@code console(0)} is the ordinary call: the ephemeral port is the encouraged shape, since
     * several dev sessions in one workspace is the ordinary case and a pinned port makes the second
     * one fail to open. Nothing here treats {@code 0} as a sentinel;
     * {@link StoreConsole#port()} reports the port bound either way.
     *
     * <p>What the console <em>is</em>, and the three measured constraints that shaped it (PostgreSQL
     * mode being a creation-time property, pgjdbc being unable to speak to H2's PostgreSQL server,
     * and H2's {@code allowOthers=false} being a peer check rather than a bind restriction) are
     * {@link StoreConsole}'s subject. Nothing about this store changes: its own mode, its
     * connection, and the way every generator query reads it are untouched, which is the whole
     * reason the console is a second database that reads through to this one.
     *
     * @param port the port to bind, or {@code 0} for an ephemeral one
     * @throws IllegalStateException if the console cannot be opened or its listener cannot be shown
     *         to be confined to loopback. A debug affordance failing must not fail a session, so a
     *         caller is expected to warn and continue without one, which is
     *         {@link #openAt}'s posture that trouble here costs a convenience and never correctness.
     */
    public StoreConsole console(int port) {
        return console(port, StoreConsole::verifyLoopbackOnly);
    }

    /** {@link #console(int)} with the bind check as a seam, for the test that forces it to fail. */
    StoreConsole console(int port, StoreConsole.BindCheck bindCheck) {
        return StoreConsole.open(connection, url, port, bindCheck);
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
     * What this open released from its home, zero for an in-memory store and for every open of a
     * home this JVM has already swept. The store carries it rather than logging it itself because
     * the once-per-JVM guard makes the reporter whichever opener ran first, and that is not the same
     * caller on every path: an ordinary build reaches the store through fact capture, while a
     * {@code graphitron:dev} session whose initial run is skipped reaches it through the session's
     * own open. {@link Reaped#report} is the sentence both of them log.
     */
    public Reaped reaped() {
        return reaped;
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
     *
     * <p>Close every {@link StoreReader} minted from this store first. A reader is a second
     * connection off the same database rather than a store handle, so it is not counted among the
     * holders this close consults, and the last handle to let a file go compacts it: that shuts the
     * database, and a reader still on it fails rather than degrades. Nothing orders this for a
     * caller, so a caller that holds both owns the order.
     */
    @Override
    public void close() {
        // Idempotent, because the handle count behind a file-backed store cannot survive a double
        // decrement: a second close would release a slot this store never held and let the next
        // compaction run under a live holder.
        if (closed) {
            return;
        }
        closed = true;
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
        } else if (location != null) {
            compactIfLast();
        }
        closeQuietly(connection);
    }

    /**
     * The {@link #OPEN_HANDLES} key for a store location: absolute and normalised, so every
     * spelling of one directory counts against one file. A path that cannot be normalised is used
     * as given, which counts it as its own file and costs a compaction rather than risking one.
     */
    private static Path handleKey(Path location) {
        try {
            return location.toAbsolutePath().normalize();
        } catch (RuntimeException e) {
            return location;
        }
    }

    /** Reserves a handle on {@code location}, which {@link #release} gives back. */
    private static void reserve(Path location) {
        OPEN_HANDLES.merge(handleKey(location), 1, Integer::sum);
    }

    /** Gives back a handle reserved by {@link #reserve}, compacting nothing. */
    private static void release(Path location) {
        OPEN_HANDLES.computeIfPresent(handleKey(location), (key, held) -> held <= 1 ? null : held - 1);
    }

    /**
     * Gives this store's handle back and, if it was the last one on the file, compacts before the
     * connection closes.
     *
     * <p>Compacting is what keeps the file a cache rather than a ledger. A plain close gives H2 its
     * default 200 ms of compaction, which on a store that has been cleared and rewritten a few
     * hundred times reclaims nothing, so the file grows without bound while the rows in it do not.
     * {@code SHUTDOWN COMPACT} rewrites the live pages and drops the rest.
     *
     * <p>It runs inside {@link Map#compute} deliberately: the lock that call holds for the key is
     * what stops an opener reserving this location while the database is being shut down. The cost
     * is recorded rather than logged, because this package reports and its callers log.
     */
    private void compactIfLast() {
        OPEN_HANDLES.compute(handleKey(location), (key, held) -> {
            if (held != null && held > 1) {
                return held - 1;
            }
            long before = fileBytes();
            long startedAt = System.nanoTime();
            try (Statement statement = connection.createStatement()) {
                statement.execute("SHUTDOWN COMPACT");
            } catch (SQLException e) {
                // A store that will not compact is a store that keeps its dead pages: worth
                // nothing to a caller, and never worth failing a build that has already produced
                // its output.
            }
            compaction = new Compaction(
                (System.nanoTime() - startedAt) / 1_000_000L, before, fileBytes());
            return null;
        });
    }

    /** The database file's size right now, or {@code -1} where it cannot be read. */
    private long fileBytes() {
        try {
            return Files.size(location.resolve(DATABASE + ".mv.db"));
        } catch (IOException | RuntimeException e) {
            return -1L;
        }
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
     * A lock budget, and deliberately nothing else. {@code DB_CLOSE_ON_EXIT=FALSE} is absent
     * because it suppressed H2's shutdown hook, and the only store that needs its database to
     * outlive a handle is the in-memory one, held open by {@code DB_CLOSE_DELAY=-1} on a different
     * URL. A file-backed store is meant to be left on disk, so H2 closing it at JVM exit is what
     * it wanted anyway.
     *
     * <p>{@code AUTO_SERVER=TRUE} is absent for a stronger reason than preference: in mixed mode
     * the holding process records the port of an embedded server in a {@code store.lock.db} beside
     * the database, and every later opener decides whether that holder is still alive by connecting
     * to the port and reading a handshake off the socket. H2 gives that read no timeout, so an
     * opener that reaches something which accepts and never answers blocks forever, under a
     * JVM-wide monitor, with no property reaching it. Two ordinary accidents are that something: a
     * holder suspended by a Ctrl-Z or a debugger breakpoint, and a lock file outliving a
     * {@code kill -9}'d holder whose ephemeral port some unrelated process later listens on, which
     * wedges every opener in the workspace until somebody deletes the cache directory by hand. An
     * open that cannot time out is not a cost a cache may impose on a build, and {@link #openAt}'s
     * promise that any failure to open costs warmth and never correctness is defeated by exactly
     * one outcome: a block, which is not a failure. Without the flag H2 writes no lock file at all
     * and takes the MVStore's own operating-system lock, which is released with the process and
     * reports a held file as {@code 90020} in well under a second, straight into the fallback that
     * was written for it.
     *
     * <p>What that gives up is one file shared by two <em>processes</em>. Nothing inside a process
     * changes, which is where the sharing that matters lives: see {@link #openAt}.
     *
     * <p>The lock timeout is raised from H2's one-second default because concurrent writers of one
     * file serialize on rows a whole capture transaction holds, and a writer that waits its turn
     * beats one that falls back cold. It is not the right budget for every row a capture takes; the
     * capture narrows it where waiting buys nothing, which is why the value is named rather than
     * spelled here alone.
     */
    private static String fileUrl(Path directory) {
        return "jdbc:h2:file:" + directory.toAbsolutePath().resolve(DATABASE)
            + ";LOCK_TIMEOUT=" + FILE_LOCK_MILLIS;
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

    /**
     * The boot-time derivation: rewrites {@code meta_materialize_dependency} from the freshly
     * created schema's stored view definitions, before any refresh can read it. Runs where the
     * schema is created rather than on every open, because the rows are a function of the DDL
     * alone: a warm store persisted them under a stamp naming this same DDL and generator
     * version, so what it holds is byte for byte what this call would write.
     */
    private static void deriveDependencies(Connection connection) {
        MaterializeDependencies.populate(DSL.using(connection, SQLDialect.H2));
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
