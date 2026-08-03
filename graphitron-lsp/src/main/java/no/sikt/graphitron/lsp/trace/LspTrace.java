package no.sikt.graphitron.lsp.trace;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Default-off wall-clock tracing for the LSP request path. Emits one line when a
 * {@link Span} opens and one when it closes, each carrying the phase name, the thread that
 * ran it, and (on close) the elapsed time, so an editor session that stops responding can
 * be attributed to a phase instead of guessed at from code inspection.
 *
 * <p>Open and close are separate lines on purpose. A phase that never returns emits an
 * open line with no matching close, which is the signal that distinguishes "stuck here"
 * from "slow everywhere"; a close-only format would show nothing at all for the case this
 * exists to diagnose.
 *
 * <h2>Enabling</h2>
 *
 * Off unless the {@code graphitron.lsp.trace} system property or the
 * {@code GRAPHITRON_LSP_TRACE} environment variable is set to {@code true}. While off,
 * {@link #span(String)} returns a shared no-op and allocates nothing, so the seam can sit
 * on per-keystroke paths. {@link #setEnabled(boolean)} flips it at runtime.
 *
 * <h2>Where output goes</h2>
 *
 * To {@code System.err}, or to the file named by {@code graphitron.lsp.trace.file}.
 * Deliberately not through slf4j, for two reasons that both point the same way. The stdio
 * entry point ({@link no.sikt.graphitron.lsp.server.Launcher}) speaks JSON-RPC over
 * {@code System.out}, and a logging backend configured with a console appender writes
 * there by default, which would corrupt the protocol stream. In that same stdio
 * deployment the runtime classpath usually carries {@code slf4j-api} with no backend
 * bound, so slf4j calls are discarded and a logger-based seam would produce nothing.
 * Writing to a stream this class owns is both safe against the first hazard and immune to
 * the second.
 *
 * <p>The {@code graphitron.lsp.trace.slowMs} threshold (default 100) marks slower phases
 * with a {@code SLOW} tag so a long log can be scanned for the outliers.
 */
public final class LspTrace {

    /** System property enabling the seam. Mirrored by the {@code GRAPHITRON_LSP_TRACE} env var. */
    public static final String ENABLE_PROPERTY = "graphitron.lsp.trace";

    /** System property redirecting output to a file instead of {@code System.err}. */
    public static final String FILE_PROPERTY = "graphitron.lsp.trace.file";

    /** System property setting the {@code SLOW} tag threshold in milliseconds. */
    public static final String SLOW_MS_PROPERTY = "graphitron.lsp.trace.slowMs";

    private static final String ENABLE_ENV = "GRAPHITRON_LSP_TRACE";
    private static final long DEFAULT_SLOW_MS = 100L;
    private static final Span NOOP = new NoopSpan();

    private static final AtomicLong NEXT_ID = new AtomicLong();
    private static final ThreadLocal<int[]> DEPTH = ThreadLocal.withInitial(() -> new int[1]);

    private static final long SLOW_NANOS = resolveSlowMs() * 1_000_000L;

    private static volatile boolean enabled = resolveEnabled();
    private static volatile PrintStream sink = resolveSink();

    private LspTrace() {}

    /** Whether the seam is currently emitting. */
    public static boolean enabled() {
        return enabled;
    }

    /**
     * Flips the seam at runtime. Intended for tests and for a future
     * {@code $/setTrace} handler; ordinary use sets {@link #ENABLE_PROPERTY} at startup
     * instead, so tracing covers the initialize handshake too.
     */
    public static void setEnabled(boolean value) {
        enabled = value;
    }

    /**
     * Opens a span named {@code name}, or returns a shared no-op when the seam is off.
     * The caller must close it; every instrumented site uses try-with-resources.
     */
    public static Span span(String name) {
        return enabled ? new ActiveSpan(name) : NOOP;
    }

    /**
     * The stream every span line goes to. Package-private, and the only read path the
     * emitter uses, so a test can assert what the default resolution picked without
     * reaching for {@code System.setErr} (which this class deliberately ignores; see
     * {@link #resolveSink()}).
     */
    static PrintStream sink() {
        return sink;
    }

    /**
     * Redirects output for the duration of a test. Not public: production output is
     * configured through {@link #FILE_PROPERTY} at startup, and a runtime-swappable
     * public sink would be a way to aim trace lines at the JSON-RPC stream by accident.
     */
    static void sinkForTesting(PrintStream replacement) {
        sink = replacement == null ? resolveSink() : replacement;
    }

    /**
     * A timed phase. {@link #detail(String, Object)} attaches key/value context that
     * renders on the close line, so counts measured during the phase (files queued,
     * diagnostics produced) land next to the duration that produced them.
     *
     * <p>Not {@link AutoCloseable} by inheritance alone: {@link #close()} is redeclared
     * without a checked exception so instrumented sites need no catch block.
     */
    public sealed interface Span extends AutoCloseable permits ActiveSpan, NoopSpan {

        /** Attaches context rendered on the close line. Returns {@code this} for chaining. */
        Span detail(String key, Object value);

        @Override
        void close();
    }

    private static final class NoopSpan implements Span {

        @Override
        public Span detail(String key, Object value) {
            return this;
        }

        @Override
        public void close() {}
    }

    private static final class ActiveSpan implements Span {

        private final String name;
        private final long id;
        private final long startNanos;
        private final int depth;
        private final List<String> details = new ArrayList<>(4);
        private boolean closed;

        private ActiveSpan(String name) {
            this.name = name;
            this.id = NEXT_ID.incrementAndGet();
            var depthCell = DEPTH.get();
            this.depth = depthCell[0]++;
            this.startNanos = System.nanoTime();
            emit(">", null);
        }

        @Override
        public Span detail(String key, Object value) {
            details.add(key + "=" + value);
            return this;
        }

        @Override
        public void close() {
            // Guard against a double close: the depth counter is per-thread shared
            // state, and decrementing it twice would corrupt the indentation of every
            // later span on this thread.
            if (closed) {
                return;
            }
            closed = true;
            long elapsedNanos = System.nanoTime() - startNanos;
            DEPTH.get()[0] = depth;
            emit("<", elapsedNanos);
        }

        private void emit(String marker, Long elapsedNanos) {
            var line = new StringBuilder(96);
            line.append("lsp-trace ").append(marker).append(' ').append(id).append(' ');
            line.append(" ".repeat(depth * 2)).append(name);
            if (elapsedNanos != null) {
                line.append(' ').append(formatMillis(elapsedNanos));
                if (elapsedNanos >= SLOW_NANOS) {
                    line.append(" SLOW");
                }
            }
            line.append(" thread=").append(Thread.currentThread().getName());
            for (var detail : details) {
                line.append(' ').append(detail);
            }
            sink.println(line);
        }
    }

    private static String formatMillis(long nanos) {
        return String.format("%.1fms", nanos / 1_000_000.0);
    }

    private static boolean resolveEnabled() {
        if (Boolean.parseBoolean(System.getProperty(ENABLE_PROPERTY))) {
            return true;
        }
        return Boolean.parseBoolean(System.getenv(ENABLE_ENV));
    }

    private static long resolveSlowMs() {
        var raw = System.getProperty(SLOW_MS_PROPERTY);
        if (raw == null || raw.isBlank()) {
            return DEFAULT_SLOW_MS;
        }
        try {
            return Math.max(0L, Long.parseLong(raw.trim()));
        } catch (NumberFormatException e) {
            return DEFAULT_SLOW_MS;
        }
    }

    private static PrintStream resolveSink() {
        var target = System.getProperty(FILE_PROPERTY);
        if (target == null || target.isBlank()) {
            // System.err, captured now rather than read per line: the stdio deployment
            // must never reach System.out, and capturing the reference here means a later
            // System.setErr cannot redirect trace output onto the protocol stream.
            return System.err;
        }
        try {
            var path = Path.of(target.trim());
            var parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            OutputStream out = Files.newOutputStream(path);
            return new PrintStream(out, true, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            System.err.println("lsp-trace: cannot open " + target + " (" + e.getMessage()
                + "); falling back to stderr");
            return System.err;
        }
    }
}
