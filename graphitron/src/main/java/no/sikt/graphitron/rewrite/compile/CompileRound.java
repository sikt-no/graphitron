package no.sikt.graphitron.rewrite.compile;

import java.util.List;

/**
 * The outcome of one incremental compile round. {@code success} is javac's own verdict
 * (false if any unit in the round had an error), and {@code diagnostics} is the round's dedicated
 * diagnostic collection.
 *
 * <p>Per the spec, a failed round <strong>does not report success</strong>: javac emits no
 * {@code .class} for a unit with errors, so a failing unit silently keeps its last-good {@code .class}
 * on disk; surfacing {@code success == false} is what tells the dev loop the running tree is stale for
 * those units rather than letting it trust a clean compile that never happened. The engine never
 * swallows a diagnostic, but the messaging tone (mid-edit inconsistency is the usual cause, not a bug)
 * is the console renderer's job, not this record's.
 */
public record CompileRound(boolean success, List<CompileDiagnostic> diagnostics) {

    public CompileRound {
        diagnostics = List.copyOf(diagnostics);
    }

    /** Diagnostics of {@code ERROR} kind, the ones that mean a unit did not produce fresh bytecode. */
    public List<CompileDiagnostic> errors() {
        return diagnostics.stream().filter(d -> "ERROR".equals(d.severity())).toList();
    }
}
