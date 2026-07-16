package no.sikt.graphitron.rewrite.model;

import java.util.List;

/**
 * Sealed sub-family of {@link Rejection.AuthorError} for the {@code @mutation(table:)}
 * argument. Sibling to {@link DeleteRowsError} / {@link UpdateRowsError}: per the
 * dimensional-model-pivot principle, a new concern adds its own sub-seal of
 * {@link Rejection.AuthorError} (and one row in that interface's {@code permits} clause) with its own
 * {@link #lspCode()} namespace, rather than collapsing into the flat
 * {@link Rejection.AuthorError.Structural} bare-string arm.
 *
 * <p>The arm-to-code mapping is exposed via {@link #lspCode()} under the
 * {@code graphitron.mutation-table-arg.} namespace so the LSP {@code Diagnostic} projector reads the
 * stable wire code without a separate dispatch table.
 */
public sealed interface MutationTableArgError extends Rejection.AuthorError permits
    MutationTableArgError.UnsupportedVerb
{
    /** LSP wire code under the {@code graphitron.mutation-table-arg.} namespace. */
    String lspCode();

    @Override default Rejection prefixedWith(String prefix) {
        // The typed arm keeps its structural components; prefixing is a no-op concerning structure.
        return this;
    }

    /**
     * {@code @mutation(table:)} was supplied on a verb that does not accept it. Only the verbs in
     * {@code supportedVerbs} (a one-element {@code {DELETE}} set today) read the argument;
     * INSERT/UPDATE/UPSERT derive their write target by other means. Silently ignoring an
     * author-written directive argument is the green-build-wrong-intent failure mode the axioms
     * forbid, so an unsupported verb rejects loudly and names the accepting verbs.
     */
    record UnsupportedVerb(String verb, List<String> supportedVerbs) implements MutationTableArgError {
        public UnsupportedVerb {
            supportedVerbs = List.copyOf(supportedVerbs);
        }

        @Override public String message() {
            return "@mutation(table:) is not supported on @mutation(typeName: " + verb + "); the "
                + "table: argument names the write target for " + String.join(" / ", supportedVerbs)
                + " only. Remove table: from this field (" + verb + " derives its write target by "
                + "other means), or change the mutation kind to one that accepts it.";
        }

        @Override public String lspCode() { return "graphitron.mutation-table-arg.unsupported-verb"; }
    }
}
