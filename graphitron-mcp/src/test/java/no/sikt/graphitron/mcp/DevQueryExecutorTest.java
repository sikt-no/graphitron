package no.sikt.graphitron.mcp;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link DevQueryExecutor}: the host half of the execute tool. The reflection boundary is JDK
 * types only, so these tests need no generated classpath: a synthetic
 * {@code com.example.GraphitronDevExecutor} and a fake JDBC driver are compiled with the system
 * javac into temp dirs, and the invoker is driven against them exactly as it drives the real
 * generated executor.
 *
 * <p>The fake driver's connection is a dynamic proxy that records lifecycle observations
 * (close, rollback) into system properties, the one channel trivially shared across the
 * host/child classloader boundary.
 */
class DevQueryExecutorTest {

    private static final String CLOSED = "graphitron.test.fakeConnection.closed";
    private static final String ROLLED_BACK = "graphitron.test.fakeConnection.rolledBack";
    private static final String AUTOCOMMIT = "graphitron.test.fakeConnection.autocommit";

    private static final String EXECUTOR_SOURCE = """
        package com.example;

        import java.sql.Connection;
        import java.util.Map;

        public final class GraphitronDevExecutor {
            public static String execute(Connection connection, String dialect, String query,
                    Map<String, Object> variables, String claims, Map<String, Object> contextArgs) {
                if ("boom".equals(claims)) {
                    throw new IllegalStateException("connect hook rejected the payload: missing claim 'sub'");
                }
                boolean tccl = Thread.currentThread().getContextClassLoader()
                    == GraphitronDevExecutor.class.getClassLoader();
                return "dialect=" + dialect
                    + ";query=" + query
                    + ";claims=" + claims
                    + ";vars=" + (variables == null ? 0 : variables.size())
                    + ";userId=" + (contextArgs == null ? null : contextArgs.get("userId"))
                    + ";tccl=" + tccl
                    + ";conn=" + (connection != null);
            }
        }
        """;

    private static final String DRIVER_SOURCE = """
        package com.example.fakedb;

        import java.lang.reflect.Proxy;
        import java.sql.Connection;
        import java.sql.Driver;
        import java.sql.DriverPropertyInfo;
        import java.sql.SQLException;
        import java.util.Properties;
        import java.util.logging.Logger;

        public final class FakeDriver implements Driver {
            @Override
            public boolean acceptsURL(String url) {
                return url != null && url.startsWith("jdbc:fake:");
            }

            @Override
            public Connection connect(String url, Properties info) {
                if (!acceptsURL(url)) {
                    return null;
                }
                return (Connection) Proxy.newProxyInstance(
                    FakeDriver.class.getClassLoader(),
                    new Class<?>[] {Connection.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "close" -> { System.setProperty("%s", "true"); yield null; }
                        case "rollback" -> { System.setProperty("%s", "true"); yield null; }
                        case "isClosed" -> false;
                        case "getAutoCommit" -> Boolean.parseBoolean(System.getProperty("%s", "true"));
                        case "toString" -> "fake-connection";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> {
                            Class<?> returnType = method.getReturnType();
                            if (returnType == boolean.class) yield false;
                            if (returnType == int.class) yield 0;
                            yield null;
                        }
                    });
            }

            @Override
            public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) {
                return new DriverPropertyInfo[0];
            }

            @Override
            public int getMajorVersion() {
                return 1;
            }

            @Override
            public int getMinorVersion() {
                return 0;
            }

            @Override
            public boolean jdbcCompliant() {
                return false;
            }

            @Override
            public Logger getParentLogger() {
                return Logger.getGlobal();
            }
        }
        """.formatted(CLOSED, ROLLED_BACK, AUTOCOMMIT);

    @TempDir
    Path tempDir;

    private Path classesDir;
    private Path libDir;

    @BeforeEach
    void compileSyntheticWorld() throws IOException {
        System.clearProperty(CLOSED);
        System.clearProperty(ROLLED_BACK);
        System.clearProperty(AUTOCOMMIT);
        classesDir = Files.createDirectories(tempDir.resolve("graphitron-classes"));
        libDir = Files.createDirectories(tempDir.resolve("lib"));
        compile(EXECUTOR_SOURCE, "com/example/GraphitronDevExecutor.java", classesDir);
        compile(DRIVER_SOURCE, "com/example/fakedb/FakeDriver.java", libDir);
        Path services = Files.createDirectories(libDir.resolve("META-INF/services"));
        Files.writeString(services.resolve("java.sql.Driver"), "com.example.fakedb.FakeDriver\n");
    }

