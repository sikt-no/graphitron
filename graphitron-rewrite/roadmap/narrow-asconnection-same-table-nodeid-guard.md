---
id: R113
title: "Demote @asConnection + same-table @nodeId guard to a warning"
status: In Review
bucket: validation
priority: 6
theme: nodeid
depends-on: []
---

# Demote @asConnection + same-table @nodeId guard to a warning

R106 (`91c3cb892`) lifted same-table `@nodeId` args from a lookup shape (`QueryLookupTableField` with a derived-table N×M join) onto the filter rail (`QueryTableField` with a `BodyParam.In` / `BodyParam.RowIn` predicate against the table's PK). The classifier change composed cleanly; one rejection inherited from the lookup era did not. R113's first pass (commits `5ed50a30`, `846f055d`) narrowed the rejection to the required-leaf case ("∃ required same-table `@nodeId` leaf") and collapsed the carrier into a sealed `AsConnectionGuard.{None | Required(SameTableHit)}`. The narrowing was right; the residual rejection was not.

Production schema relies on the required-leaf composition. Concrete shape from opptak-subgraph: `Query.kompetanseregelverkGittIdV2(ider: [ID!]! @nodeId(typeName: "Kompetanseregelverk")): [Kompetanseregelverk!] @asConnection`. The wire format the producer emits is what the consumer expects: a paginated list with `WHERE pk IN (decoded_ider)`. Removing `@asConnection` to satisfy the classifier breaks the wire format. The classifier was rejecting a perfectly paginatable shape the user deliberately authored.

Reframe: keep the same-table-leaf detection (the carrier collapse stands), but emit a `LOG.warn` at the guard site instead of a `Rejection.structural`. Author intent is hygiene-flagged, not blocked; the connection emitter does what the author asked for (`WHERE pk IN (decoded_ids)` with the standard seek pagination on top). The warn carries the same advisory hint the rejection used to carry: "every page would equal the input set; consider making `<leaf>` nullable or dropping `@asConnection`," but as guidance, not a build break.

## Decision: warn, do not reject; carrier shape unchanged

The guard at `FieldBuilder.resolveTableFieldComponents` becomes `if (... instanceof AsConnectionGuard.Required required) LOG.warn(formatAsConnectionSameTableWarning(required.hit(), fieldDef.getName()));` — no early return. Classification continues to the normal `QueryTableField` + `FieldWrapper.Connection` path. The conjunctive ∃-required predicate from R113's first pass still drives the warn (optional same-table leaves are silent — they're "filter when supplied, paginate the full table when absent" and no advisory applies); only required leaves trigger the warn.

`AsConnectionGuard.{None | Required(SameTableHit)}` keeps its shape. "Guard" now describes an advisory-warning emit rather than a rejection; the Javadoc is updated to say so. The sealed carrier is still load-bearing for the legibility win at the warn site (`instanceof Required required` reads as "advisory applies; here is the leaf to name in the warn") and remains the right encoding for the typed signal — booleans + nullable accessor would be no better.

`formatAsConnectionSameTableRejection` renames to `formatAsConnectionSameTableWarning` and reframes the prose: the headline is still "always-bounded; @asConnection adds no value" with the "Make 'X' nullable" hint, but the prose is advisory, not directive. The "or use a filter argument that resolves to a different table via FK" clause stays — it's the third alternative the author has.

This is no longer "narrow the predicate." The R113 first pass did that; the carrier collapse and conjunctive ∃-required walk both stand. The change here is: stop blocking, start advising. R114-style "lift the detection entirely" is *not* what we want — we still want the warn to fire on the always-bounded shape so authors who didn't mean to compose them have a signal, just not one that fails the build.

## Implementation

