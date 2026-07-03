package no.sikt.graphitron.rewrite.maven.watch;

import no.sikt.graphitron.rewrite.compile.CompileDiagnostic;

import java.util.List;

/**
 * R410 slice 6 — the console renderer for a {@code graphitron:dev} incremental-compile round's error
 * diagnostics. The companion to {@link WatchErrorFormatter}'s schema-validation tree: where that block
 * anchors on schema coordinates, this one anchors on the generated {@code .java} where javac reported
 * the error, and it is labelled as a <em>generated-code</em> compilation failure so the user is not sent
 * hunting in the schema for a javac error.
 *
 * <p>Per the spec's <em>Surfacing compile diagnostics</em>, the tone is deliberately not alarmist: a
 * compile error that reaches this engine is most often transient mid-edit inconsistency (a consumer
 * service ABI changed while the generated fetcher that calls it has not yet been regenerated), which the
 * next save or consumer compile clears. Only an error that survives a clean regen and a clean consumer
 * compile is a real defect. The header says so, so the engine never cries wolf while never swallowing a
 * genuine failure.
 *
 * <p>The formatter never logs; it returns a string the caller prints or tests against.
 */
public final class CompileErrorFormatter {

    private CompileErrorFormatter() {}

    /**
     * Renders the round's error diagnostics into a labelled block. Expects the ERROR-severity subset
     * ({@code CompileRound.errors()}); an empty list yields a single "no diagnostics captured" line so a
     * failing round with a swallowed diagnostic list still prints something actionable.
     */
    public static String format(List<CompileDiagnostic> errors) {
        var sb = new StringBuilder();
        sb.append("generated-code compilation failed (")
          .append(errors.size()).append(errors.size() == 1 ? " error" : " errors").append(").\n");
        sb.append("  This is usually transient mid-edit inconsistency (a consumer type changed ahead of\n");
        sb.append("  the generated code that calls it); the next save or consumer compile normally clears\n");
        sb.append("  it. An error that survives a clean regen and consumer compile is a real defect.\n");
        if (errors.isEmpty()) {
            sb.append("  (no diagnostics captured)\n");
            return sb.toString();
        }
        for (CompileDiagnostic d : errors) {
            sb.append("    ").append(d.file());
            if (d.line() >= 0) {
                sb.append(':').append(d.line());
                if (d.column() >= 0) {
                    sb.append(':').append(d.column());
                }
            }
            sb.append("  ").append(d.message().replace("\n", "\n      ")).append('\n');
        }
        return sb.toString();
    }
}
