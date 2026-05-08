---
id: R113
title: "Narrow @asConnection + same-table @nodeId guard to required-arg case"
status: Backlog
bucket: validation
priority: 6
theme: nodeid
depends-on: []
---

# Narrow @asConnection + same-table @nodeId guard to required-arg case

R106 (`91c3cb892`) lifted same-table `@nodeId` args from a lookup shape (`QueryLookupTableField` with derived-table N×M join) onto the filter rail (`QueryTableField` with a `BodyParam.In` / `BodyParam.RowIn` predicate against the table's PK). The classifier change composed cleanly; one rejection inherited from the lookup era did not. `FieldBuilder.resolveTableFieldComponents` (`FieldBuilder.java:403-407`) still rejects every `@asConnection` field that has a same-table `@nodeId` leaf anywhere in its argument set, with the message at `FieldBuilder.java:379-387` ("makes this argument a lookup key. Lookups don't compose with @asConnection..."). The R106 spec called the keep deliberate ("pagination semantics on a PK-IN filter is incoherent for the same reasons it was for the lookup shape"), but the rationale is inherited from pre-R106 lookup semantics, not re-derived for the filter shape. Post-R106 the arg is `WHERE pk IN (...)` — a perfectly paginatable filter; "bounded by N input ids" only matters when N is *always* supplied. When the `@nodeId` leaf is optional (the caller may omit it and get the full paginated table), the guard fires for a non-existent problem.

Concrete failures driving the lift: opptak-subgraph's `regelverk_exp.graphqls` rejects `rangeringsregelverkV2`, `kompetanseregelverkV2`, `kompetanseregelverkGittIdV2`, `kvotetyperV2`, `regelverksamlingerV2` — five paginated lists, each carrying an optional same-typename `@nodeId` id-list arg.

The chosen direction is to narrow the guard, not delete it. Required (`!`) same-table `@nodeId` leaves keep firing the rejection (the result *is* always bounded; pagination over an always-bounded set is the case the original spec was right about). Optional same-table `@nodeId` leaves stop firing — the field classifies as `QueryTableField` with the PK-IN filter composing alongside the connection's order-by + pagination, and an empty/null id list means "no id filter, paginate the full table." Symmetric across top-level args (`anyArgSameTable`) and nested input-fields (`anyNestedSameTable`).

## Implementation sketch (Spec phase will tighten)

- **Guard predicate**: `FieldBuilder.java:403-407` currently reads `plan.anyArgSameTable() || plan.anyNestedSameTable()` and `plan.sameTableHit() != null`. Narrow to "the same-table hit's leaf is non-null." Two carrier options for the Spec to choose between: (a) extend `NodeIdArgPlan.SameTableHit` with a `boolean leafRequired` bit populated in `buildNodeIdArgPlan`; (b) re-read the leaf's `GraphQLType` at guard time from the resolved arg/input-field. (a) keeps the rejection a pure read off the plan; (b) avoids carrier growth ahead of the also-pending `AsConnectionGuard` sealed-carrier follow-up from R106.
- **Message refresh**: `formatAsConnectionSameTableRejection` (`FieldBuilder.java:379-387`) is now stale on two axes — "makes this argument a lookup key" (post-R106 it's a filter, not a lookup) and the "Lookups don't compose with @asConnection" framing. Rewrite to lead with the cardinality argument explicitly: required same-table `@nodeId` arg means result is bounded by the input id list, which `@asConnection` cannot meaningfully paginate; optional version is fine and is the suggested fix.
- **Symmetry with nested input-fields**: `BuildContext.resolveInputField` (`BuildContext.java:1201-1237`) already lifts nested same-table `@nodeId` leaves to filter-shaped `InputField` variants (the R106 spec confirmed this path was already correct). The Spec should verify the nullability check follows the input-field's own type (not the wrapping arg's nullability), so `input: Foo!` carrying `id: ID @nodeId` (optional leaf) lifts but `input: Foo!` carrying `id: ID! @nodeId` does not.

## Tests

- **Pipeline**: existing `@asConnection` + same-table `@nodeId` rejection cases in `NodeIdPipelineTest` (the two cases at `NodeIdPipelineTest.java:1245` and `:1268` referencing the rejection text) split into a required-arg arm (rejection still fires) and an optional-arg arm (classifies as `QueryTableField` with `BodyParam.In` filter + connection wrapper). Mirror cases for nested input-field leaves, both nullabilities.
- **Execution**: end-to-end query against the sakila fixture with an optional same-table `@nodeId` id-list arg + connection pagination — assert (a) ids supplied → bounded paginated result over those ids, (b) ids omitted/null → full-table paginated result, (c) sibling filter args still compose (R106's headline guarantee).
- **Author-error wording**: the rejection-text assertion at `NodeIdPipelineTest.java:1245,1268` updates to the new message; pin the new wording so future drift is loud.

## Out of scope

- Cosmetic `AsConnectionGuard.{None | Hit}` sealed-carrier refactor noted in R106's commit body and Spec body. Orthogonal; can land before, after, or alongside this item, but isn't required for the behaviour change.
- Re-evaluating the rejection in the required-arg case. The conservative call here is to keep guarding the always-bounded case; lifting that too would be a separate roadmap item with its own coherence argument.
- FK-target `@nodeId` + `@asConnection`. Already composes today (FK-target lifts to `BodyParam.In/Eq/RowIn/RowEq` via `Resolved.FkTarget.DirectFk` and was never gated by this guard).