> **Shipped (rework).** Guard arm now emits `LOG.warn` instead of `Rejection.structural`;
> classification continues to `QueryTableField` + `FieldWrapper.Connection` as the author
> requested. `formatAsConnectionSameTableRejection` renamed to
> `formatAsConnectionSameTableWarning` with reframed advisory prose (still names the leaf,
> typeName, and field). Carrier shape, walker, and conjunctive ∃-required predicate all
> unchanged from the first pass. Pipeline tier renamed to
> `NodeIdConnectionAdvisoryCase` (8 cases, all `_ALLOWED`). New `AsConnectionSameTableWarnFormatTest`
> (4 cases) pins the warn surface via logback `ListAppender` (required→fires, optional→silent,
> FK-target→silent, nullable-outer-required-inner→silent). Execution tier added
> `Query.filmsConnectionByRequiredIds` mirroring opptak-subgraph's production shape.

R113's first pass (commits `5ed50a30`, `846f055d`) shipped the carrier collapse to `NodeIdArgPlan.AsConnectionGuard.{None | Required(SameTableHit)}`, the conjunctive ∃-required walk, and the rejection wording. The second pass (this rework) keeps all of that and replaces the rejection arm with a `LOG.warn` arm. Strictly less invasive than the first pass: no carrier shape change, no walker change, no test fixture rewrites for the optional/conjunctive/FK-target/sibling cases.

### Guard site: warn instead of reject

`FieldBuilder.resolveTableFieldComponents` today returns a `TableFieldComponents.Rejected` when `plan.asConnectionGuard() instanceof Required required`. Replace with a `LOG.warn` and continue:

```java
if (fieldDef.hasAppliedDirective(DIR_AS_CONNECTION)
        && plan.asConnectionGuard() instanceof NodeIdArgPlan.AsConnectionGuard.Required required) {
    LOG.warn(formatAsConnectionSameTableWarning(required.hit(), fieldDef.getName()));
}
// Fall through; classification proceeds to QueryTableField + Connection wrapper.
```

The conjunctive ∃-required predicate stays exactly as-is — optional same-table leaves remain silent (no warn fires), only the required-leaf shape advises. Comment refresh at the guard site reframes "flags confused author intent" as "advises confused author intent": the connection still ships the way the author asked for it.

### Message rename + reframe

`formatAsConnectionSameTableRejection` renames to `formatAsConnectionSameTableWarning`. The prose reframes from directive ("Make 'X' nullable to compose ...") to advisory ("you can make 'X' nullable to silence this warning, or drop @asConnection, or use a filter argument that resolves to a different table via FK"). The "always-bounded; @asConnection adds no value here — every page would equal the input set" framing stays; that's the actual hygiene observation.

The warn message must still name (a) the leaf (`hit.leafName()`), (b) the typeName (`hit.refTypeName()`), and (c) the field (`fieldName`). Future migration tooling can grep on this stable shape.

### Carrier Javadoc: "guard" now means "advisory"

`AsConnectionGuard` keeps its name and shape. Update its Javadoc: "Hygiene-only: this carrier exists for an advisory `LOG.warn` at `resolveTableFieldComponents` … the connection emitter consumes `BodyParam.In` filters identically whether they came from a required leaf or an optional leaf, so this signal never reaches a generator." Replace the previous "author-error rejection" wording with "advisory-warning emit"; otherwise unchanged.

## Tests

### Pipeline tier

`NodeIdPipelineTest.NodeIdConnectionRejectionCase` becomes `NodeIdConnectionAdvisoryCase` (rename the enum and the test method). The required cases flip from "asserts `UnclassifiedField` + `Rejection.AuthorError.Structural`" to "asserts `QueryTableField` with `FieldWrapper.Connection`, BodyParam.In on the PK, pagination components present"; structurally identical to the `OPTIONAL_*_ALLOWED` cases. The optional/conjunctive/FK-target/sibling cases stay as-is; no fixture rewrite needed.

