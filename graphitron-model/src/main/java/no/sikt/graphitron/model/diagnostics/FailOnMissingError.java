package no.sikt.graphitron.model.diagnostics;

import java.util.List;

/**
 * Sealed sub-family of {@link Rejection.AuthorError} for the {@code @mutation(failOnMissing:)}
 * argument. Sibling to {@link MutationTableArgError}: the argument is admitted on one verb, and
 * silently ignoring it anywhere else is the green-build-wrong-intent failure the axioms forbid, so
 * each verb it does not apply to rejects loudly. Which inputs a DELETE admits under either reading
 * is the DELETE walker's question and its {@link DeleteRowsError} answers.
 *
 * <p>The arm-to-code mapping is exposed via {@link #lspCode()} under the
 * {@code graphitron.fail-on-missing.} namespace so the LSP {@code Diagnostic} projector reads
 * the stable wire code without a separate dispatch table.
 */
public sealed interface FailOnMissingError extends Rejection.AuthorError permits
    FailOnMissingError.UnsupportedVerb
{
    /** LSP wire code under the {@code graphitron.fail-on-missing.} namespace. */
    String lspCode();

    @Override default Rejection prefixedWith(String prefix) {
        // Typed arms keep their structural components; prefixing is a no-op concerning structure.
        return this;
    }

    /**
     * {@code failOnMissing: true} on a verb that does not read it. Only the verbs in
     * {@code supportedVerbs} ({@code {DELETE}} today) treat a key that matches no row as already
     * done by default, so only they have a reading to opt out of.
     */
    record UnsupportedVerb(String verb, List<String> supportedVerbs) implements FailOnMissingError {
        public UnsupportedVerb {
            supportedVerbs = List.copyOf(supportedVerbs);
        }

        @Override public String message() {
            return "@mutation(failOnMissing: true) is not supported on @mutation(typeName: " + verb
                + "); a key that matches no row counts as already deleted only for "
                + String.join(" / ", supportedVerbs) + ", so only there can it be made to fail "
                + "instead. Remove failOnMissing: from this field.";
        }

        @Override public String lspCode() { return "graphitron.fail-on-missing.unsupported-verb"; }
    }
}
