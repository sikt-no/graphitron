package no.sikt.graphitron.rewrite;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * JSONL trace of classifier decisions, gated on the system property
 * {@code graphitron.classification.trace=<path>}. When the property is unset
 * (the default for production generator runs and ad-hoc local builds), every
 * call is a no-op and pays nothing beyond a property read at class init.
 *
 * <p>Records are appended one-per-line. Within one JVM, writes are serialised on
 * the writer instance; across forked JVMs (Surefire / Failsafe) the OS-level
 * O_APPEND semantics from {@link StandardOpenOption#APPEND} make appends safe
 * without coordination, so per-module trace files can absorb fork-parallel
 * classification.
 *
 * <p>The {@link Context} ThreadLocal is set by the JUnit extension that
 * auto-registers in the test classpath; it tags every record produced inside a
 * test's lifecycle with the running test class and its tier annotation. Outside
 * tests the context is empty and the emitted record carries empty
 * {@code test} / {@code tier} fields.
 */
public final class ClassificationTrace {

    private static final String PROPERTY = "graphitron.classification.trace";

    /**
     * Lazily initialised on first {@link #emit} call against the path named by the system
     * property at that moment. {@code null} when the property is unset; once opened, stays
     * bound to the file for the lifetime of the JVM (or until {@link #resetForTesting} is
     * invoked).
     */
    private static volatile BufferedWriter writer;
    private static volatile boolean writerInitialised;

    private static final ThreadLocal<Context> CURRENT = ThreadLocal.withInitial(() -> Context.EMPTY);

    private ClassificationTrace() {}

    /** Marker carried per record describing the operation that produced it. */
    public enum Op { classify, enrich, demote, synthesize }

    /**
     * Lifecycle context for a running test. The JUnit extension sets this in
     * {@code BeforeAllCallback} and clears it in {@code AfterAllCallback}.
     */
    public record Context(String testClassName, String tier) {
        public static final Context EMPTY = new Context("", "");
    }

    /** Set by the JUnit extension (test sources). Not for production callers. */
    public static void setContext(Context context) {
        CURRENT.set(context == null ? Context.EMPTY : context);
    }

    /** Cleared by the JUnit extension. */
    public static void clearContext() {
        CURRENT.set(Context.EMPTY);
    }

    /** True when the trace property is set; emits otherwise are pure no-ops. */
    public static boolean isEnabled() {
        return getOrInitWriter() != null;
    }

    /**
     * Test-only: close any existing writer and rebind to {@code path}, bypassing the system
     * property. Pass {@code null} to disable tracing for the rest of the JVM.
     */
    public static synchronized void resetForTesting(java.nio.file.Path path) {
        if (writer != null) {
            try { writer.close(); } catch (IOException ignored) {}
            writer = null;
        }
        writerInitialised = true;
        if (path == null) return;
        try {
            Path parent = path.getParent();
            if (parent != null) Files.createDirectories(parent);
            writer = new BufferedWriter(new OutputStreamWriter(
                Files.newOutputStream(path, StandardOpenOption.CREATE, StandardOpenOption.APPEND),
                StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static synchronized BufferedWriter getOrInitWriter() {
        if (writerInitialised) return writer;
        writer = openWriter();
        writerInitialised = true;
        return writer;
    }

    /**
     * Emits one trace record. Type-axis records use empty {@code parent} and pass
     * the type name in {@code name}; field-axis records use the parent type name
     * in {@code parent} and the field name in {@code name}.
     *
     * @param op         which named operation produced this record
     * @param parent     parent type name (empty for top-level types)
     * @param name       type or field name
     * @param leaf       fully-qualified record class of the produced variant
     * @param source     {@code SourceLocation.getSourceName()}; null for inline-string fixtures
     * @param rejection  rejection kind on Unclassified arms; null otherwise
     * @param message    rejection message on Unclassified arms; null otherwise
     */
    public static void emit(Op op, String parent, String name, String leaf, String source,
            RejectionKind rejection, String message) {
        BufferedWriter w = getOrInitWriter();
        if (w == null) return;
        var ctx = CURRENT.get();
        var sb = new StringBuilder(256);
        sb.append('{');
        appendField(sb, "op", op.name(), true);
        appendField(sb, "parent", parent == null ? "" : parent, false);
        appendField(sb, "name", name == null ? "" : name, false);
        appendField(sb, "leaf", leaf == null ? "" : leaf, false);
        appendField(sb, "source", source == null ? "" : source, false);
        if (rejection != null) {
            appendField(sb, "rejection", rejection.name(), false);
            appendField(sb, "message", message == null ? "" : message, false);
        }
        appendField(sb, "test", ctx.testClassName, false);
        appendField(sb, "tier", ctx.tier, false);
        sb.append('}').append('\n');
        write(w, sb.toString());
    }

    private static synchronized void write(BufferedWriter w, String line) {
        try {
            w.write(line);
            w.flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static BufferedWriter openWriter() {
        String path = System.getProperty(PROPERTY);
        if (path == null || path.isEmpty()) return null;
        try {
            Path file = Path.of(path);
            Path parent = file.getParent();
            if (parent != null) Files.createDirectories(parent);
            return new BufferedWriter(new OutputStreamWriter(
                Files.newOutputStream(file, StandardOpenOption.CREATE, StandardOpenOption.APPEND),
                StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot open classification trace at " + path, e);
        }
    }

    private static void appendField(StringBuilder sb, String key, String value, boolean first) {
        if (!first) sb.append(',');
        sb.append('"').append(key).append("\":\"");
        escape(sb, value);
        sb.append('"');
    }

    private static void escape(StringBuilder sb, String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"'  -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
    }
}
