package no.sikt.graphitron.model.compile;

import no.sikt.graphitron.model.capture.compile.CompileFacts;

import java.util.Locale;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;

/**
 * One compiler diagnostic from an incremental compile round, flattened to the fields the dev
 * loop surfaces: the generated {@code .java} javac reported it on (as the path javac named),
 * {@code line:col}, javac's own {@link Diagnostic.Kind}, its stable diagnostic {@code code}, and
 * the rendered message. One flattening, two sinks: the console error block and the fact store's
 * {@code javac_diagnostic} relation (via {@link CompileFacts}) both read this record. Every
 * reader that wants a round rather than a console line reads that relation, through the
 * {@code diagnostic} view, where a {@code source} of {@code "compile"} is what separates these
 * from the schema-anchored arms. These stay anchored on the generated file, deliberately
 * <em>not</em> folded into the schema-anchored {@code ValidationReport}: a generated-file error
 * has no schema coordinate to fabricate.
 */
public record CompileDiagnostic(String file, long line, long column, String kind, String code,
                                String message) {

    /**
     * Flattens a javac {@link Diagnostic}. The file is the source's own name, which is the path
     * form every {@code file} column in the store holds; a diagnostic with no source keeps the
     * {@code "(no source)"} placeholder, and {@link Diagnostic#NOPOS} line/column stay as
     * {@code -1}.
     */
    public static CompileDiagnostic from(Diagnostic<? extends JavaFileObject> diagnostic) {
        JavaFileObject source = diagnostic.getSource();
        return new CompileDiagnostic(
            source == null ? "(no source)" : source.getName(),
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
