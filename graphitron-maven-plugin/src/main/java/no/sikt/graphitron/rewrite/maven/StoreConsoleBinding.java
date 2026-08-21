package no.sikt.graphitron.rewrite.maven;

/**
 * POM XML binding for the {@code <storeConsole>} block: the read-only SQL console onto the
 * {@code graphitron:dev} session's own fact store, which lets a developer query the rows the
 * session is answering from with {@code psql}.
 *
 * <pre>{@code
 * <storeConsole>
 *   <enabled>true</enabled>
 *   <port>0</port>             <!-- omit for an ephemeral port, which is the encouraged shape -->
 * </storeConsole>
 * }</pre>
 *
 * <p>Both fields have an environment-variable override that wins over the POM, so turning the
 * console on is a thing a developer does to one shell rather than to the checked-in file:
 * {@code GRAPHITRON_DEV_STORE_CONSOLE} and {@code GRAPHITRON_DEV_STORE_CONSOLE_PORT}.
 * Reconciliation lives in {@code DevMojo.resolveStoreConsole}: absent or disabled means no console
 * and no port bound, and the session says in its log how to turn it on.
 *
 * <p>An unset {@code <port>} means an ephemeral one, and that default is the point rather than a
 * convenience. Several dev sessions in one workspace is the ordinary case in a multi-module
 * reactor, a fixed default would make the second session's console fail to open on a port the
 * first one holds, and a well-known port on a developer's machine is exactly the kind of listener
 * that gets found by something other than its owner. A pinned {@code <port>} stays available for a
 * developer who wants a stable connect line, and pinning it is then a deliberate choice.
 */
public class StoreConsoleBinding {

    /** Whether to open the console at all. Absent or false means no console and no port bound. */
    Boolean enabled;

    /**
     * The port to bind. Absent means an ephemeral one, and the session logs the port it got;
     * pin a value only for a stable connect line, and expect a collision when two sessions run.
     */
    Integer port;
}
