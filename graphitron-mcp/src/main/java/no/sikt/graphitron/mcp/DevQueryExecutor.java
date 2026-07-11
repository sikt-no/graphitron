package no.sikt.graphitron.mcp;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.ServiceLoader;

/**
 * The host half of the R428 in-process query execution: opens the dev database connection, loads
 * the generated {@code GraphitronDevExecutor} from the R410-compiled classes, reflects its one
 * JDK-typed {@code execute} entry point, and hands back the JSON result string.
 *
 * <p>The reflection boundary is JDK types only ({@code java.sql.Connection}, {@code String},
 * {@code java.util.Map}), so no jOOQ or graphql-java type crosses between this host and the
 * generated classloader, and this class needs no generated classpath to unit-test against a
 * synthetic executor.
 *
 * <p>Each call builds a fresh {@link URLClassLoader} over {@code target/graphitron-classes}
 * (first, so a fresh copy shadows any stale one in {@code target/classes}) plus the consumer's
 * compile classpath, parented on the <em>platform</em> loader: the generated world resolves jOOQ,
 * graphql-java, and the JDBC driver exclusively from the consumer's own dependencies, never from
 * the plugin realm, so the dev execution sees exactly the versions the application would. A fresh
 * loader per call means the executor is always the current compile round's bytecode; dev-loop
 * latencies absorb the reload cost.
 *
 * <p>Transactions, session hooks, and rollback all run <em>inside</em> the generated executor via
 * the R429 machinery ({@code ROLLBACK_ONLY} commit policy). The host adds one defense-in-depth
 * measure on the way out: if the connection comes back with an open transaction (an executor bug
 * or a mid-operation crash), it is rolled back before close rather than left to the driver's
 * close-time default.
 */
public final class DevQueryExecutor {

    /** Simple name of the generated executor class, emitted into the consumer's output package. */
    static final String EXECUTOR_CLASS = "GraphitronDevExecutor";
    static final String EXECUTE_METHOD = "execute";

    /**
     * Everything the host needs to locate and drive the generated executor: the consumer's
     * {@code outputPackage} (the executor's package), the R410 class output dir, and the
     * consumer's compile classpath. All plain values, supplied by the dev Mojo.
     */
    public record Wiring(String outputPackage, Path classesDir, List<Path> classpath) {
        public Wiring {
            Objects.requireNonNull(outputPackage, "outputPackage");
            Objects.requireNonNull(classesDir, "classesDir");
            classpath = List.copyOf(classpath);
        }

        String executorClassName() {
            return outputPackage + "." + EXECUTOR_CLASS;
        }
    }

    /**
     * The dev database coordinates the connection is opened from. Resolution (pom configuration,
     * environment-variable overrides, the {@code @file} claims form) happens upstream; by the
     * time this record exists the values are final.
     */
    public record DbConfig(String url, String user, String password, String dialect, String claims) {
        public DbConfig {
            Objects.requireNonNull(url, "url");
            Objects.requireNonNull(dialect, "dialect");
        }
    }

    /** A dev execution failure with a message fit to surface verbatim as the MCP tool result. */
    public static final class DevExecutionException extends Exception {
        @java.io.Serial
        private static final long serialVersionUID = 1L;

        public DevExecutionException(String message) {
            super(message);
        }

        public DevExecutionException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private DevQueryExecutor() {}

    /**
     * Executes one GraphQL operation through the generated executor and returns the JSON result
     * string ({@code ExecutionResult.toSpecification()} serialized by the executor). GraphQL-level
     * errors come back inside that JSON; a thrown {@link DevExecutionException} means the
     * execution machinery itself failed (no executor class, no accepting driver, a connect-hook
     * rejection), with the underlying message preserved verbatim so the caller can act on it.
     */
    public static String execute(Wiring wiring, DbConfig db, String query,
            Map<String, Object> variables, Map<String, Object> contextArgs)
            throws DevExecutionException {
        try (URLClassLoader loader = newLoader(wiring)) {
            var executeMethod = resolveExecuteMethod(wiring, loader);
            try (Connection connection = openConnection(loader, db)) {
                try {
                    return invoke(executeMethod, loader, connection, db, query, variables, contextArgs);
                } finally {
                    rollbackLeftovers(connection);
                }
            } catch (SQLException e) {
                throw new DevExecutionException("Dev database connection failed: " + e.getMessage(), e);
            }
        } catch (java.io.IOException e) {
            throw new DevExecutionException("Failed to close the dev execution classloader: " + e.getMessage(), e);
        }
    }

