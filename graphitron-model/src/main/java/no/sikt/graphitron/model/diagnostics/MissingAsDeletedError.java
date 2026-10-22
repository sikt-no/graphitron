package no.sikt.graphitron.model.diagnostics;

import java.util.List;

/**
 * Sealed sub-family of {@link Rejection.AuthorError} for the {@code @mutation(missingAsDeleted:)}
 * argument. Sibling to {@link MutationTableArgError}: the argument is admitted on one verb and one
 * return shape, and silently ignoring it anywhere else is the green-build-wrong-intent failure the
 * axioms forbid, so each place it does not apply rejects loudly. Whether the input identifies rows
 * by exactly the primary key is the DELETE walker's question and its
 * {@link DeleteRowsError.MissingAsDeletedNeedsPrimaryKey} answer.
 *
 * <p>The arm-to-code mapping is exposed via {@link #lspCode()} under the
 * {@code graphitron.missing-as-deleted.} namespace so the LSP {@code Diagnostic} projector reads
 * the stable wire code without a separate dispatch table.
 */
public sealed interface MissingAsDeletedError extends Rejection.AuthorError permits
    MissingAsDeletedError.UnsupportedVerb,
    MissingAsDeletedError.UnsupportedReturnShape
{
    /** LSP wire code under the {@code graphitron.missing-as-deleted.} namespace. */
    String lspCode();

    @Override default Rejection prefixedWith(String prefix) {
        // Typed arms keep their structural components; prefixing is a no-op concerning structure.
        return this;
    }

    /**
     * {@code missingAsDeleted: true} on a verb that does not read it. Only the verbs in
     * {@code supportedVerbs} ({@code {DELETE}} today) have a goal that a missing row already meets.
     */
    record UnsupportedVerb(String verb, List<String> supportedVerbs) implements MissingAsDeletedError {
        public UnsupportedVerb {
            supportedVerbs = List.copyOf(supportedVerbs);
        }

        @Override public String message() {
            return "@mutation(missingAsDeleted: true) is not supported on @mutation(typeName: " + verb
                + "); a key that matches no row counts as already deleted only for "
                + String.join(" / ", supportedVerbs) + ". Remove missingAsDeleted: from this field.";
        }

        @Override public String lspCode() { return "graphitron.missing-as-deleted.unsupported-verb"; }
    }

    /**
     * {@code missingAsDeleted: true} on a DELETE that returns {@code ID} or {@code [ID!]} directly.
     * Only the payload-returning DELETE, whose payload carries an id field echoing the deleted
     * primary keys, echoes a missing key.
     */
    record UnsupportedReturnShape(String fieldCoordinate) implements MissingAsDeletedError {
        @Override public String message() {
            return "@mutation(typeName: DELETE, missingAsDeleted: true) on '" + fieldCoordinate
                + "' returns ID or [ID!] directly; missingAsDeleted: is supported only on a "
                + "payload-returning DELETE, whose payload type carries an ID or [ID!] field echoing "
                + "the deleted keys. Return a payload type, or remove missingAsDeleted: from this field.";
        }

        @Override public String lspCode() { return "graphitron.missing-as-deleted.unsupported-return-shape"; }
    }
}
