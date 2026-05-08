---
id: R113
title: "Narrow @asConnection + same-table @nodeId guard to required-leaf case"
status: In Progress
bucket: validation
priority: 6
theme: nodeid
depends-on: []
---

# Narrow @asConnection + same-table @nodeId guard to required-leaf case

R106 (`91c3cb892`) lifted same-table `@nodeId` args from a lookup shape (`QueryLookupTableField` with a derived-table N×M join) onto the filter rail (`QueryTableField` with a `BodyParam.In` / `BodyParam.RowIn` predicate against the table's PK). The classifier change composed cleanly; one rejection inherited from the lookup era did not. `FieldBuilder.resolveTableFieldComponents` (`FieldBuilder.java:396-411`) still rejects every `@asConnection` field that has any same-table `@nodeId` leaf in its argument set, with the message built by `formatAsConnectionSameTableRejection` (`FieldBuilder.java:379-387`): "makes this argument a lookup key. Lookups don't compose with @asConnection (the result cardinality is bounded by the input list, not paginatable)."

The R106 spec body kept this rejection deliberately, on the claim that "pagination semantics on a PK-IN filter is incoherent for the same reasons it was for the lookup shape." That rationale was inherited; it was not re-derived against the filter shape R106 introduced. Post-R106 the arg is `WHERE pk IN (decoded_ids)` (or `WHERE (pk_a, pk_b) IN ((..., ...), ...)` for composite PKs). That is a perfectly paginatable filter. "Result bounded by the input id list" only matters when the input id list is *always* supplied. A non-null leaf (`ids: [ID!]! @nodeId`) is always-bounded; a nullable leaf (`ids: [ID!] @nodeId`) is "filter when supplied, paginate the full table when absent" — a coherent paginatable shape for which the guard fires on a problem that does not exist.

Concrete failures driving the lift: opptak-subgraph's `regelverk_exp.graphqls` rejects `Query.rangeringsregelverkV2`, `Query.kompetanseregelverkV2`, `Query.kompetanseregelverkGittIdV2`, `Query.kvotetyperV2`, `Query.regelverksamlingerV2` — five paginated lists each carrying a nullable same-typename `@nodeId` id-list arg.

## Decision: narrow the predicate; collapse the carrier into a sealed guard

Keep the guard, narrow the predicate to "the same-table leaf is required." A leaf is **required** iff every wrapper from the field's argument-root down to the leaf's `ID`/`[ID]` carrier is non-null. For top-level args this collapses to "the arg's outer wrapper is `GraphQLNonNull`" (`id: ID! @nodeId` → required; `id: ID @nodeId` → optional; `ids: [ID!]! @nodeId` → required; `ids: [ID!] @nodeId` → optional; `ids: [ID]! @nodeId` → required at the list level even if elements are nullable; element-level nullability does not change the bounded-by-N argument). For nested input-field leaves the requirement is conjunctive across the path: arg outer + every input-field wrapper down to the leaf must all be non-null.

The guard's semantic claim is **`∃ required same-table @nodeId leaf`**, not "the first hit in walk order is required." A field carrying both `idsRequired: [ID!]! @nodeId` and `idsOptional: [ID!] @nodeId` is always-bounded by `idsRequired` regardless of SDL ordering; the guard must fire either way. R113 walks all hits and folds to "any required" so the predicate is order-independent.

This is **not** "delete the guard." The required-leaf shape generates a working `WHERE pk IN (...)` connection that cannot have a second page (the input list bounds the result, the `LIMIT` either equals or undershoots that bound, the next-page seek is empty). The classifier is not preventing a runtime crash; it is flagging confused author intent — `@asConnection` adds no value when the result is always bounded by a mandatory id list. Lifting that case too would be a separate roadmap item with its own coherence argument.

R113 also collapses the carrier signals the R106 commit-body called out as a follow-up. After this change `NodeIdArgPlan.{anyArgSameTable, anyNestedSameTable}` have no consumers; `sameTableHit` had the only remaining one (this guard). Adding a fourth `boolean leafRequired` field alongside two redundant booleans would be the "boolean flag for typed information" smell the rewrite's "sealed hierarchies over enums for typed information" principle pushes back on. Do the seal now: replace the three-field carrier with a sealed `AsConnectionGuard.{None | Required(SameTableHit)}` permit. The guard reads "is this `Required`?" as a sealed-switch arm rather than a `&& sameTableHit() != null && sameTableHit().leafRequired()` triple.

## Implementation

### Carrier: replace the three same-table signals with a sealed `AsConnectionGuard`

`NodeIdArgPlan` today (`FieldBuilder.java:222-232`):

```java
record NodeIdArgPlan(
        Map<String, NodeIdLeafResolver.Resolved> byArgName,
        boolean anyArgSameTable,
        boolean anyNestedSameTable,
        SameTableHit sameTableHit) {
    static final NodeIdArgPlan EMPTY = new NodeIdArgPlan(Map.of(), false, false, null);
    record SameTableHit(String leafName, String refTypeName, String containingTableName) {}
}
```

After R113:

```java
record NodeIdArgPlan(
        Map<String, NodeIdLeafResolver.Resolved> byArgName,
        AsConnectionGuard asConnectionGuard) {
    static final NodeIdArgPlan EMPTY = new NodeIdArgPlan(Map.of(), new AsConnectionGuard.None());
    record SameTableHit(String leafName, String refTypeName, String containingTableName) {}
    sealed interface AsConnectionGuard {
        record None() implements AsConnectionGuard {}
        record Required(SameTableHit hit) implements AsConnectionGuard {}
    }
}
```

`Required` carries the first-required-hit `SameTableHit` for the message builder; absent any required hit, the guard is `None` regardless of how many optional hits the walk found. Optional hits do not need to be carried on the carrier — they have no consumer post-R113. (`byArgName` is unchanged: `classifyArgument`'s `SameTable` arm at `FieldBuilder.java:777` still reads it for per-arg shape decisions.)

### `buildNodeIdArgPlan`: walk all hits, fold to "any required"

`FieldBuilder.buildNodeIdArgPlan` (`:242-279`) currently maintains three accumulators and breaks on first-hit-wins seeding for `sameTableHit`. Rewrite to walk every same-table hit and fold:

- Top-level arg loop (`:248-262`): when an arg resolves to `Resolved.SameTable`, compute `argRequired = arg.getType() instanceof GraphQLNonNull`. If `argRequired && firstRequiredHit == null`, seed `firstRequiredHit` with the arg-level `SameTableHit`. (Order-independent at the field level: any required hit wins; the seeded hit is the first *required* one in walk order, used only for message context.)
- Nested input walker, `walkInputTypeForSameTableNodeId` (`:281-303`): change the return shape from `SameTableHit` (first hit only) to "report each hit with a `pathRequired` bit." Simplest: pass an accumulator `Consumer<HitWithRequired>` down the recursion; thread `pathRequired = pathRequiredSoFar && (inputField.getType() instanceof GraphQLNonNull)` through each step. The seed at the call site in `buildNodeIdArgPlan` is `pathRequired = arg.getType() instanceof GraphQLNonNull` (the outer arg). Each leaf call invokes the accumulator with its hit + `pathRequired`. The accumulator updates `firstRequiredHit` the same way the top-level loop does.
- Final fold: `asConnectionGuard = firstRequiredHit != null ? new AsConnectionGuard.Required(firstRequiredHit) : new AsConnectionGuard.None()`.

The per-field `EMPTY`-plan short-circuit (`:275-277`) reduces to "if `byArgName` is empty and `asConnectionGuard` is `None`, return `EMPTY`."

### Guard predicate

`FieldBuilder.java:403-407` today:

```java
if (fieldDef.hasAppliedDirective(DIR_AS_CONNECTION)
        && (plan.anyArgSameTable() || plan.anyNestedSameTable())
        && plan.sameTableHit() != null) {
    return new TableFieldComponents.Rejected(Rejection.structural(
        formatAsConnectionSameTableRejection(plan.sameTableHit(), fieldDef.getName())));
}
```

After R113:

```java
if (fieldDef.hasAppliedDirective(DIR_AS_CONNECTION)
        && plan.asConnectionGuard() instanceof NodeIdArgPlan.AsConnectionGuard.Required required) {
    return new TableFieldComponents.Rejected(Rejection.structural(
        formatAsConnectionSameTableRejection(required.hit(), fieldDef.getName())));
}
```

The `instanceof` pattern binding makes the carrier collapse legible: "guard fires iff a required same-table hit exists, and that hit's `SameTableHit` is the rejection-message context." No null check, no dual-boolean read.

### Comment refresh at the guard site

`FieldBuilder.java:398-402` currently reads:

```
// @asConnection + same-table @nodeId leaf is incoherent at runtime — the result
// cardinality is bounded by the input id list (lookup semantics), not paginatable.
// Reject before classification so the structural conflict surfaces with a pointed hint
// instead of building a degenerate connection. Symmetric across argument-level and
// input-field leaves.
```

Replace with the post-R106 rationale that names "required" and frames the rejection as confused-intent flagging, not runtime-incoherence:

```
// @asConnection + a required same-table @nodeId leaf flags confused author intent:
// the result is always bounded by the mandatory input id list, so the connection's
// page is the input set (not broken at runtime — just useless). Optional same-table
// @nodeId leaves are fine: caller-omitted leaves drop the PK-IN filter and the
// connection paginates the full table; caller-supplied leaves narrow to a bounded
// set and paginate within it. Required-ness is conjunctive across the path: the
// guard fires iff the outer arg and every nested input wrapper down to the leaf
// are all non-null. ∃-required across all hits, not first-hit-wins.
```

### Message refresh

`formatAsConnectionSameTableRejection` (`FieldBuilder.java:379-387`) is stale on two axes ("makes this argument a lookup key" — post-R106 it is a filter, not a lookup; "Lookups don't compose with @asConnection" — same). The architect-review note also caught that the "cannot paginate" framing overstates the problem: the always-bounded shape paginates fine, the page just always equals the input set. Reframe as "always-bounded; @asConnection adds no value":

```java
private static String formatAsConnectionSameTableRejection(
        NodeIdArgPlan.SameTableHit hit, String fieldName) {
    return "@nodeId(typeName: '" + hit.refTypeName() + "') on '" + hit.leafName()
        + "' (required) resolves to '" + hit.containingTableName()
        + "', the field's own backing table. A required same-table @nodeId leaf"
        + " bounds the result to the input id list, so @asConnection adds no value"
        + " here — every page would equal the input set. Make '" + hit.leafName()
        + "' nullable to compose with @asConnection (the filter is applied when ids"
        + " are supplied, omitted otherwise), drop @asConnection from '" + fieldName
        + "', or use a filter argument that resolves to a different table via FK.";
}
```

The "make it nullable" hint is the headline migration path the new fields exercise.

### `LoadBearingClassifierCheck` annotation: hygiene-only, document explicitly

The architect raised whether the guard's "no required same-table `@nodeId` leaf reaches a `QueryTableField`-with-connection-wrapper emitter" guarantee should be `@LoadBearingClassifierCheck`-pinned. After auditing the connection-wrapper emitter sites: no downstream emitter relies on the optional-vs-required distinction. The connection emitter consumes `BodyParam.In` filters identically whether they came from a required leaf or an optional leaf — the runtime IN clause receives whatever the caller passed (id list or none), and pagination runs over the resulting filtered set either way. This guard is **hygiene-only**: it flags confused author intent, not a structural assumption emitter code relies on.

Document this on the `NodeIdArgPlan.AsConnectionGuard` Javadoc explicitly: "this carrier exists for an author-error rejection at `FieldBuilder.java:403-407`; it has no downstream emitter consumers and is not load-bearing in the classifier-guarantee sense." The principles-architect's published precedent allows hygiene-only guards; the spec just has to say so.

## Tests

### Pipeline tier

`NodeIdPipelineTest.NodeIdConnectionRejectionCase` (`NodeIdPipelineTest.java:1229-1297`) splits and grows. The required cases pin **structural classification** (`UnclassifiedField` with `Rejection.structural`); only one of them (the headline) pins the wording. The optional cases pin classification-shape end-to-end.

- `ASCONNECTION_PLUS_REQUIRED_ARGUMENT_SAME_TABLE_NODEID_REJECTED`: the existing `ASCONNECTION_PLUS_ARGUMENT_SAME_TABLE_NODEID_REJECTED` SDL already uses `ids: [ID!]! @nodeId(...)` (line 1239). Rename to make required-ness explicit. Assert structural: `instanceOf(UnclassifiedField.class)`, `f.reason().rejection() instanceof Rejection.Structural`. (Wording check moves to the dedicated wording-pin case below.)
- `ASCONNECTION_PLUS_OPTIONAL_ARGUMENT_SAME_TABLE_NODEID_ALLOWED` (new): same SDL with `ids: [ID!] @nodeId(...)` (drop the outer `!`). Assert classification shape: field is `QueryTableField` with a `FieldWrapper.Connection` wrapper, exactly one `BodyParam.In` predicate against the PK column with `extraction = SkipMismatchedElement`, and pagination components present (order-by + cursor seeded). This is the headline migration case.
- `ASCONNECTION_PLUS_REQUIRED_INPUT_FIELD_SAME_TABLE_NODEID_REJECTED`: the existing `ASCONNECTION_PLUS_INPUT_FIELD_SAME_TABLE_NODEID_REJECTED` (`NodeIdPipelineTest.java:1249-1269`) uses `filter: BazFilter` (nullable arg) carrying `ids: [ID!] @nodeId` (nullable leaf) — both wrappers nullable, so post-R113 this case would be **allowed**. Tighten to `filter: BazFilter!` carrying `ids: [ID!]! @nodeId` so every wrapper is non-null and the leaf is required by the conjunctive rule. Rename, structural assertion as above.
- `ASCONNECTION_PLUS_OPTIONAL_INPUT_FIELD_SAME_TABLE_NODEID_ALLOWED` (new): `filter: BazFilter` carrying `ids: [ID!] @nodeId` — exercises the original (now-allowed) input-field case the legacy test pinned. Assert `QueryTableField` shape with the input field becoming `InputField.ColumnField` with a `BodyParam.In` filter.
- `ASCONNECTION_PLUS_NESTED_INPUT_REQUIRED_NULLABLE_OUTER_ALLOWED` (new): `filter: BazFilter` (nullable arg) carrying `ids: [ID!]! @nodeId` (required leaf inside). Conjunctive rule: outer is nullable, so the path is nullable; assert classification-shape `QueryTableField` with connection wrapper. Pins that the rule is conjunctive across the path, not per-step.
- `ASCONNECTION_PLUS_MIXED_NULLABILITY_REQUIRED_AT_ANY_HIT_REJECTED` (new): two same-table `@nodeId` args, one required (`requiredIds: [ID!]! @nodeId`) and one optional (`optionalIds: [ID!] @nodeId`), both on the same `@asConnection` field. Pins the **∃-required-not-first-hit-wins** semantic: the guard fires regardless of SDL ordering. Implement as two SDL variants (required-then-optional and optional-then-required) — both must reject. (This is the case the architect flagged as a quiet under-constraint under the original first-hit-wins design.)
- `ASCONNECTION_PLUS_FK_TARGET_ARG_NODEID_ALLOWED` (`NodeIdPipelineTest.java:1271-1288`): unchanged; FK-target was never gated by this guard.

### Wording-pin case

One dedicated case asserts the user-facing migration hint: `ASCONNECTION_REJECTION_MESSAGE_NAMES_NULLABLE_HINT`. SDL is the headline required-arg shape; assertion is `f.reason().contains("Make '" + leafName + "' nullable")` plus the structural form `f.reason().contains("@nodeId(typeName: 'Baz')")`. Avoids leaning on `contains("required")` as a structural pin (the architect-review fragility note); future copy edits to "always-bounded" / "mandatory" do not break the structural cases. The "make it nullable" hint is the user-facing payoff of R113 and worth pinning explicitly so future drift away from it is loud.

### Execution tier

End-to-end test against the sakila fixture in `GraphQLQueryTest` (or a new sibling class if the existing one is full) — pick the sakila table and field name when implementing; `film` is the canonical paginated fixture and already has a `@nodeId` NodeType, so adding a `Query.filmsConnection(ids: [ID!] @nodeId(typeName: "Film"), first: Int, after: String): FilmConnection @asConnection` field there is the natural extension.

- `_idsSupplied_paginatesBoundedSet`: query with `ids: [<3 encoded film ids>], first: 2` — first page returns 2 of the 3 films, `pageInfo.hasNextPage = true`, second page returns the remaining 1.
- `_idsOmitted_paginatesFullTable`: same field with `first: 5` and no `ids` arg — assert 5 films from the full table, `pageInfo.hasNextPage = true`, paginates correctly.
- `_idsNullExplicit_paginatesFullTable`: `ids: null, first: 5` — same shape as omitted; explicit-null behaves like omitted.
- `_idsSuppliedWithSiblingFilter_composes`: `ids: [<encoded ids spanning multiple titles>], titleContains: "<substring>", first: 5` — pins R106's "siblings compose" guarantee on the connection rail (PK-IN filter and condition-driven sibling filter both apply).

## Out of scope

- Re-evaluating the required-leaf rejection itself. R113 keeps guarding the always-bounded shape on the "flag confused author intent" framing; the rejection is hygiene-only (no emitter relies on it). Lifting that too would be a separate roadmap item with its own user-facing argument — the conservative call here matches the user's stated intent ("only if the field with @nodeId matching the target table is required should we make this guard trigger").
- FK-target `@nodeId` + `@asConnection`. Already composes today via `Resolved.FkTarget.DirectFk` → `BodyParam.In/Eq/RowIn/RowEq`; never gated by this guard.
- The implicit scalar-`ID`-arg path at `FieldBuilder.java:848-883` (no `@nodeId` directive, parent table has node metadata). Synthesised, not an authored same-table `@nodeId` leaf; out of scope for the guard's predicate.
- Element-level nullability inside an outer-required list (`[ID!]!` vs `[ID]!`). R113 treats element-nullability as irrelevant: the *list* is bounded either way once the outer wrapper is non-null. Empty-list semantics and `null` elements are runtime concerns that affect *what* the IN clause contains, not *whether* the filter applies.
