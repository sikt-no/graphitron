package no.sikt.graphitron.model.boot;

import org.h2.tools.Server;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * A read-only SQL door onto a live fact store, speaking the PostgreSQL wire protocol on a loopback
 * port, so a developer debugging a wrong answer can query the rows the session is answering from.
 * {@link GraphitronModelStore#console(int)} mints it, for the reason that class mints
 * {@link GraphitronModelStore#reader(ReadBudget)}: the store's URL is private and a caller
 * reconstructing it would open a different database, look fine, and answer from nothing.
 *
 * <p>What it actually is: a <em>second</em> in-memory database, created in PostgreSQL mode, holding
 * one {@code READONLY} linked table per relation of the store, with an H2 PostgreSQL server in front
 * of it. Three measured constraints shaped that, and a future reader will otherwise rediscover each
 * one:
 *
 * <ul>
 *   <li>PostgreSQL mode is a <em>creation-time</em> property. Without {@code MODE=PostgreSQL} in the
 *       creation URL the first client connection dies with {@code Schema "PG_CATALOG" not found},
 *       and {@code SET MODE PostgreSQL} on a live connection does not retro-fit it. So this is a
 *       second database rather than a flag on the store's own: putting the session's store in that
 *       mode would land under every generator query and under the codegen bootstrap that reads its
 *       model off exactly that boot, giving a debug flag the power to change the session's SQL
 *       semantics.
 *   <li>Only {@code psql} (libpq) can speak to it. pgjdbc's startup queries include
 *       {@code SET extra_float_digits = 2}, which H2 rejects as a syntax error, and no combination
 *       of {@code assumeMinServerVersion}, {@code preferQueryMode} or {@code options} gets past it,
 *       so JDBC-based clients (DBeaver, IntelliJ's PostgreSQL driver) cannot connect. That is why
 *       {@link #connectCommand()} hands out a command line rather than a JDBC URL.
 *   <li>H2's own "only local connections" is a <em>peer check</em>, not a bind restriction. With
 *       {@code -pgAllowOthers} omitted, a session arriving on a non-loopback address is dropped, but
 *       the listener is still on every interface and a plain TCP connect to it is accepted. So the
 *       confinement rests on {@code h2.bindAddress} instead, and H2 reads that property once, when
 *       its first class initialises. A host that already loaded H2 cannot be confined by setting it
 *       afterwards, which is why {@link #open} verifies the bind rather than trusting the property,
 *       and why nothing here reads H2's status string as proof.
 * </ul>
 *
 * <p>Read-only is a property of who the client is, not only of the links. Loopback is not a
 * boundary between the accounts and processes of one machine, and an H2 admin can compile and run
 * Java ({@code CREATE ALIAS}), read and write files, and open a fresh writable link onto the store.
 * So a client is never the admin. The database is created by an owner whose password is minted
 * here and kept nowhere, and a client connects as {@link #USER}, which holds {@code SELECT} on the
 * linked relations and nothing else: H2 refuses it every statement that needs admin or schema
 * rights, which is the whole list above along with DML and DDL on the relations. Underneath, the
 * links are {@code READONLY} and reach the store as its own select-only reader, so the console
 * database holds no way to write to the store whoever is asking. Two things make that possible:
 *
 * <ul>
 *   <li>H2's PostgreSQL server issues {@code SET DEFAULT_NULL_ORDERING HIGH} at every connect, which
 *       requires admin, so a rights-limited user is ordinarily refused before it runs anything.
 *       {@code IGNORE_UNKNOWN_SETTINGS=TRUE} on the database name the server maps its key to makes
 *       H2 log that refusal and carry on, and the ordering is set once, in the creation URL,
 *       where the owner has the rights.
 *   <li>The client's password is minted per console and handed over as a file only this process's
 *       owner can read (see {@link #passwordFile()}), so reaching the port is not enough to log in,
 *       and a password from an earlier session opens nothing.
 * </ul>
 *
 * <p>What remains is what any read access carries: a client can read every fact, create
 * session-local temporary tables, and spend this JVM's memory and processor on a query.
 *
 * <p>Linked reads are live and cheap. A row committed to the store after the links were created is
 * visible on the next query, so the console shows the session's current rows rather than a snapshot
 * taken when it opened; and H2 pools link connections per URL and user, so N linked relations cost
 * the store one connection rather than N.
 *
 * <p>{@link #close()} stops the server and drops the console database, and touches the store not at
 * all.
 */
public final class StoreConsole implements AutoCloseable {

    /**
     * The user a client connects as: rights-limited, holding {@code SELECT} on the linked relations
     * and nothing else. The name is fixed, since it is not the secret; its password is minted per
     * console.
     */
    static final String USER = "graphitron";

    /**
     * The user that creates the console database and so is its admin. Its password is minted in
     * {@link #open} and kept nowhere, so nothing arriving on the port can become it.
     */
    private static final String OWNER = "graphitron_console_owner";

    /** The bytes of entropy in a minted password, which is hex-encoded and so needs no escaping. */
    private static final int SECRET_BYTES = 32;

    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * The database name a client passes as {@code -d}: the alias the PostgreSQL server maps onto the
     * console database, rather than that database's own name. The real name carries a {@link UUID},
     * for {@link GraphitronModelStore#open()}'s collision reason, and the alias is what keeps it out
     * of the printed command.
     */
    static final String DATABASE = "store";

    /** The host the listener is confined to, and the host {@link #connectCommand()} names. */
    static final String HOST = "127.0.0.1";

    /** How long a bind probe waits for a connect before reading the address as unreachable. */
    private static final int PROBE_TIMEOUT_MILLIS = 2_000;

    /**
     * The bind verification, as a seam. Production passes {@link #verifyLoopbackOnly}; a test passes
     * one that refuses, which is the only way to drive the arm where the listener could not be
     * confined on a host whose only address <em>is</em> loopback.
     */
    interface BindCheck {

        /**
         * @throws IOException if the listener on {@code port} is reachable anywhere but loopback, or
         *         not reachable on loopback at all
         */
        void verify(int port) throws IOException;
    }

    private final Server server;
    private final Connection connection;
    private final String url;
    private final int relationCount;
    private final String password;
    private final Path passwordFile;

    private StoreConsole(Server server, Connection connection, String url, int relationCount,
                         String password, Path passwordFile) {
        this.server = server;
        this.connection = connection;
        this.url = url;
        this.relationCount = relationCount;
        this.password = password;
        this.passwordFile = passwordFile;
    }

    /**
     * Builds the console over the store behind {@code storeConnection} and starts serving it.
     *
     * <p>Package-private, and it takes the store's URL, which is the whole reason
     * {@link GraphitronModelStore#console(int)} is the door: the URL never leaves that class.
     *
     * @param storeConnection the store's own connection, read for its relation names the same
     *        raw-JDBC way the stamp check reads its row, so the bootstrap keeps touching no
     *        generated class
     * @param storeUrl the store's private JDBC URL, which the link statements name
     * @param port the port to bind, or {@code 0} for an ephemeral one, which is the ordinary call
     * @param bindCheck the loopback confinement check to run once the server is up
     * @throws IllegalStateException if anything fails, the bind check included, having first closed
     *         whatever it had opened
     */
    static StoreConsole open(Connection storeConnection, String storeUrl, int port, BindCheck bindCheck) {
        List<String> relations = relationsOf(storeConnection, port);
        String name = "graphitron-console-" + UUID.randomUUID();
        String url = "jdbc:h2:mem:" + name;
        // DEFAULT_NULL_ORDERING here rather than left to the server's connect-time SET, which a
        // rights-limited client is refused: see the class comment.
        Connection console = connect(
            url + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DEFAULT_NULL_ORDERING=HIGH", port);
        String password = secret();
        try (Statement statement = console.createStatement()) {
            for (String relation : relations) {
                statement.execute(link(relation, storeUrl));
            }
            // After the links, so the grant covers them, and never ADMIN: everything a client must
            // not do is something H2 refuses a user without it.
            statement.execute("CREATE USER \"" + USER + "\" PASSWORD '" + password + "'");
            statement.execute("GRANT SELECT ON SCHEMA PUBLIC TO \"" + USER + "\"");
        } catch (SQLException e) {
            shutdownQuietly(console);
            throw failure(port, "the store's relations could not be linked: " + e.getMessage(), e);
        }
        Server server;
        try {
            // -pgDaemon so a console nobody closed cannot keep a JVM alive. A debug tool holding a
            // build or a test fork open after its work is done would be a worse failure than the
            // missing tool, and the console has nothing to flush on the way out.
            //
            // Neither -pgAllowOthers (H2's peer check stays on, which is defence in depth behind the
            // bind, never instead of it) nor -baseDir (nothing here is file-backed).
            //
            // IGNORE_UNKNOWN_SETTINGS on the mapped name is what lets the rights-limited client in:
            // the server sends settings at connect that only an admin may set, and with this H2
            // skips the ones it refuses rather than dropping the session.
            server = Server.createPgServer("-pgPort", Integer.toString(port), "-pgDaemon",
                "-key", DATABASE, "mem:" + name + ";IGNORE_UNKNOWN_SETTINGS=TRUE").start();
        } catch (SQLException e) {
            shutdownQuietly(console);
            throw failure(port, "the PostgreSQL server did not start: " + e.getMessage(), e);
        }
        try {
            bindCheck.verify(server.getPort());
        } catch (IOException e) {
            // A listener that cannot prove it is loopback-only does not stay up, so the failure mode
            // is a missing debug tool rather than a port open to the network. Through the same
            // teardown close() uses, so the refused console is given back exactly as thoroughly.
            tearDown(server, console, null);
            throw failure(server.getPort(), e.getMessage(), e);
        }
        Path passwordFile;
        try {
            passwordFile = writePasswordFile(server.getPort(), password);
        } catch (IOException | RuntimeException e) {
            // The same posture as the bind: a console whose password cannot be kept to its owner
            // does not stay up.
            tearDown(server, console, null);
            throw failure(server.getPort(),
                "its password file could not be written for the owner alone: " + e.getMessage(), e);
        }
        return new StoreConsole(server, console, url, relations.size(), password, passwordFile);
    }

    /**
     * The console database's own JDBC URL, so a test can assert what a client sees without paying
     * for a wire round trip. Not a production door: a consumer that hands the console on gets
     * {@link #coordinates()}, and a consumer that connects goes through the port. Publishing this
     * one costs nothing the store's privacy doctrine protects, since it names the console rather
     * than the store. It carries no settings, because a rights-limited client naming one is refused.
     */
    String consoleUrl() {
        return url;
    }

    /**
     * The port the listener actually bound, never the port asked for. A caller that passed {@code 0}
     * learns the real port here, which is what makes the ephemeral default usable: the log line and
     * the connect command carry a number rather than a placeholder.
     */
    public int port() {
        return server.getPort();
    }

    /** The host the listener is confined to. */
    public String host() {
        return HOST;
    }

    /** The user a client authenticates as. @see #USER */
    public String user() {
        return USER;
    }

    /**
     * This console's password, for a test that connects in-process as the client. Deliberately not
     * public: a consumer hands on {@link #passwordFile()}, never the secret itself.
     */
    String password() {
        return password;
    }

    /**
     * The file holding this console's password, in libpq's password-file format, so a client names
     * it as {@code PGPASSFILE} and the secret never appears on a command line, in a log, or in an
     * answer from a service that does not know who is asking. Only the owner of this process can
     * read it, and {@link #close()} deletes it.
     */
    public Path passwordFile() {
        return passwordFile;
    }

    /** The database name a client passes as {@code -d}. @see #DATABASE */
    public String database() {
        return DATABASE;
    }

    /** How many of the store's relations are linked, which is all of them. */
    public int relationCount() {
        return relationCount;
    }

    /**
     * The whole command that connects, assembled here rather than at each site that shows it: a
     * caller composing this line itself is a caller that can compose it wrong, and with an ephemeral
     * port the log is the only place the port exists at all.
     *
     * <p>{@code -X} is load-bearing rather than tidy. Without it psql runs the developer's
     * {@code ~/.psqlrc}, and a file written for a real PostgreSQL server sets things this server
     * does not have: {@code SET application_name}, {@code SET bytea_output} and their like are
     * syntax errors to H2, so the prompt opens under a wall of red and every later statement carries
     * the same errors ahead of its answer. A line handed out to be pasted has to work on a machine
     * whose owner has configured psql, and the cost is that the session does not inherit their
     * formatting preferences, which is the cheaper half of that trade.
     *
     * <p>{@code -w} keeps the line from ever prompting. Whoever cannot read the password file has
     * no password to type either, and an agent that shells out would hang on the prompt rather than
     * read the refusal.
     */
    public String connectCommand() {
        return "PGPASSFILE=" + shellQuote(passwordFile.toString()) + " psql -X -w -h " + HOST
            + " -p " + port() + " -U " + USER + " -d " + DATABASE;
    }

    /**
     * The same coordinates as fields, for a caller that would otherwise parse them back out of the
     * connect command. {@code connectCommand} rides along so a caller that shells out and a
     * developer reading the log run the same string from the same place.
     */
    public Coordinates coordinates() {
        return new Coordinates(HOST, port(), USER, passwordFile.toString(), DATABASE,
            relationCount, connectCommand());
    }

    /**
     * A live console's connection coordinates, for a consumer that hands them on rather than
     * connecting itself: the MCP {@code store.console} tool, which answers an agent in fields so it
     * never scrapes them out of log text. It names the password file and never the password: the
     * tool answers whoever reaches its port, and the file answers only the session's owner.
     */
    public record Coordinates(String host, int port, String user, String passwordFile,
                              String database, int relations, String connectCommand) {}

    /**
     * Whether this console is still serving. False once {@link #close()} has run, and it says so
     * from H2's own server state rather than by probing the port, which after a close would answer
     * for whoever holds that port next.
     */
    public boolean running() {
        return server.isRunning(false);
    }

    /**
     * Stops serving, drops the console database and deletes the password file. The store is left
     * exactly as it was: the links were this database's, and the one pooled connection they shared
     * goes with it.
     */
    @Override
    public void close() {
        tearDown(server, connection, passwordFile);
    }

    /**
     * Gives back everything a console holds: the listener first, so no client is mid-statement when
     * the database goes, then the database itself. One helper rather than two call sites, so the
     * console that failed its bind check is released exactly as completely as one that served.
     */
    private static void tearDown(Server server, Connection console, Path passwordFile) {
        server.stop();
        shutdownQuietly(console);
        if (passwordFile != null) {
            try {
                Files.deleteIfExists(passwordFile);
            } catch (IOException e) {
                // The password in it died with the database, so a file left behind opens nothing.
            }
        }
    }

    /** A fresh password: unpredictable, and hex so it needs no escaping in SQL or a password file. */
    private static String secret() {
        byte[] bytes = new byte[SECRET_BYTES];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    /**
     * Writes the client's password where only this process's owner can read it, as one line of
     * libpq's password-file format naming exactly this console.
     *
     * <p>Registered for deletion at exit as well as deleted by {@link #close()}, for the reason the
     * listener is a daemon: a console nobody closed must not leave something behind.
     */
    private static Path writePasswordFile(int port, String password) throws IOException {
        Path file = createOwnerOnly();
        try {
            Files.writeString(file,
                HOST + ":" + port + ":" + DATABASE + ":" + USER + ":" + password + "\n",
                StandardCharsets.UTF_8);
        } catch (IOException e) {
            Files.deleteIfExists(file);
            throw e;
        }
        file.toFile().deleteOnExit();
        return file;
    }

    /**
     * An empty file nobody but the owner can open, restricted in the call that creates it rather
     * than afterwards, so there is no moment at which it is readable by anyone else. On a POSIX
     * filesystem that is mode {@code 600}, which libpq also insists on before it will read a
     * password file. Elsewhere the access-control list is replaced with a single entry for the
     * owner, and a filesystem offering neither fails rather than leaving the password readable.
     */
    private static Path createOwnerOnly() throws IOException {
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            return Files.createTempFile("graphitron-console-", ".pgpass",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        }
        Path file = Files.createTempFile("graphitron-console-", ".pgpass");
        try {
            AclFileAttributeView acl = Files.getFileAttributeView(file, AclFileAttributeView.class);
            if (acl == null) {
                throw new IOException("this filesystem cannot restrict a file to its owner");
            }
            acl.setAcl(List.of(AclEntry.newBuilder()
                .setType(AclEntryType.ALLOW)
                .setPrincipal(acl.getOwner())
                .setPermissions(EnumSet.allOf(AclEntryPermission.class))
                .build()));
            return file;
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(file);
            throw e;
        }
    }

    /**
     * {@code value} as one shell word. A temporary directory is usually a plain path and is left
     * bare so the printed line stays readable; anything else is single-quoted.
     */
    private static String shellQuote(String value) {
        return value.matches("[A-Za-z0-9_./+=:@%-]+")
            ? value
            : "'" + value.replace("'", "'\\''") + "'";
    }

    /**
     * Every relation of the store's {@code PUBLIC} schema, base tables and views alike. Views are
     * most of the fact schema and they link and read like tables, so nothing here filters by type.
     */
    private static List<String> relationsOf(Connection storeConnection, int port) {
        String sql = "SELECT table_name FROM information_schema.tables "
            + "WHERE table_schema = 'PUBLIC' ORDER BY table_name";
        var relations = new ArrayList<String>();
        try (PreparedStatement statement = storeConnection.prepareStatement(sql);
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                relations.add(rows.getString(1));
            }
        } catch (SQLException e) {
            throw failure(port, "the store's relations could not be read: " + e.getMessage(), e);
        }
        if (relations.isEmpty()) {
            throw failure(port, "the store holds no relations to link", null);
        }
        return relations;
    }

    /**
     * One link statement. The link connects to the store as
     * {@link GraphitronModelStore#READER_USER}, the store's own select-only user, so nothing in the
     * console database, its owner included, holds a way to write to the store: the guard is the
     * store's grant rather than a promise made here. {@code READONLY} then refuses DML where it is
     * typed instead of at the store. The quoted name keeps the console's relation named exactly as
     * the store spells it, so an unquoted lowercase lookup from a client resolves the way it does
     * against the store itself.
     */
    private static String link(String relation, String storeUrl) {
        return "CREATE LINKED TABLE \"" + relation + "\"(NULL, '"
            + quote(GraphitronModelStore.readerUrl(storeUrl)) + "', '"
            + quote(GraphitronModelStore.READER_USER) + "', '', 'PUBLIC." + relation
            + "') READONLY";
    }

    private static String quote(String literal) {
        return literal.replace("'", "''");
    }

    /**
     * The measured confinement check: every non-loopback IPv4 address of this host must refuse a
     * connect to the port, and loopback must accept one. A host with no non-loopback address passes
     * the first half trivially, which is correct rather than lenient: there is nowhere else to reach
     * it from.
     */
    static void verifyLoopbackOnly(int port) throws IOException {
        for (InetAddress address : nonLoopbackIPv4()) {
            if (reachable(address, port)) {
                throw new IOException("the listener accepted a connection on "
                    + address.getHostAddress() + ", so it is not confined to " + HOST
                    + " (H2 reads h2.bindAddress once, when its first class initialises, so setting "
                    + "it after H2 has loaded has no effect)");
            }
        }
        if (!reachable(InetAddress.getLoopbackAddress(), port)) {
            throw new IOException("the listener refused a connection on " + HOST);
        }
    }

    /** Every non-loopback IPv4 address this host answers on: where the bind must not be reachable. */
    static List<InetAddress> nonLoopbackIPv4() throws IOException {
        var addresses = new ArrayList<InetAddress>();
        for (NetworkInterface nic : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            for (InetAddress address : Collections.list(nic.getInetAddresses())) {
                if (!address.isLoopbackAddress() && address instanceof Inet4Address) {
                    addresses.add(address);
                }
            }
        }
        return addresses;
    }

    /** Whether a TCP connect to {@code address:port} is accepted, which is the whole question. */
    static boolean reachable(InetAddress address, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(address, port), PROBE_TIMEOUT_MILLIS);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * As {@link #OWNER}, under a password minted for this one call: the first connection creates
     * the database and makes its user the admin, and the connection returned is the only thing
     * that will ever hold that identity.
     */
    private static Connection connect(String url, int port) {
        var source = new org.h2.jdbcx.JdbcDataSource();
        source.setURL(url);
        source.setUser(OWNER);
        source.setPassword(secret());
        try {
            return source.getConnection();
        } catch (SQLException e) {
            throw failure(port, "the console database could not be opened: " + e.getMessage(), e);
        }
    }

    /**
     * Drops the console database and releases the connection. {@code SHUTDOWN} rather than a plain
     * close for the reason {@link GraphitronModelStore#close()} states about its in-memory shape:
     * this database is held open by {@code DB_CLOSE_DELAY=-1}, so closing the connection alone would
     * leave it in the JVM.
     */
    private static void shutdownQuietly(Connection connection) {
        try (Statement statement = connection.createStatement()) {
            statement.execute("SHUTDOWN");
        } catch (SQLException e) {
            // A console holds nothing of record, so there is nothing to lose and nothing a caller
            // could do about it either.
        }
        try {
            connection.close();
        } catch (SQLException e) {
            // As above.
        }
    }

    /**
     * The one failure shape, naming the port because that is what a developer will suspect first,
     * and the reason because a console that will not open must say why rather than just be missing.
     */
    private static IllegalStateException failure(int port, String reason, Throwable cause) {
        return new IllegalStateException(
            "the fact-store console on port " + port + " could not start: " + reason, cause);
    }
}
