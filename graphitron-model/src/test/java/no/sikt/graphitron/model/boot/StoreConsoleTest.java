package no.sikt.graphitron.model.boot;

import no.sikt.graphitron.model.test.FactStores;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The read-only SQL console a store mints onto itself: what it shows, what it refuses, and the one
 * thing about it that is a requirement rather than a convenience.
 *
 * <p>Everything here reads the console through its own database rather than over the wire, as the
 * user a wire client becomes, which is where every claim except the printed command line actually
 * lives: what that user may do is a property of its rights, and liveness is a property of the link
 * reading through. What only a real client can
 * answer for, that the command the session prints runs verbatim, is {@link StoreConsolePsqlTest}'s
 * subject.
 *
 * <p>One store for the whole class. A store boot executes the fact schema's two thousand statements,
 * and no case here writes anything the next one could read: the only writer is the liveness case,
 * which writes a row into a relation nothing else asserts a count over.
 */
class StoreConsoleTest {

    @RegisterExtension
    static final FactStores.ClassStore STORE = FactStores.perClass();

    /**
     * The two ports the cases that must pin one use, and they are below the operating system's
     * ephemeral range on purpose. A case that reserved a port by opening and closing a socket would
     * be handing it back to exactly the range every other listener in a parallel build is drawing
     * from, and the second or two before the console binds it is enough for something else to take
     * it; a port the operating system never hands out cannot be taken that way. One each, so a
     * listener a failing case left behind cannot be read as the other case's.
     */
    private static final int PINNED_PORT = 18471;

    /** @see #PINNED_PORT */
    private static final int PINNED_PORT_FOR_REFUSAL = 18472;

    @Test
    void everyRelationOfTheStoreIsLinkedAndReadable() throws SQLException {
        var store = STORE.handle();
        try (var console = store.console(0)) {
            assertThat(console.relationCount())
                .as("every relation of the store's PUBLIC schema is linked")
                .isEqualTo(relationCount(store.connection()));
            assertThat(count(console, "SELECT count(*) FROM information_schema.tables "
                + "WHERE table_schema = 'PUBLIC'"))
                .as("and the console's own catalog holds exactly those")
                .isEqualTo(console.relationCount());
            // Most of the fact schema is views, and a view links and reads like a table, which is
            // the property that makes the console useful rather than a table-only door.
            assertThat(count(console, "SELECT count(*) FROM meta_family"))
                .as("a view relation reads through the link")
                .isPositive();
        }
    }

    @Test
    void aRowWrittenAfterTheConsoleOpenedIsVisibleThroughIt() throws SQLException {
        var store = STORE.handle();
        try (var console = store.console(0)) {
            long before = count(console, "SELECT count(*) FROM store_graph");
            try (Statement writer = store.connection().createStatement()) {
                writer.execute("INSERT INTO store_graph (graph_name, base_dir, last_captured) "
                    + "VALUES ('after-open', '/tmp', CURRENT_TIMESTAMP)");
            }
            assertThat(count(console, "SELECT count(*) FROM store_graph"))
                .as("the console shows the session's current rows, not a snapshot taken when it opened")
                .isEqualTo(before + 1);
        }
    }

    /**
     * The refusals, each arm rather than a sample, since this is the guard a developer relies on
     * while poking around: the client holds {@code SELECT} and nothing else, so DML is refused, and
     * so is DDL that would drop a link or put something beside it.
     */
    @Test
    void writesThroughTheConsoleAreRefusedAndTheStoreIsUnchanged() throws SQLException {
        var store = STORE.handle();
        long before = count(store.connection(), "SELECT count(*) FROM store_stamp");
        try (var console = store.console(0);
             Connection client = client(console)) {
            for (String write : List.of(
                "INSERT INTO store_stamp (singleton, ddl_hash, generator_version) VALUES ('Y', 'h', 'v')",
                "UPDATE store_stamp SET ddl_hash = 'tampered'",
                "DELETE FROM store_stamp",
                "DROP TABLE store_stamp",
                "CREATE TABLE beside_the_links (i INT)")) {
                assertThatThrownBy(() -> execute(client, write))
                    .as("%s is refused through the console", write)
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("Not enough rights");
            }
            assertThat(count(client, "SELECT count(*) FROM store_stamp"))
                .as("and the relation is still there to read")
                .isEqualTo(before);
        }
        assertThat(count(store.connection(), "SELECT count(*) FROM store_stamp"))
            .as("and the store's rows are what they were")
            .isEqualTo(before);
    }