    private static URLClassLoader newLoader(Wiring wiring) throws DevExecutionException {
        var urls = new URL[wiring.classpath().size() + 1];
        try {
            // graphitron-classes first: the invariant that a fresh generated .class shadows any
            // stale copy a consumer build may have left in target/classes.
            urls[0] = wiring.classesDir().toUri().toURL();
            for (int i = 0; i < wiring.classpath().size(); i++) {
                urls[i + 1] = wiring.classpath().get(i).toUri().toURL();
            }
        } catch (java.net.MalformedURLException e) {
            throw new DevExecutionException("Invalid classpath entry for dev execution: " + e.getMessage(), e);
        }
        return new URLClassLoader("graphitron-dev-execute", urls, ClassLoader.getPlatformClassLoader());
    }

    private static java.lang.reflect.Method resolveExecuteMethod(Wiring wiring, ClassLoader loader)
            throws DevExecutionException {
        try {
            Class<?> executor = Class.forName(wiring.executorClassName(), true, loader);
            return executor.getMethod(EXECUTE_METHOD,
                Connection.class, String.class, String.class, Map.class, String.class, Map.class);
        } catch (ClassNotFoundException e) {
            throw new DevExecutionException(
                "The generated " + wiring.executorClassName() + " is not on the dev classpath. "
                    + "It is compiled by the dev loop's incremental compiler; check that compilation "
                    + "succeeded (the diagnostics tool shows compile errors) and that the schema is "
                    + "not a federation subgraph (federation execution is not supported yet).", e);
        } catch (NoSuchMethodException e) {
            throw new DevExecutionException(
                "The generated " + wiring.executorClassName() + " does not expose the expected "
                    + EXECUTE_METHOD + " signature; the compiled classes are likely stale. "
                    + "Save a schema file to trigger a regeneration, or restart graphitron:dev.", e);
        }
    }

    /**
     * Opens the dev connection through the consumer's own JDBC driver, discovered on the project
     * classloader. {@code DriverManager} is deliberately bypassed: it resolves drivers against the
     * caller's classloader (the plugin realm, where the consumer's driver does not live).
     */
    private static Connection openConnection(ClassLoader loader, DbConfig db) throws DevExecutionException {
        var props = new Properties();
        if (db.user() != null) {
            props.setProperty("user", db.user());
        }
        if (db.password() != null) {
            props.setProperty("password", db.password());
        }
        for (Driver driver : ServiceLoader.load(Driver.class, loader)) {
            try {
                if (!driver.acceptsURL(db.url())) {
                    continue;
                }
                Connection connection = driver.connect(db.url(), props);
                if (connection != null) {
                    return connection;
                }
            } catch (SQLException e) {
                throw new DevExecutionException("Dev database connection failed: " + e.getMessage(), e);
            }
        }
        throw new DevExecutionException(
            "No JDBC driver on the project classpath accepts the configured dev database url '"
                + db.url() + "'. The driver the application uses (and jOOQ codegen needs) should "
                + "already be a project dependency.");
    }

    private static String invoke(java.lang.reflect.Method executeMethod, ClassLoader loader,
            Connection connection, DbConfig db, String query,
            Map<String, Object> variables, Map<String, Object> contextArgs)
            throws DevExecutionException {
        // graphql-java and jOOQ both consult the thread context classloader for service loading;
        // point it at the generated world for the duration of the call.
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        thread.setContextClassLoader(loader);
        try {
            return (String) executeMethod.invoke(null,
                connection, db.dialect(), query, variables, db.claims(), contextArgs);
        } catch (IllegalAccessException e) {
            throw new DevExecutionException("Could not invoke the generated executor: " + e.getMessage(), e);
        } catch (java.lang.reflect.InvocationTargetException e) {
            // The hook is the validator and its errors are the feedback loop: surface the
            // executor-side failure (connect-hook rejection, fail-loud missing claims, SQL error)
            // with its own message, verbatim.
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new DevExecutionException(cause.getMessage() != null
                ? cause.getMessage()
                : cause.getClass().getName(), cause);
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    /**
     * Defense-in-depth on the way out: everything should have settled inside the executor (R429
     * demarcates and {@code ROLLBACK_ONLY} rolls back), but if a crash left a transaction open,
     * roll it back explicitly rather than trusting the driver's close-time default.
     */
    private static void rollbackLeftovers(Connection connection) {
        try {
            if (!connection.isClosed() && !connection.getAutoCommit()) {
                connection.rollback();
            }
        } catch (SQLException ignored) {
            // Close follows immediately; a rollback failure here has nothing actionable.
        }
    }
}
