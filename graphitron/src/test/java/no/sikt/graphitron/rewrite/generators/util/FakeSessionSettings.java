package no.sikt.graphitron.rewrite.generators.util;

import java.lang.reflect.Proxy;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

/**
 * The PostgreSQL session-settings capture and reset as the emitted {@code ConnectionSources.mounting}
 * issues them, for the emitted-code harness tests' fake connections: the capture is a
 * {@code Statement.executeQuery} over {@code pg_settings} at acquire, the reset a
 * {@code prepareStatement} whose SQL starts with {@code RESET ALL} at release. A fake connection
 * answers the capture with {@link #captureStatement} and recognises the reset with
 * {@link #isReset}; without them the capture reads a {@code null} result set and acquire evicts.
 */
final class FakeSessionSettings {

    private FakeSessionSettings() {}

    /**
     * A {@code DatabaseMetaData} naming {@code product} as the database, what
     * {@code ConnectionSources.mounting} reads to decide whether to capture and reset:
     * {@code "PostgreSQL"} for a fake standing in for a PostgreSQL connection.
     */
    static java.sql.DatabaseMetaData metaData(ClassLoader loader, String product) {
        return (java.sql.DatabaseMetaData) Proxy.newProxyInstance(loader, new Class<?>[]{java.sql.DatabaseMetaData.class},
            (proxy, method, args) -> method.getName().equals("getDatabaseProductName")
                ? product
                : defaultFor(proxy, method, args, "fakeMetaData:" + product));
    }

    /** {@link #metaData} for PostgreSQL. */
    static java.sql.DatabaseMetaData postgres(ClassLoader loader) {
        return metaData(loader, "PostgreSQL");
    }

    /** Whether {@code sql} is the reset release issues after the unmount. */
    static boolean isReset(String sql) {
        return sql.startsWith("RESET ALL");
    }

    /**
     * A {@code Statement} whose {@code executeQuery} runs {@code onCapture} and answers with
     * {@code rows}, each a {@code {name, value}} pair; every other method returns its default.
     */
    static Statement captureStatement(ClassLoader loader, List<String[]> rows, Runnable onCapture) {
        return (Statement) Proxy.newProxyInstance(loader, new Class<?>[]{Statement.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "executeQuery" -> {
                    onCapture.run();
                    yield resultSet(loader, rows);
                }
                default -> defaultFor(proxy, method, args, "fakeCaptureStatement");
            });
    }

    /** A forward-only {@code ResultSet} over {@code rows}, read by column index 1 and 2. */
    static ResultSet resultSet(ClassLoader loader, List<String[]> rows) {
        int[] cursor = {-1};
        return (ResultSet) Proxy.newProxyInstance(loader, new Class<?>[]{ResultSet.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "next" -> ++cursor[0] < rows.size();
                case "getString" -> rows.get(cursor[0])[(Integer) args[0] - 1];
                default -> defaultFor(proxy, method, args, "fakeSettingsResultSet");
            });
    }

    private static Object defaultFor(Object proxy, java.lang.reflect.Method method, Object[] args, String label) {
        return switch (method.getName()) {
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == (args == null ? null : args[0]);
            case "toString" -> label;
            default -> {
                Class<?> type = method.getReturnType();
                if (!type.isPrimitive() || type == void.class) {
                    yield null;
                }
                yield type == boolean.class ? (Object) false : (Object) 0;
            }
        };
    }
}