    /**
     * What makes "read-only" a statement about the client rather than about the links. Every
     * statement here is one an H2 admin may run and each is a way out of the database: Java in the
     * session's JVM, the developer's files, or a fresh writable link onto the store. The client is
     * not an admin, so H2 refuses all of them, and it refuses the statements that would make it one.
     *
     * <p>The file arms name a path that does not exist, so a refusal is the rights check and not the
     * filesystem's answer; the write arm's target is asserted absent afterwards.
     */
    @Test
    void aClientCannotRunCodeTouchFilesOrOpenItsOwnLinks(@TempDir Path dir)
        throws SQLException {
        var store = STORE.handle();
        Path target = dir.resolve("written-by-the-console.txt");
        try (var console = store.console(0);
             Connection client = client(console)) {
            for (String escape : List.of(
                "CREATE ALIAS run AS 'String run(String c) { return c; }'",
                "CREATE ALIAS prop FOR \"java.lang.System.getProperty\"",
                "CREATE AGGREGATE agg FOR \"java.lang.Object\"",
                "SELECT FILE_READ('" + dir.resolve("absent.txt") + "', 'UTF-8')",
                "SELECT FILE_WRITE('x', '" + target + "')",
                "CALL CSVWRITE('" + target + "', 'SELECT 1')",
                "SELECT * FROM CSVREAD('" + dir.resolve("absent.csv") + "')",
                "SCRIPT",
                "SCRIPT TO '" + target + "'",
                "RUNSCRIPT FROM '" + dir.resolve("absent.sql") + "'",
                "BACKUP TO '" + target + "'",
                "CREATE LINKED TABLE writable(NULL, 'jdbc:h2:mem:elsewhere', '', '', 'PUBLIC.T')",
                "CREATE LOCAL TEMPORARY LINKED TABLE writable(NULL, 'jdbc:h2:mem:elsewhere', '', '', 'PUBLIC.T')",
                "CALL LINK_SCHEMA('ELSEWHERE', '', 'jdbc:h2:mem:elsewhere', '', '', 'PUBLIC')",
                "ALTER USER \"graphitron\" ADMIN TRUE",
                "CREATE USER another PASSWORD 'p' ADMIN",
                "SHUTDOWN")) {
                assertThatThrownBy(() -> execute(client, escape))
                    .as("%s is refused to a console client", escape)
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("Admin rights are required");
            }
            assertThat(target).as("and nothing was written").doesNotExist();
            assertThat(count(client, "SELECT count(*) FROM meta_family"))
                .as("the console is still serving after every refusal, SHUTDOWN included")
                .isPositive();
        }
    }

    /**
     * The guard under the client's rights: the links reach the store as its own select-only reader,
     * so the console database holds no connection that could write to the store at all.
     */
    @Test
    void theLinksReachTheStoreAsItsSelectOnlyReader() throws SQLException {
        var store = STORE.handle();
        // The name as the DDL's "Store access" section spells it.
        String readers = "SELECT count(*) FROM information_schema.sessions "
            + "WHERE user_name = 'GRAPHITRON_READER'";
        long before = count(store.connection(), readers);
        try (var console = store.console(0)) {
            assertThat(count(store.connection(), readers))
                .as("the one connection the %d links share is the reader's, not the store admin's",
                    console.relationCount())
                .isEqualTo(before + 1);
        }
    }

    @Test
    void linkingEveryRelationCostsTheStoreOneConnection() throws SQLException {
        var store = STORE.handle();
        long before = count(store.connection(), "SELECT count(*) FROM information_schema.sessions");
        try (var console = store.console(0)) {
            assertThat(count(store.connection(), "SELECT count(*) FROM information_schema.sessions"))
                .as("H2 pools link connections per url and user, so %d links cost one connection "
                    + "rather than %d", console.relationCount(), console.relationCount())
                .isEqualTo(before + 1);
        }
    }

