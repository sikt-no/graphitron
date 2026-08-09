package no.sikt.graphitron.rewrite.compile;

import no.sikt.graphitron.rewrite.ValidationReport;

import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import java.util.Locale;

/**
 * One compiler diagnostic from an incremental compile round, flattened to the fields the dev
 * loop surfaces: the generated {@code .java} javac reported it on (as a canonical file URI),
 * {@code line:col}, javac's own {@link Diagnostic.Kind}, its stable diagnostic {@code code}, and
 * the rendered message. One flattening, three sinks: the console error block, the MCP
 * {@code diagnostics} tool, and the fact store's {@code javac_diagnostic} relation (via
 * {@link CompileFacts}) all read this record, which is why the file spelling is normalised here
 * at the javac boundary rather than per sink. These stay a small dedicated collection anchored
 * on the generated file, deliberately <em>not</em> folded into the schema-anchored
 * {@code ValidationReport} (a generated-file error has no schema coordinate to fabricate). The
 * {@code source: "compile"} discriminator that separates these from schema entries is added at
 * the MCP surface, not here.
 */
public record CompileDiagnostic(String file, long line, long column, String kind, String code,
                                String message) {

    /**
     * Flattens a javac {@link Diagnostic}. The file is normalised through the single
     * canonical-URI site ({@link ValidationReport#canonicalUri}) so every sink agrees on one
     * spelling by construction; a diagnostic with no source keeps the {@code "(no source)"}
     * placeholder, and {@link Diagnostic#NOPOS} line/column stay as {@code -1}.
     */
    static CompileDiagnostic from(Diagnostic<? extends JavaFileObject> diagnostic) {
        JavaFileObject source = diagnostic.getSource();
        return new CompileDiagnostic(
            source == null ? "(no source)" : ValidationReport.canonicalUri(source.getName()),
            diagnostic.getLineNumber(),
            diagnostic.getColumnNumber(),
            diagnostic.getKind().name(),
            diagnostic.getCode(),
            diagnostic.getMessage(Locale.ROOT));
    }

    /**
     * The model-owned severity projection over javac's {@link Diagnostic.Kind}: {@code ERROR}
     * projects to {@code "error"}, every other kind ({@code WARNING}, {@code MANDATORY_WARNING},
     * {@code NOTE}, {@code OTHER}) to {@code "warning"}. The single home for the predicate every
     * consumer used to evaluate independently; total over the enum by construction, and pinned
     * total by a partition test so a new {@code Kind} fails a build instead of falling through
     * silently somewhere downstream.
     */
    public String severity() {
        return "ERROR".equals(kind) ? "error" : "warning";
    }
}
