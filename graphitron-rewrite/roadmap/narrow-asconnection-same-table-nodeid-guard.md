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

> **Shipped (rework).** `FieldBuilder.resolveTableFieldComponents` emits `ASCONNECTION_HYGIENE_LOG.warn` (category-named logger `FieldBuilder.asConnectionSameTableHygiene`, mirroring the `BuildContext.idRefShim` precedent) when `plan.firstRequiredSameTableHit() != null`; classification falls through to `QueryTableField` + `FieldWrapper.Connection`. The first-pass sealed `AsConnectionGuard.{None | Required(SameTableHit)}` collapsed to a single nullable `SameTableHit firstRequiredSameTableHit` field on `NodeIdArgPlan` — a sealed two-arm hierarchy is heavier than a single warn site needs. `formatAsConnectionSameTableRejection` renamed to `formatAsConnectionSameTableWarning` with advisory rather than directive prose; still names field/leaf/typeName for migration tooling. Carrier walker and conjunctive ∃-required predicate unchanged from the first pass. Pipeline tier renamed to `NodeIdConnectionAdvisoryCase` (8 cases, all `_ALLOWED`); the wording-pin case `ASCONNECTION_REJECTION_MESSAGE_NAMES_NULLABLE_HINT` was deleted (the wording moved out of `Rejection.message()` into the warn). New `AsConnectionSameTableWarnFormatTest` (one `requiredLeaf_emitsWarn_namingFieldLeafAndType` case) pins the warn-message format via logback `ListAppender` on the category logger; predicate coverage (when the warn fires vs. silent) lives at pipeline tier where the same SDL shapes assert structural classification — duplicating silence pins at unit tier would be defence-in-depth on a single flag with no unique signal. Execution tier added one `Query.filmsConnectionByRequiredIds` scenario mirroring opptak-subgraph's production shape.

The first pass shipped the conjunctive ∃-required walk and a sealed `AsConnectionGuard` carrier whose two arms gated a build-breaking rejection. The rework demoted the rejection to a warn; with no build break to gate, the sealed two-arm shape lost its weight and collapsed to a nullable field. Strictly less machinery than the first pass for strictly more permissive runtime behaviour.

## Tests

Pipeline tier (`NodeIdPipelineTest.NodeIdConnectionAdvisoryCase`) — 8 cases, all `_ALLOWED`. Required arg/input field/conjunctive cases assert `QueryTableField` + `FieldWrapper.Connection`, structurally identical to the optional cases that already shipped; the carrier flip from rejected→allowed is visible in the test source as a rename + an assertion-shape change.

Unit tier (`AsConnectionSameTableWarnFormatTest`) — one case: required-leaf shape emits a warn whose message names `field 'bazByIds'`, `@nodeId(typeName: 'Baz')`, `'ids'`, the headline diagnostic `every page of @asConnection would equal the input set`, and the advisory hint `make 'ids' nullable`. Logger is the category-named `FieldBuilder.asConnectionSameTableHygiene` (stable address for migration tooling, independent of `FieldBuilder` class organisation).

Execution tier (`GraphQLQueryTest`) — one new scenario, `filmsConnectionByRequiredIds_idsSupplied_paginatesBoundedSet`, mirroring opptak-subgraph's production shape. The required-arg case collapses three of the optional-arg's four scenarios: `_idsOmitted` and `_idsNullExplicit` are syntactically excluded by the `!`, and `_siblingComposes` reduces to the same `WHERE pk IN (...)` runtime as `_idsSupplied`. One scenario is enough.

## Out of scope

- Suppressing the warn by directive. The author can already silence the warn three ways: nullable leaf, drop `@asConnection`, or use an FK-target arg. A `@suppressWarning` directive is over-engineering for a single warn category.
- `@LoadBearingClassifierCheck` annotation. The architect-review verified hygiene-only; that holds for the warn arm too. Annotating would be inert.
- FK-target `@nodeId` + `@asConnection`. Composes today via `Resolved.FkTarget.DirectFk` → `BodyParam.In/Eq/RowIn/RowEq`; never gated by this guard.
- Implicit scalar-`ID`-arg path. Synthesised, not an authored same-table `@nodeId` leaf; out of scope for the predicate.
- Element-level nullability inside an outer-required list (`[ID!]!` vs `[ID]!`). The list is bounded either way once the outer wrapper is non-null; element-nullability does not change the warn predicate.
