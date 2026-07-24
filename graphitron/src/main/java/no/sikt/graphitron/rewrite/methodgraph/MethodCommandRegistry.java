package no.sikt.graphitron.rewrite.methodgraph;

import no.sikt.graphitron.rewrite.model.BatchKeyField;
import no.sikt.graphitron.rewrite.model.OutputField;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-generation-run command/name registry covering the reentry family. One instance lives for
 * one generation run and is surfaced on the generation result next to the emitted units, so the
 * bidirectional closure oracle ({@code ReentryCommandClosureTest}) can join the two: every
 * covered emitted method is exactly one committed command's output, and every schema coordinate
 * the covered family claims has a committed command behind it.
 *
 * <p><b>The registry is the name authority, not a census.</b> The reentry declaration path runs
 * <em>through</em> {@link #declareReentryRowsMethod}: the emitter obtains the name it declares
 * with from the commit's return value, and the commit reads that name off the model's naming
 * fact ({@link BatchKeyField#rowsMethodName()}, minted once on the model, read by the
 * declaration through this commit and by the DataLoader call site directly). An emitter that
 * bypasses the seam produces a covered coordinate with no command, which the oracle reports;
 * a parallel register-beside-the-formula shape is not constructible because no commit overload
 * accepts an externally-derived name.
 *
 * <p><b>The covered-family boundary is derived, never tagged.</b> Whether a declaration commits
 * a command is decided by the model's site-level fact ({@link OutputField#emitsKeyedReQuery()}),
 * evaluated inside the seam: the Table-sourced {@code @splitQuery} arm flows through the same
 * declaration call and commits nothing (not reentry), the record-sourced arm commits. No caller
 * passes a family tag; methods of other emit families are simply outside the covered set.
 *
 * <p>The covered set is structurally {@code emitsKeyedReQuery()} on a {@link BatchKeyField} or
 * {@link no.sikt.graphitron.rewrite.model.MutationField.DmlTableField}: DataLoader-backed leaves
 * commit through {@link #declareReentryRowsMethod}, the projected / discriminated DML arms (whose
 * keyed re-query lives in a named {@code rows<Name>} companion) through
 * {@link #declareDmlReentryRowsMethod}.
 */
public final class MethodCommandRegistry {

    private final Map<String, MethodCommand> byMethodKey = new LinkedHashMap<>();

    /**
     * The reentry rows/load-method declaration seam. Returns the name the caller must declare
     * the method with; commits the command claiming {@code (unitFqcn, top-level, name)} when the
     * site-level fact holds. Committing twice for the same method key is a generator bug (two
     * emitters claiming one method) and throws.
     */
    public <F extends OutputField & BatchKeyField> String declareReentryRowsMethod(F field, String unitFqcn) {
        String methodName = field.rowsMethodName();
        if (field.emitsKeyedReQuery()) {
            commit(new MethodCommand(field.qualifiedName(), unitFqcn, "", methodName));
        }
        return methodName;
    }

    /**
     * The DML reentry declaration seam: the {@code Projected*} / {@code Discriminated*} mutation
     * arms' follow-up SELECT lives in a named rows method whose declaration name resolves here.
     * The {@code Encoded*} arms carry no reentry and never reach it, but the gate reads the
     * site-level fact rather than trusting the call site.
     */
    public <F extends OutputField & no.sikt.graphitron.rewrite.model.MutationField.DmlTableField>
            String declareDmlReentryRowsMethod(F field, String unitFqcn) {
        String methodName = field.reentryRowsMethodName();
        if (field.emitsKeyedReQuery()) {
            commit(new MethodCommand(field.qualifiedName(), unitFqcn, "", methodName));
        }
        return methodName;
    }

    private void commit(MethodCommand command) {
        MethodCommand previous = byMethodKey.putIfAbsent(command.methodKey(), command);
        if (previous != null) {
            throw new IllegalStateException(
                "Graphitron generator bug (method-command registry): two commands claim emitted method "
                + command.methodKey() + " — first committed for coordinate " + previous.coordinate()
                + ", now recommitted for coordinate " + command.coordinate()
                + "; every emitted method is exactly one command's output");
        }
    }

    /** Every command committed this run, in commit order. */
    public List<MethodCommand> committed() {
        return List.copyOf(byMethodKey.values());
    }
}