    /**
     * The console is a second database in PostgreSQL mode precisely so that this stays true: that
     * mode is a creation-time property, so putting the store itself in it would land under every
     * generator query and under the codegen bootstrap that reads its model off exactly this boot.
     */
    @Test
    void theStoresOwnModeIsUntouched() throws SQLException {
        var store = STORE.handle();
        String mode = "SELECT setting_value FROM information_schema.settings WHERE setting_name = 'MODE'";
        assertThat(string(store.connection(), mode)).isEqualTo("REGULAR");
        try (var console = store.console(0)) {
            assertThat(string(store.connection(), mode))
                .as("opening a console does not move the store's mode")
                .isEqualTo("REGULAR");
            try (Connection client = client(console)) {
                assertThat(string(client, mode))
                    .as("the console is the database in PostgreSQL mode")
                    .isEqualTo("PostgreSQL");
            }
        }
    }

    @Test
    void anEphemeralPortIsBoundAndReported() {
        var store = STORE.handle();
        try (var first = store.console(0); var second = store.console(0)) {
            assertThat(first.port()).as("the bound port is reported, never the 0 that was asked for")
                .isPositive();
            assertThat(second.port())
                .as("two consoles at the ephemeral default get different ports, which is the "
                    + "multi-session case that default exists for")
                .isNotEqualTo(first.port());
            assertThat(reachable(first.port())).isTrue();
            assertThat(reachable(second.port())).isTrue();
        }
    }

    /**
     * Release is asserted by rebinding, and by H2's own server state, rather than by probing the
     * closed port for silence: a port nobody holds belongs to nobody, so something else listening
     * there a moment later would read as a leak.
     */
    @Test
    void closingFreesThePortAndDropsTheConsoleDatabase() throws Exception {
        var store = STORE.handle();
        String sessions = "SELECT count(*) FROM information_schema.sessions";
        long before = count(store.connection(), sessions);
        int port = PINNED_PORT;
        StoreConsole closed;
        try (var console = store.console(port)) {
            assertThat(console.port()).isEqualTo(port);
            assertThat(console.running()).as("serving while open").isTrue();
            closed = console;
        }
        assertThat(closed.running()).as("and not serving once closed").isFalse();
        assertThat(count(store.connection(), sessions))
            .as("the console database went with the listener, taking the link connection it held on "
                + "the store; one left in the JVM by DB_CLOSE_DELAY would still hold that")
            .isEqualTo(before);
        try (var reopened = store.console(port)) {
            assertThat(reopened.port())
                .as("a second console binds the port the first one released, which it could not do "
                    + "if close had left the listener up")
                .isEqualTo(port);
        }
    }

    @Test
    void theConnectCommandIsCompleteAndCarriesNoSecret() {
        var store = STORE.handle();
        try (var console = store.console(0)) {
            assertThat(console.connectCommand())
                .as("the whole point is that a developer pastes this without editing it")
                .isEqualTo("PGPASSFILE=" + console.passwordFile() + " psql -X -w -h 127.0.0.1 -p "
                    + console.port() + " -U graphitron -d store")
                .doesNotContain("<")
                .as("and it names where the password is rather than the password, because this "
                    + "line goes to the log and to whoever asks the MCP server")
                .doesNotContain(console.password());
            assertThat(console.coordinates())
                .as("an agent reads the same values as fields, off the same handle")
                .isEqualTo(new StoreConsole.Coordinates("127.0.0.1", console.port(), "graphitron",
                    console.passwordFile().toString(), "store", console.relationCount(),
                    console.connectCommand()));
            assertThat(console.coordinates().toString())
                .as("and no field of them is the password")
                .doesNotContain(console.password());
        }
    }

