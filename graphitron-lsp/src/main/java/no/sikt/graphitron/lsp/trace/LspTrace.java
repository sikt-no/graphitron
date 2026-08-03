package no.sikt.graphitron.lsp.trace;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Default-off wall-clock tracing for the LSP request path. Emits one line when a
 * {@link Span} opens and one when it closes, each stamped with the time of day and
 * carrying the phase name, the thread that ran it, and (on close) the elapsed time, so an
 * editor session that stops responding can be attributed to a phase instead of guessed at
 * from code inspection.
 *
 * <p>Open and close are separate lines on purpose. A phase that never returns emits an
 * open line with no matching close, which is the signal that distinguishes "stuck here"
 * from "slow everywhere"; a close-only format would show nothing at all for the case this
 * exists to diagnose. The timestamp is what makes that signal actionable: an unmatched
 * {@code >} with no clock says where the server stuck but not when, so it cannot be lined
 * up against the user's report of when the editor froze, against the editor's own log, or
 * against a build swap in another window. It also separates a log whose tail is a genuinely
 * open span from one that merely ended. A one-off header line carries the date and the
 * resolved configuration, so the artifact is self-describing without a per-line date.
 *
 * <h2>Enabling</h2>
 *
 * Off unless the {@code graphitron.lsp.trace} system property or the
 * {@code GRAPHITRON_LSP_TRACE} environment variable is set to {@code true}. While off,
 * {@link #span(String)} returns a shared no-op and allocates no span, so the seam can sit
 * on per-keystroke paths. {@link #setEnabled(boolean)} flips it at runtime, and
 * {@link no.sikt.graphitron.lsp.server.GraphitronLanguageServer#setTrace} wires that to
 * lsp4j's {@code $/setTrace} notification so an editor can turn tracing on mid-session
 * without a relaunch flag.
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
 * <p>Also deliberately not through lsp4j's {@code window/logMessage}, which would be the
 * editor-visible channel: that routes trace lines over the very connection whose framing
 * and liveness are under suspicion, serialised behind the same endpoint writes as every
 * response, and emitted from inside {@code Workspace}'s mutator lock at the
 * {@code file.reparse} and {@code file.typeIndex} sites. A hang diagnosis carried by the
 * channel fails exactly when the channel is the problem. Mainstream clients already
 * surface a language server's stderr in an editor output panel, so stderr is
 * editor-visible in practice without that coupling.
 *
 * <p>Writes are synchronous, and deliberately so: what reaches the sink is what happened
 * right up to a {@code kill}, which an asynchronous drain would lose precisely when the
 * tail is the evidence. The cost is that a sink whose reader has stopped draining blocks
 * the emitter, including at the two lock-held sites above, so
 * {@code graphitron.lsp.trace.file} rather than stderr is the recommendation when
 * investigating a hang. See
 * {@code docs/architecture/how-to/dev-loop-internals.adoc}.
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

    /** Time of day only. The date lives on the one-off header line, so lines stay narrow. */
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    /** Guards the header so it is written once per sink rather than once per span. */
    private static final AtomicBoolean headerWritten = new AtomicBoolean();

    private static volatile long slowNanos = resolveSlowMs() * 1_000_000L;

    private static volatile boolean enabled = resolveEnabled();
    private static volatile PrintStream sink = resolveSink();

    private LspTrace() {}

    /** Whether the seam is currently emitting. */
    public static boolean enabled() {
        return enabled;
    }

    /**
     * Flips the seam at runtime. Driven by
     * {@link no.sikt.graphitron.lsp.server.GraphitronLanguageServer#setTrace} off lsp4j's
     * {@code $/setTrace}, so an editor can start tracing mid-session; setting
     * {@link #ENABLE_PROPERTY} at startup remains the way to cover the initialize
     * handshake and anything else before the client's first notification.
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
        headerWritten.set(false);
    }

    /**
     * Overrides the {@code SLOW} threshold for the duration of a test, or restores the
     * {@link #SLOW_MS_PROPERTY} value when passed {@code null}. Package-private for the
     * same reason as {@link #sinkForTesting(PrintStream)}: the threshold is production
     * configuration, read once at startup.
     */
    static void slowMsForTesting(Long millis) {
        slowNanos = (millis == null ? resolveSlowMs() : Math.max(0L, millis)) * 1_000_000L;
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

        /**
         * Primitive overload, so a count attached on a per-keystroke path does not box at
         * the call site while the seam is off. Most details are counts, and nearly every
         * instrumented site reaches this overload rather than
         * {@link #detail(String, Object)}.
         */
        Span detail(String key, int value);

        /** Primitive overload, as {@link #detail(String, int)}. */
        Span detail(String key, long value);

        @Override
        void close();
    }

    private static final class NoopSpan implements Span {

        @Override
        public Span detail(String key, Object value) {
            return this;
        }

        @Override
        public Span detail(String key, int value) {
            return this;
        }

        @Override
        public Span detail(String key, long value) {
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
        public Span detail(String key, int value) {
            details.add(key + "=" + value);
            return this;
        }

        @Override
        public Span detail(String key, long value) {
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
            writeHeaderOnce();
            var line = new StringBuilder(112);
            line.append(LocalTime.now().format(CLOCK)).append(' ');
            line.append("lsp-trace ").append(marker).append(' ').append(id).append(' ');
            line.append(" ".repeat(depth * 2)).append(name);
            if (elapsedNanos != null) {
                line.append(' ').append(formatMillis(elapsedNanos));
                if (elapsedNanos >= slowNanos) {
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

    /**
     * One line per sink naming the date and the resolved configuration, so a trace file
     * read days later carries its own provenance: per-line stamps are time-of-day only,
     * and a bare duration threshold is not otherwise recoverable from the output.
     */
    private static void writeHeaderOnce() {
        if (!headerWritten.compareAndSet(false, true)) {
            return;
        }
        sink.println(LocalTime.now().format(CLOCK)
            + " lsp-trace header date=" + LocalDate.now()
            + " slowMs=" + (slowNanos / 1_000_000L)
            + " pid=" + ProcessHandle.current().pid());
    }

    private static String formatMillis(long nanos) {
        return String.format("%.1fms", nanos / 1_000_000.0);
    }

    private static boolean resolveEnabled() {
        return enabledFrom(System.getProperty(ENABLE_PROPERTY), System.getenv(ENABLE_ENV));
    }

    /**
     * The property-or-environment decision, split out as a pure function so the env-var
     * arm is testable: a test cannot set an environment variable for the JVM it runs in,
     * so without this seam that arm could only ever be asserted by reading the source.
     */
    static boolean enabledFrom(String propertyValue, String envValue) {
        return Boolean.parseBoolean(propertyValue) || Boolean.parseBoolean(envValue);
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
        return openSink(System.getProperty(FILE_PROPERTY));
    }

    /**
     * Resolves {@code target} to a stream, falling back to {@code System.err} when it is
     * absent or cannot be opened. Package-private so both arms are testable against a
     * real path without the test having to set a system property before this class
     * initialises.
     */
    static PrintStream openSink(String target) {
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
