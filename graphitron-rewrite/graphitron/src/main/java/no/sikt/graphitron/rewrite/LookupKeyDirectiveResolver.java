package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.model.FieldWrapper;
import no.sikt.graphitron.rewrite.model.Rejection;
import no.sikt.graphitron.rewrite.model.ReturnTypeRef;

import java.util.List;

/**
 * Resolves the directive-level invariants of {@code @lookupKey} into a sealed {@link Resolved} the
 * caller switches on, absorbing the structural rejections previously inlined at three classify
 * sites:
 *
 * <ul>
 *   <li>{@code classifyQueryField} (root): {@link #resolveAtRoot} performs the target-table
 *       check, rejecting non-{@code @table}-annotated returns. The query arm has no cardinality
 *       rejection today (root @lookupKey accepts both Single and List, and Connection is
 *       structurally absent).</li>
 *   <li>{@code classifyChildFieldOnTableType} (child @table-parent, both the
 *       {@code @splitQuery + @lookupKey} and the bare {@code @lookupKey} arms):
 *       {@link #resolveAtChild} performs the byte-identical {@code @asConnection} rejection and
 *       the per-context Single-cardinality rejection (the message specializes on
 *       {@code withSplitQuery} so the original inline strings remain byte-identical to today).
 *       The target-table invariant is gated by the call site (the surrounding
 *       {@code instanceof TableBackedType} check), so this resolver doesn't repeat it.</li>
 * </ul>
 *
 * <p>The third site that constructs a {@code @lookupKey}-typed variant
 * ({@code classifyChildFieldOnResultType} → {@code RecordLookupTableField}) does not perform
 * cardinality validation today and is intentionally left untouched: tightening its invariants
 * would be a behavior change, separate from this lift.
 *
 * <p>Mapping projection ({@code projectForLookup}) lives in the dedicated
 * {@link LookupMappingResolver} (Phase 6a); this resolver is purely directive-level invariant
 * checking and holds no dependencies.
 */
final class LookupKeyDirectiveResolver {

    /**
     * Outcome of {@link #resolveAtRoot} / {@link #resolveAtChild}. Two terminal arms; the caller
     * exhausts them with a switch.
     *
     * <ul>
     *   <li>{@link Ok} — invariants hold; carries the typed
     *       {@link ReturnTypeRef.TableBoundReturnType} to spare the caller a redundant cast.</li>
     *   <li>{@link Rejected} — every error path: target-table mismatch (root only) or
     *       cardinality invariant violation (child only).</li>
     * </ul>
     */
    sealed interface Resolved {
        record Ok(ReturnTypeRef.TableBoundReturnType returnType) implements Resolved {}
        record Rejected(Rejection rejection) implements Resolved {
            public String message() { return rejection.message(); }
            public RejectionKind kind() { return RejectionKind.of(rejection); }
        }
    }

    LookupKeyDirectiveResolver() {}

    /**
     * Validates the root-site invariant for {@code @lookupKey}: the resolved return type must be
     * {@code @table}-annotated. Returns the typed {@link ReturnTypeRef.TableBoundReturnType} as
     * {@link Resolved.Ok}, or a {@link Resolved.Rejected} otherwise.
     */
    Resolved resolveAtRoot(ReturnTypeRef returnType) {
        if (!(returnType instanceof ReturnTypeRef.TableBoundReturnType tb)) {
            return new Resolved.Rejected(Rejection.structural("@lookupKey requires a @table-annotated return type"));
        }
        return new Resolved.Ok(tb);
    }

    /**
     * Validates the child-site cardinality invariants for {@code @lookupKey} on a
     * {@code @table}-parent. The target-table invariant is gated by the call site (the
     * surrounding {@code instanceof TableBackedType} arm), so only cardinality is checked here.
     *
     * <p>{@code withSplitQuery} adjusts the Single-cardinality message to mention
     * {@code @splitQuery} when both directives are present, preserving the byte-identical
     * inline strings the two child arms emitted before this lift.
     */
    Resolved resolveAtChild(ReturnTypeRef.TableBoundReturnType returnType, boolean withSplitQuery) {
        if (returnType.wrapper() instanceof FieldWrapper.Connection) {
            return new Resolved.Rejected(Rejection.directiveConflict(
                List.of("asConnection", "lookupKey"),
                "@asConnection on @lookupKey fields is invalid: @lookupKey establishes a positional "
                + "correspondence between the input key list and the output list (one entry per key), "
                + "which pagination would break. Drop @asConnection or drop @lookupKey."));
        }
        if (returnType.wrapper() instanceof FieldWrapper.Single) {
            String prefix = withSplitQuery ? "Single-cardinality @splitQuery @lookupKey" : "Single-cardinality @lookupKey";
            return new Resolved.Rejected(Rejection.invalidSchema(prefix + " is not supported; pass a list-returning field or drop @lookupKey"));
        }
        return new Resolved.Ok(returnType);
    }
}