    /**
     * Loopback keeps other machines out and nobody else: every account and process on this one can
     * reach the port. What keeps them out is that the password exists in one file only its owner
     * can read, which is also the only form libpq accepts a password file in.
     */
    @Test
    void thePasswordIsInAFileOnlyItsOwnerCanRead() throws IOException {
        var store = STORE.handle();
        Path file;
        try (var console = store.console(0)) {
            file = console.passwordFile();
            assertThat(Files.readString(file, StandardCharsets.UTF_8))
                .as("one line of libpq's password-file format, naming exactly this console")
                .isEqualTo("127.0.0.1:" + console.port() + ":store:graphitron:"
                    + console.password() + "\n");
            assumeTrue(Files.getFileStore(file).supportsFileAttributeView("posix"),
                "this filesystem has no POSIX permissions to read");
            assertThat(Files.getPosixFilePermissions(file))
                .as("readable and writable by the owner, and by nobody else")
                .isEqualTo(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        }
        assertThat(file).as("and it goes when the console does").doesNotExist();
    }

    /**
     * The password is minted per console, so the pair every installation used to share opens
     * nothing, and neither does a password read off an earlier session.
     */
    @Test
    void neitherAGuessablePasswordNorAnEarlierConsolesOpensThisOne() throws SQLException {
        var store = STORE.handle();
        String earlier;
        try (var console = store.console(0)) {
            earlier = console.password();
            assertThat(earlier).as("256 bits, hex").matches("[0-9a-f]{64}");
        }
        try (var console = store.console(0)) {
            assertThat(console.password()).as("a fresh one each start").isNotEqualTo(earlier);
            for (String wrong : List.of("graphitron", "", earlier)) {
                assertThatThrownBy(() -> client(console.consoleUrl(), "graphitron", wrong))
                    .as("the password '%s' is refused", wrong)
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("Wrong user name or password");
            }
            try (Connection client = client(console)) {
                assertThat(string(client, "SELECT 1")).as("and its own is accepted").isEqualTo("1");
            }
        }
    }

    /**
     * The item's one hard requirement, asserted directly rather than inferred from H2's status
     * string, which reports "only local connections" even when the listener is on every interface:
     * that string describes a peer check H2 runs after accepting, not the bind.
     *
     * <p>This passes here because the module's surefire fork sets {@code h2.bindAddress} on the
     * command line, which is the only time H2 honours it: it reads the property once, when its first
     * class initialises. A host that already loaded H2 cannot be confined that way, which is what
     * the next case is about.
     */
    @Test
    void theListenerIsOnLoopbackAndNowhereElse() throws IOException {
        var store = STORE.handle();
        List<InetAddress> elsewhere = StoreConsole.nonLoopbackIPv4();
        assumeFalse(elsewhere.isEmpty(), "this host has no non-loopback IPv4 address to probe");
        try (var console = store.console(0)) {
            var accepted = new ArrayList<String>();
            for (InetAddress address : elsewhere) {
                if (StoreConsole.reachable(address, console.port())) {
                    accepted.add(address.getHostAddress());
                }
            }
            assertThat(accepted).as("the console's port is not open to the network").isEmpty();
            assertThat(reachable(console.port())).as("and loopback still works").isTrue();
        }
    }

    /**
     * The other half of the requirement: a listener that cannot prove it is confined does not stay
     * up, so the failure mode is a missing debug tool rather than a port open to the network. Driven
     * through the bind-check seam, because in production the property may have been read before the
     * goal could set it and a test cannot reproduce that ordering inside a fork that already booted
     * a store.
     */
    @Test
    void aConsoleThatCannotProveItsBindDoesNotStayUp() throws Exception {
        var store = STORE.handle();
        int port = PINNED_PORT_FOR_REFUSAL;
        var checked = new ArrayList<Integer>();
        assertThatThrownBy(() -> store.console(port, bound -> {
            checked.add(bound);
            throw new IOException("the listener accepted a connection on 198.51.100.7");
        }))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("could not start")
            .hasMessageContaining("198.51.100.7");
        assertThat(checked)
            .as("the check ran once, against the port that was actually bound")
            .containsExactly(port);
        // Nothing is left listening, asserted by binding the port rather than by probing it for
        // silence: see the release case above for why absence is not a safe signal here.
        try (var confined = store.console(port)) {
            assertThat(confined.port())
                .as("the refused console released the port, so a good one can have it")
                .isEqualTo(port);
        }
    }

    // ---- helpers ----

    /** A connection onto the console database, which is what a client over the wire becomes. */
    private static Connection client(StoreConsole console) throws SQLException {
        return client(console.consoleUrl(), console.user(), console.password());
    }

    private static Connection client(String url, String user, String password) throws SQLException {
        var source = new org.h2.jdbcx.JdbcDataSource();
        source.setURL(url);
        source.setUser(user);
        source.setPassword(password);
        return source.getConnection();
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static long count(StoreConsole console, String sql) throws SQLException {
        try (Connection client = client(console)) {
            return count(client, sql);
        }
    }

    private static long count(Connection connection, String sql) throws SQLException {
        return Long.parseLong(string(connection, sql));
    }

    private static String string(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getString(1);
        }
    }

    private static long relationCount(Connection storeConnection) throws SQLException {
        return count(storeConnection, "SELECT count(*) FROM information_schema.tables "
            + "WHERE table_schema = 'PUBLIC'");
    }

    private static boolean reachable(int port) {
        return StoreConsole.reachable(InetAddress.getLoopbackAddress(), port);
    }
}
