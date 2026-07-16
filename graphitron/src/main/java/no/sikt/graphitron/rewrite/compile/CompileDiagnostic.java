package no.sikt.graphitron.rewrite.compile;

import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import java.util.Locale;

/**
 * One compiler diagnostic from an incremental compile round, flattened to the fields
 * the dev loop surfaces. Per the spec's <em>Surfacing compile diagnostics</em>, these stay a small
 * dedicated collection anchored to the generated {@code .java} where javac reports them (path +
 * {@code line:col} + severity + message), deliberately <em>not</em> folded into the schema-anchored
 * {@code ValidationReport} (a generated-file error has no schema coordinate to fabricate). The
 * {@code source: "compile"} discriminator that separates these from schema entries is added at the
 * MCP surface, not here.
 */
public record CompileDiagnostic(String file, long line, long column, String severity, String message) {

    /** Flattens a javac {@link Diagnostic}; {@link Diagnostic#NOPOS} line/column stay as {@code -1}. */
    static CompileDiagnostic from(Diagnostic<? extends JavaFileObject> diagnostic) {
        JavaFileObject source = diagnostic.getSource();
        return new CompileDiagnostic(
            source == null ? "(no source)" : source.getName(),
            diagnostic.getLineNumber(),
            diagnostic.getColumnNumber(),
            diagnostic.getKind().name(),
            diagnostic.getMessage(Locale.ROOT));
    }
}
