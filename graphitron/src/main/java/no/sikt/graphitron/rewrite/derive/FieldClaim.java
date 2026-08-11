package no.sikt.graphitron.rewrite.derive;

import graphql.language.SourceLocation;

import java.util.List;

/**
 * One authored claim at a field coordinate, as the {@code intent_authored_field_claim} view
 * states it, enriched with the claiming directive's own decoded slot facts. One arm per
 * field-grain claiming classifier, so each arm carries exactly its semantic relation's slots
 * beside the shared provenance ({@link #trigger()}, {@link #decoded()}, {@link #location()});
 * no component is nullable by claim kind.
 *
 * <p>A claim's slots are the directive's <em>own</em> decoded columns, never a resolution the
 * classification walk would have to perform: {@link Mutation#tableRef()} is
 * {@code graphitron_mutation.table_ref} as written and absent when unwritten, deliberately not
 * the write-target precedence (whose single producer is
 * {@link no.sikt.graphitron.rewrite.MutationInputResolver#resolveDmlWriteTableRef}); a second
 * evaluation here could assert a table the classifier refuses.
 *
 * <p>Arm selection is an exhaustive switch over {@link AuthoredClaim} in
 * {@link AuthoredClaimConflicts}, whose two-way vocabulary binding to the view arms is
 * test-enforced, so a claiming relation added to the view without an arm here fails the
 * vocabulary round trip, and a vocabulary value without a switch case fails to compile.
 */
public sealed interface FieldClaim {

    /** The claim's classifier: the store vocabulary value the arm was selected by. */
    AuthoredClaim classifier();

    /** The claiming directive's name without the leading {@code @}, as the view row states it. */
    String trigger();

    /**
     * {@code false} when only the presence arm produced the claim (the application exists but
     * its decode declined); the arm's slot facts are then absent.
     */
    boolean decoded();

    /** The claiming application's own position; {@code null} when the view row is unpositioned. */
    SourceLocation location();

    /** {@code @service}: the external service reference's class and method, as written. */
    record Service(String className, String method, String trigger, boolean decoded, SourceLocation location)
        implements FieldClaim {
        @Override public AuthoredClaim classifier() { return AuthoredClaim.SERVICE; }
    }

    /** {@code @externalField}: the static jOOQ-Field method's class and method, as written. */
    record ExternalField(String className, String method, String trigger, boolean decoded, SourceLocation location)
        implements FieldClaim {
        @Override public AuthoredClaim classifier() { return AuthoredClaim.EXTERNAL_FIELD; }
    }

    /** {@code @nodeId}: the author-spelled node type reference; {@code null} when omitted. */
    record NodeId(String nodeTypeRef, String trigger, boolean decoded, SourceLocation location)
        implements FieldClaim {
        @Override public AuthoredClaim classifier() { return AuthoredClaim.NODE_ID; }
    }

    /** {@code @lookupKey}: an argument-surface marker with no slot facts of its own. */
    record LookupKey(String trigger, boolean decoded, SourceLocation location)
        implements FieldClaim {
        @Override public AuthoredClaim classifier() { return AuthoredClaim.LOOKUP_KEY; }
    }

    /**
     * {@code @routine}: the chain's routine references in application-ordinal order. The
     * repeatable directive's whole chain is one claim (two applications must never read as
     * routine-conflicting-with-routine), and its steps are the claim's slot facts. Interleaved
     * {@code @reference} hops are another directive's facts and never claim, so they neither
     * split the chain into rival claims nor enter these slots; the steps' order is known here,
     * their adjacency is not (the store's per-name ordinal does not model cross-directive
     * order).
     */
    record Routine(List<String> routineRefs, String trigger, boolean decoded, SourceLocation location)
        implements FieldClaim {
        public Routine {
            routineRefs = routineRefs == null ? null : List.copyOf(routineRefs);
        }
        @Override public AuthoredClaim classifier() { return AuthoredClaim.ROUTINE; }
    }

    /**
     * {@code @mutation}: the DML verb as written (a string, so a broken literal renders
     * faithfully) and the {@code table:} argument as written; either is {@code null} when the
     * author omitted it.
     */
    record Mutation(String operation, String tableRef, String trigger, boolean decoded, SourceLocation location)
        implements FieldClaim {
        @Override public AuthoredClaim classifier() { return AuthoredClaim.MUTATION; }
    }
}
