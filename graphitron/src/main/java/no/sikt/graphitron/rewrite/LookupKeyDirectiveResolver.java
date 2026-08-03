package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.model.FieldWrapper;
import no.sikt.graphitron.rewrite.model.Rejection;
import no.sikt.graphitron.rewrite.model.ReturnTypeRef;

import java.util.List;

/**
 * Resolves the directive-level invariants of {@code @lookupKey} into a sealed {@link Resolved}
 * the caller switches on. {@link #resolveAtRoot} (from {@code classifyQueryField}) checks the
 * target-table invariant; the root site has no cardinality rejection (root {@code @lookupKey}
 * accepts both Single and List, and Connection is structurally absent there).
 * {@link #resolveAtChild} (from {@code classifyChildFieldOnTableType}) checks cardinality only.
 *
 * <p>The record-sourced lookup-keyed construction (a
 * {@link no.sikt.graphitron.rewrite.model.ChildField.BatchedTableField} carrying a keyed
 * {@link no.sikt.graphitron.rewrite.model.LookupResolution}) in
 * {@code classifyChildFieldOnResultType} does not route through this resolver and performs no
 * cardinality validation.
 *
 * <p>Mapping projection lives in {@link LookupMappingResolver}; this resolver is purely
 * directive-level invariant checking and holds no dependencies.
 */
final class LookupKeyDirectiveResolver {

    /**
     * Outcome of {@link #resolveAtRoot} / {@link #resolveAtChild}; the caller exhausts the two
     * arms with a switch. {@link Ok} carries the typed
     * {@link ReturnTypeRef.TableBoundReturnType} to spare the caller a redundant cast.
     */
    sealed interface Resolved {
        record Ok(ReturnTypeRef.TableBoundReturnType returnType) implements Resolved {}
        record Rejected(Rejection rejection) implements Resolved {
            public String message() { return rejection.message(); }
            public RejectionKind kind() { return RejectionKind.of(rejection); }
        }
    }

    LookupKeyDirectiveResolver() {}

    /** Validates the root-site invariant: the return type must be {@code @table}-annotated. */
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