    private void compile(String source, String relativePath, Path outputDir) throws IOException {
        Path sourceFile = tempDir.resolve("src").resolve(relativePath);
        Files.createDirectories(sourceFile.getParent());
        Files.writeString(sourceFile, source);
        int result = ToolProvider.getSystemJavaCompiler().run(null, null, null,
            "-d", outputDir.toString(), sourceFile.toString());
        assertThat(result).as("javac exit code for " + relativePath).isZero();
    }

    private DevQueryExecutor.Wiring wiring() {
        return new DevQueryExecutor.Wiring("com.example", classesDir, List.of(libDir));
    }

    private DevQueryExecutor.DbConfig db(String claims) {
        return new DevQueryExecutor.DbConfig("jdbc:fake:dev", "dev", "secret", "POSTGRES", claims);
    }

    @Test
    void execute_passesEveryValueThroughTheJdkOnlyBoundaryAndReturnsTheExecutorString()
            throws Exception {
        String result = DevQueryExecutor.execute(wiring(), db("{\"sub\":\"u1\"}"),
            "{ films { title } }", Map.of("first", 10), Map.of("userId", "u1"));
        assertThat(result)
            .contains("dialect=POSTGRES")
            .contains("query={ films { title } }")
            .contains("claims={\"sub\":\"u1\"}")
            .contains("vars=1")
            .contains("userId=u1")
            .contains("conn=true")
            // The thread context classloader points at the generated world during the call.
            .contains("tccl=true");
    }

    @Test
    void execute_closesTheConnectionAfterTheCall() throws Exception {
        DevQueryExecutor.execute(wiring(), db("c"), "{ ping }", null, null);
        assertThat(System.getProperty(CLOSED)).isEqualTo("true");
    }

    @Test
    void executorFailure_surfacesTheCauseMessageVerbatim() {
        // The hook is the validator and its errors are the feedback loop: an executor-side
        // failure (here the synthetic connect-hook rejection) must reach the caller with its own
        // message, not a reflection wrapper's.
        assertThatThrownBy(() ->
            DevQueryExecutor.execute(wiring(), db("boom"), "{ ping }", null, null))
            .isInstanceOf(DevQueryExecutor.DevExecutionException.class)
            .hasMessage("connect hook rejected the payload: missing claim 'sub'");
        assertThat(System.getProperty(CLOSED)).as("connection closed on failure too").isEqualTo("true");
    }

    @Test
    void leftoverOpenTransaction_isRolledBackBeforeClose() throws Exception {
        System.setProperty(AUTOCOMMIT, "false");
        DevQueryExecutor.execute(wiring(), db("c"), "{ ping }", null, null);
        assertThat(System.getProperty(ROLLED_BACK)).isEqualTo("true");
        assertThat(System.getProperty(CLOSED)).isEqualTo("true");
    }

    @Test
    void settledConnection_isNotRolledBack() throws Exception {
        DevQueryExecutor.execute(wiring(), db("c"), "{ ping }", null, null);
        assertThat(System.getProperty(ROLLED_BACK)).isNull();
    }

    @Test
    void missingExecutorClass_failsWithAPointerAtCompileDiagnosticsAndTheFederationGap() {
        var wrongPackage = new DevQueryExecutor.Wiring("com.absent", classesDir, List.of(libDir));
        assertThatThrownBy(() ->
            DevQueryExecutor.execute(wrongPackage, db("c"), "{ ping }", null, null))
            .isInstanceOf(DevQueryExecutor.DevExecutionException.class)
            .hasMessageContaining("com.absent.GraphitronDevExecutor")
            .hasMessageContaining("diagnostics")
            .hasMessageContaining("federation");
    }

    @Test
    void noAcceptingDriver_failsWithTheConfiguredUrlInTheMessage() {
        var unknownUrl = new DevQueryExecutor.DbConfig(
            "jdbc:nosuchdb:dev", null, null, "POSTGRES", "c");
        assertThatThrownBy(() ->
            DevQueryExecutor.execute(wiring(), unknownUrl, "{ ping }", null, null))
            .isInstanceOf(DevQueryExecutor.DevExecutionException.class)
            .hasMessageContaining("No JDBC driver")
            .hasMessageContaining("jdbc:nosuchdb:dev");
    }

    @Test
    void executorClassIsIsolatedFromTheHostClassloader() throws Exception {
        // The child loader is platform-parented: the synthetic executor must not be visible to
        // (or loaded from) the host's classloader, mirroring how the real generated world resolves
        // jOOQ/graphql-java only from the consumer's classpath.
        DevQueryExecutor.execute(wiring(), db("c"), "{ ping }", null, null);
        assertThatThrownBy(() -> Class.forName("com.example.GraphitronDevExecutor"))
            .isInstanceOf(ClassNotFoundException.class);
    }
}