- `ASCONNECTION_PLUS_REQUIRED_ARGUMENT_SAME_TABLE_NODEID_ALLOWED` (renamed from `_REJECTED`): SDL `ids: [ID!]! @nodeId`. Structurally identical assertion to the optional-arg case.
- `ASCONNECTION_PLUS_REQUIRED_INPUT_FIELD_SAME_TABLE_NODEID_ALLOWED` (renamed): `filter: BazFilter!` + `ids: [ID!]! @nodeId`. Structurally identical assertion.
- `ASCONNECTION_PLUS_MIXED_NULLABILITY_REQUIRED_FIRST_ALLOWED` (renamed) and `..._OPTIONAL_FIRST_ALLOWED` (renamed): both flip to allowed; the ∃-required predicate still fires the warn for both orderings (still order-independent), but the warn no longer rejects.
- All `*_ALLOWED` cases that already shipped: unchanged. They never tripped the guard.
- `ASCONNECTION_REJECTION_MESSAGE_NAMES_NULLABLE_HINT`: delete. The wording-pin moves to a dedicated logback-capture test (next section). The classifier no longer surfaces the message through `Rejection.message()`, so a `f.reason().contains(...)` pin is no longer reachable.

### Warn-format test (logback capture)

New test class `AsConnectionSameTableWarnFormatTest` modeled on `IdReferenceShimWarnFormatTest`:

- Attach a `ListAppender<ILoggingEvent>` to the `FieldBuilder` logger (`LoggerFactory.getLogger(FieldBuilder.class.getName())`) at WARN level, in `@BeforeEach`.
- Build a schema with the headline required-arg shape (`bazByIds(ids: [ID!]! @nodeId(typeName: "Baz")): BazConnection @asConnection`).
- Assert `appender.list` has at least one event whose formatted message contains `"@nodeId(typeName: 'Baz')"`, `"'ids'"`, and `"every page would equal the input set"` (the headline diagnostic). Optionally assert it also contains `"make 'ids' nullable"` (the advisory hint).
- Add a "warn does not fire on optional leaf" case with `ids: [ID!] @nodeId(typeName: "Baz")` — assert no events on the appender for that schema build. Pins the ∃-required predicate at the warn surface.
- Add a "warn does not fire on FK-target leaf" case (the existing FK-target SDL) — same: zero events. Defends against accidental warn-broadening.

### Execution tier

`GraphQLQueryTest` already has `filmsConnectionByOptionalIds_*` (4 scenarios). Add a sibling for the production-shape required-arg case, modeled on the production schema (`Query.kompetanseregelverkGittIdV2(ider: [ID!]! @nodeId(...)): [...] @asConnection`):

- New SDL field on `Query` in `graphitron-sakila-example/src/main/resources/graphql/schema.graphqls`:
  ```graphql
  filmsConnectionByRequiredIds(
      ids: [ID!]! @nodeId(typeName: "Film"),
      first: Int, after: String
  ): [Film!]! @asConnection @defaultOrder(primaryKey: true)
  ```
- `filmsConnectionByRequiredIds_idsSupplied_paginatesBoundedSet`: same shape as the optional-arg `_idsSupplied` test (3 ids, `first: 2`, page 1 has 2, page 2 has 1); pins that the required-arg shape still produces a working WHERE-IN connection. The schema build emits the warn at classifier time but the runtime is unaffected.
- The optional-arg execution-tier tests stay as-is: still pin the optional leaf's runtime-shape behaviour.

## Out of scope

- Suppressing the warn by directive. The author can already silence the warn three ways: nullable leaf, drop `@asConnection`, or use an FK-target arg. A `@suppressWarning` directive is over-engineering for a single warn category.
- `@LoadBearingClassifierCheck` annotation. The architect-review verified hygiene-only; that holds for the warn arm too. Annotating would be inert.
- FK-target `@nodeId` + `@asConnection`. Composes today via `Resolved.FkTarget.DirectFk` → `BodyParam.In/Eq/RowIn/RowEq`; never gated by this guard.
- Implicit scalar-`ID`-arg path. Synthesised, not an authored same-table `@nodeId` leaf; out of scope for the predicate.
- Element-level nullability inside an outer-required list (`[ID!]!` vs `[ID]!`). The list is bounded either way once the outer wrapper is non-null; element-nullability does not change the warn predicate.
