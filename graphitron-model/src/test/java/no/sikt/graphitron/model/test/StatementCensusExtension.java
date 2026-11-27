package no.sikt.graphitron.model.test;

import no.sikt.graphitron.model.boot.StatementObserver;
import org.jooq.ExecuteContext;
import org.jooq.ExecuteListener;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;

/**
 * Records every distinct statement a store executes during the run, for the census of which views a
 * test reads. Off unless {@value #PROPERTY} names a directory, so an ordinary build pays one property
 * lookup per class and nothing else.
 *
 * <p>Registered and autodetected as {@link StoreCostExtension} is, so it reaches every module that
 * puts this module's test-jar on its test classpath. It installs its listener before the first
 * class's own callbacks, so a store a class opens in its {@code @BeforeAll} is seen; one opened in a
 * static initializer ahead of that is not. The statements are written once as the JVM exits, to
 * {@code statements-<module>-<pid>.tsv} in the directory, one {@code origin count sql} line per
 * distinct statement text and origin, the module being the working directory's name as surefire sets
 * it.
 *
 * <p>The origin is where the statement was issued from: the first frame outside jOOQ, H2 and the
 * JDK, {@code main} where its class was compiled from main source and {@code test} where from test
 * source. A view read only by a test's own statement, a fact document's claim among them, is tested
 * model that no main code reads, which the census reports apart.
 */
public final class StatementCensusExtension implements BeforeAllCallback {

    /** The system property naming the directory the statements are written to. */
    public static final String PROPERTY = "graphitron.statementCensus";

    private static final AtomicBoolean INSTALLED = new AtomicBoolean();

    private static final Map<String, LongAdder> STATEMENTS = new ConcurrentHashMap<>();

    private static final StackWalker WALKER = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);

    /** Whether a class was compiled from test source: its classes directory or jar is a test one. */
    private static final ClassValue<Boolean> FROM_TEST = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            var source = type.getProtectionDomain().getCodeSource();
            var location = source == null || source.getLocation() == null ? "" : source.getLocation().getPath();
            return location.contains("test-classes") || location.endsWith("-tests.jar");
        }
    };

    @Override
    public void beforeAll(ExtensionContext context) {
        String directory = System.getProperty(PROPERTY);
        if (directory == null || directory.isBlank() || !INSTALLED.compareAndSet(false, true)) {
            return;
        }
        StatementObserver.install(new Recorder());
        Runtime.getRuntime().addShutdownHook(new Thread(() -> write(Path.of(directory))));
    }

    private static final class Recorder implements ExecuteListener {

        private static final long serialVersionUID = 1L;

        @Override
        public void executeStart(ExecuteContext ctx) {
            String origin = origin();
            if (ctx.batchSQL().length > 1) {
                for (String sql : ctx.batchSQL()) {
                    record(origin, sql);
                }
            } else {
                record(origin, ctx.sql());
            }
        }

        private static String origin() {
            return WALKER.walk(frames -> frames
                .map(StackWalker.StackFrame::getDeclaringClass)
                .filter(type -> !library(type.getName()))
                .findFirst()
                .map(type -> FROM_TEST.get(type) ? "test" : "main")
                .orElse("main"));
        }

        private static boolean library(String name) {
            return name.startsWith("org.jooq.") || name.startsWith("org.h2.") || name.startsWith("java.")
                || name.startsWith("jdk.") || name.startsWith("sun.")
                || name.startsWith(StatementCensusExtension.class.getName());
        }

        private static void record(String origin, String sql) {
            if (sql != null) {
                STATEMENTS.computeIfAbsent(origin + "\t" + sql.replaceAll("\\s+", " "), s -> new LongAdder())
                    .increment();
            }
        }
    }

    private static void write(Path directory) {
        var lines = new StringBuilder();
        STATEMENTS.forEach((key, count) -> {
            int tab = key.indexOf('\t');
            lines.append(key, 0, tab).append('\t').append(count.sum()).append(key, tab, key.length()).append('\n');
        });
        String module = Path.of(System.getProperty("user.dir")).getFileName().toString();
        try {
            Files.createDirectories(directory);
            Files.writeString(directory.resolve("statements-" + module + "-" + ProcessHandle.current().pid()
                + ".tsv"), lines, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
