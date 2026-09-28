package no.sikt.graphitron.model.test;

import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Writes what each test class cost the store: how many captures and boots it ran, and how long it
 * took. Off unless {@value #ENV} names a file, so an ordinary build pays one environment lookup per
 * class and nothing else.
 *
 * <p>Registered through {@code META-INF/services} and autodetected, so it reaches every module that
 * puts this module's test-jar on its test classpath and runs with autodetection on, whether through
 * this module's {@code junit-platform.properties}, which rides in the test-jar, or its own. Autodetected extensions register before a
 * class's own, so a capture in a class's {@code @BeforeAll} lands inside that class's window.
 *
 * <p>Attributed by thread rather than by interval. Classes run four at a time, so the global
 * counters differenced around one class would charge it for its neighbours; {@link FactStores}
 * keeps a tally per thread beside the global count, and a class is charged what its own thread ran
 * between its first and last callback. That is close rather than exact: a class blocked on a join
 * can lend its thread to another class's task, whose work then lands on the lender. The module
 * totals, written once as the JVM exits, are exact.
 *
 * <p>One tab-separated line per class, {@code module class captures boots millis}, and one
 * {@code module TOTAL captures boots -} line per JVM. The module is the working directory's name,
 * which is the module directory under surefire, so several modules can append to one file.
 */
public final class StoreCostExtension implements BeforeAllCallback, AfterAllCallback {

    /** The environment variable naming the file this appends to. */
    public static final String ENV = "GRAPHITRON_STORE_COST_REPORT";

    private static final ExtensionContext.Namespace NAMESPACE =
        ExtensionContext.Namespace.create(StoreCostExtension.class);

    private static final AtomicBoolean TOTAL_HOOKED = new AtomicBoolean();

    /** What this thread had run when the class started. */
    private record Start(long captures, long boots, long nanos) {}

    @Override
    public void beforeAll(ExtensionContext context) {
        Path report = report();
        if (report == null || nested(context)) {
            return;
        }
        if (TOTAL_HOOKED.compareAndSet(false, true)) {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> append(report,
                line("TOTAL", FactStores.captures(), FactStores.boots(), "-"))));
        }
        context.getStore(NAMESPACE).put(context.getUniqueId(), new Start(
            FactStores.capturesOnThisThread(), FactStores.bootsOnThisThread(), System.nanoTime()));
    }

    @Override
    public void afterAll(ExtensionContext context) {
        Path report = report();
        if (report == null) {
            return;
        }
        Start start = context.getStore(NAMESPACE).remove(context.getUniqueId(), Start.class);
        if (start == null) {
            return;
        }
        append(report, line(context.getRequiredTestClass().getName(),
            FactStores.capturesOnThisThread() - start.captures(),
            FactStores.bootsOnThisThread() - start.boots(),
            Long.toString((System.nanoTime() - start.nanos()) / 1_000_000)));
    }

    /** A nested class runs inside its outer class's window, which already charges it. */
    private static boolean nested(ExtensionContext context) {
        return context.getParent().flatMap(ExtensionContext::getTestClass).isPresent();
    }

    private static Path report() {
        String named = System.getenv(ENV);
        return named == null || named.isBlank() ? null : Path.of(named);
    }

    private static String line(String subject, long captures, long boots, String millis) {
        return String.join("\t", Path.of("").toAbsolutePath().getFileName().toString(), subject,
            Long.toString(captures), Long.toString(boots), millis) + "\n";
    }

    /** One write per line, appended, so concurrent classes and JVMs interleave by whole lines. */
    private static synchronized void append(Path report, String line) {
        try {
            Files.writeString(report, line, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
