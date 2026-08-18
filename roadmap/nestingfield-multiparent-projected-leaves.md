---
id: R323
title: "Multi-parent NestingField sharing: admit the projected leaves, retire the open BatchKey question"
status: Spec
bucket: architecture
priority: 5
theme: classification-model
depends-on: []
created: 2026-06-17
last-updated: 2026-08-18
---

# Multi-parent NestingField sharing: admit the projected leaves, retire the open BatchKey question

A `NestingField` is a plain-object type with no `@table` of its own, declared as a field on a
`@table` parent; its leaves resolve against that parent's table (the *anchor*). When two or more
`@table` parents declare fields of the same nesting type, each parent classifies the shared type's
leaves independently against its own anchor, and
`GraphitronSchemaValidator.validateNestingParentCompat` checks the results agree:
`compareNestedFieldsShape` compares every later parent's leaves field-by-field against the first
parent in SDL order. Admission arms exist for `ColumnBackedField` (terminal SQL name and Java
column class must match), `TableField` (R23's arm: per-parent join topology tolerated, filters must
agree because one generated condition method serves every reuse site), and `NestingField`
(recursion). Every other leaf class falls to the catch-all
`Rejection.deferred(...not yet supported across multiple parents)`.

This item was filed as R23's follow-up when the catch-all's blocking population was five
BatchKey-carrying leaf classes. The 2026-07-15 addendum asked for that list to be re-derived at
Spec time against the post-R431/R432 model. This body is the re-derivation, and it lands somewhere
the original title did not predict: of the leaves for which the catch-all is the *sole* blocker,
two are projected leaves that admit here on R23's own argument, and only one is a BatchKey carrier,
whose admission would be a real design effort with no measured demand. The spec therefore admits
the two projected leaves and converts the remaining BatchKey deferral from an open question into a
precisely documented one.

## The catch-all's blocking population, re-derived

A leaf reaches the multi-parent question only if it is admitted at nested depth at all.
`isNestedWireableLeaf` admits six shapes under a `NestingField`: `ColumnBackedField`, `TableField`,
and `NestingField` (all three already have admission arms), plus `ColumnBackedReferenceField` and
`ComputedField` (admitted at nested depth by R645), plus the `SourceShape.Table` arm of
`BatchedTableField`. The catch-all is therefore the sole blocker for exactly three classes:

| Leaf | What it is | Verdict here |
|---|---|---|
| `ComputedField` | `@externalField` expression leaf | admit |
| `ColumnBackedReferenceField` | scalar `@field` + `@reference` projection | admit |
| `BatchedTableField`, Table-sourced | `@splitQuery` batched read | deferral stays, made precise |

Every other `BatchKeyField` carrier (the Record-sourced `BatchedTableField` arm,
`BatchedLookupTableField`, `ServiceTableField`, `ServiceRecordField`, `BatchedPivotField`, and the
batched polymorphic leaves) is rejected per-variant at nested depth by
`validateVariantIsSupportedAtNestedDepth` before the multi-parent question can matter; for those
the catch-all fires redundantly on top of the per-variant rejection. If a future item admits one of
them at nested depth, its multi-parent question arrives with that item, exactly as R645's
nested-depth admissions surfaced the two projected leaves here.

## Why now

R645 measured the downstream demand on the fs-plattform sis subgraph mid-migration: 39 of its 72
deferred errors were the nested-depth gate, and 4 of those 39 (`EkskludertEmneIResultatsammendrag.emnenavn`
and siblings) were *also* multi-parent `ComputedField` shapes. R645 shipped the nested-depth half,
so this gate is what still rejects those 4 today. R645 named one prerequisite for admitting them,
the `@externalField` parent-table assignability hole, and that shipped as R646 (Done). Nothing
blocks the admission any more. One consequence worth stating for whoever runs the migration:
admission converts a concretely-typed helper under a shared nesting type from this gate's deferral
into R646's structural rejection on the mismatching parent, which is the correct authoring signal
(type the helper on `Table<?>` or a shared supertype).

## Design: one admission arm, keyed on the alias-read capability

The three shipped arms already encode one rule, and stating it is part of this item's work,
because the BatchKey verdict below is the same rule's other answer. Each arm compares exactly the
facts consumed by the artifacts that are *shared* across the sharing parents, and ignores the facts
consumed by per-anchor artifacts. `ColumnBackedField` compares terminal SQL name and Java class
because the shared `<NestedType>Fetchers` read is jOOQ's name-based typed `Record.get`;
`TableField` compares `filters()` because one generated condition method serves every reuse site,
and skips `joinPath` / `orderBy` / `pagination` because `ProjectionCommands.mintNestedUnit` mints
the projection unit per anchor (addressed `<Anchor><Nested>`), so those facts feed per-anchor
artifacts only. Restated in address terms, for `compareNestedFieldsShape`'s javadoc: a leaf is
multi-parent-safe when every unit and method address it mints under a nested type carries the
anchor, and an arm compares exactly the inputs of the addresses that do not.

The projected leaves need no emitter work, by the mechanism R645 verified by spike: the per-anchor
unit's `$project` receives the anchor's own table local, and the shared `<NestedType>Fetchers`
registration reads the value back by `__rk_<resultKey>` alias off the source record without
consulting the parent, so first-parent-wins registration of the shared nested type is
output-identical whichever parent registers it.

That parent-independent read is not a prose argument to restate at the arm; it is already reified
and enforced. `ResultKeyAliasedField` exists to single-home "projects under a result-key alias and
reads back by that alias", with membership enforced on the write side (`ProjectionCommands`'
default arm) and the read side (`FetcherEmitter`'s fall-through), both of which throw on an
unhandled member. Both admitted leaves are members, and `ColumnBackedField` deliberately is not. So
the admission is **one arm keyed on the capability**, `instanceof ResultKeyAliasedField` on both
sides (the upstream class-equality check already guarantees both sides are the same class), not two
class-identity arms. The next alias-projecting variant is then forward-admitted by the capability's
own enforced membership, instead of falling into the catch-all as a spurious deferral while its
emit arms already work.

The arm compares one thing: `domainReturnType()`. The one shared artifact these leaves touch is the
read, and one `<NestedType>Fetchers` class carries one typed read per coordinate: the peeled helper
return type for `ComputedField`, the terminal column's type for a Direct reference. Across parents
these are identical by construction today (the helper method and the path's terminal column both
derive from the single SDL declaration on the shared type), but the comparison is one line, it is
the exact read-side analogue of the `ColumnBackedField` arm's `columnClass` comparison, and an
enforcer beats a by-construction paragraph. Everything else the leaves store is per-anchor join
topology each anchor renders itself: the reference leaf's `joinPath` / `parentCorrelation` (its
inferred FK entry into the shared path), the computed leaf's anchor table handed to the helper.

Two per-variant concerns stay where they already live, and the arm re-checks neither:

- Whether the developer's `@externalField` helper accepts each anchor's table is checked per parent
  instance: nested classification threads each anchor's `TableRef` into
  `ExternalFieldDirectiveResolver.resolve`, whose `ServiceCatalog.reflectExternalField` call runs
  R646's two-layer assignability check. A helper typed on one parent's concrete table classifies
  clean under that parent and rejects under the other; a shared helper must take `Table<?>` or a
  common supertype, exactly the widened form R646 admits. The lift form (`@externalField` carrying
  a `@reference` path) likewise keeps its per-variant deferral, which since R645 runs at nested
  depth per parent instance.
- `NodeIdEncodeKeys` compaction: `validateColumnBackedReferenceField` rejects it at nested depth
  per parent instance (R645), and the per-variant walk and this walk both always run, so the
  composite carrier stays rejected regardless of sharing.

**The mixed-classification edge is rearmed, not fixed.** A rooted-at-child reference collapses to
`ColumnBackedField` at classification time, per parent, so one SDL leaf can classify
`ColumnBackedField` under one anchor and `ColumnBackedReferenceField` under another when the
anchors' FK orientations differ. Rejecting the pair is right: the two variants read through
different mechanisms (a typed column constant versus a projected alias) and one shared
`<NestedType>Fetchers` class carries one read. But today the class-mismatch check rejects it as
`Rejection.structural`, an author error naming two model-internal class names for a divergence that
is a property of the two anchors' FK topology, not of anything authored. When the mismatched pair's
`ResultKeyAliasedField` membership differs, route the rejection through the class-carrying
`Rejection.deferred` overload instead, phrased in reader terms: one parent reads this field off a
typed column, the other off a projected alias, and one generated fetchers class carries one read.
Other class mismatches keep the structural arm. A path entered via `{key: "..."}` is unaffected by
any of this: it names one specific FK, resolvable from one anchor only, so the other parent's leaf
rejects during classification before this gate is reached; the admission target is the
`{table: ...}`-entry form, where each anchor infers its own FK.

## The BatchKey carrier: the deferral becomes the documented end state

The `SourceShape.Table` arm of `BatchedTableField` (a `@splitQuery` leaf) is the one `BatchKeyField`
carrier admitted at nested depth, and for it the catch-all does real work. The conflict is now
precisely stateable against the decomposed model, which is what the 2026-07-15 addendum asked for:

- A nested leaf's `parentTypeName` is the nested type's own name, not the anchor's
  (`FieldBuilder.classifyNestingField` classifies each nested field with the nested SDL type as its
  parent name, threading the anchor's table only for resolution). `GeneratedUnits.rowsMethod`
  therefore mints the identical `<NestedType>Fetchers#rows<Field>` method reference for every
  sharing parent, and the nested coordinate gets exactly one DataFetcher registration.
- Each parent's key derivation is legitimately per-anchor: `deriveSplitQuerySource` reads the batch
  grain off the parent-side correlation columns, or the anchor's primary key when the join path is
  empty, so two anchors produce two different `SourceKey` / `ParentCorrelation` values, two
  different rows-method bodies, and two different runtime key lifts.

One minted artifact, two correct-but-different derivations. Admitting the leaf requires per-anchor
rows-method minting plus a runtime dispatch, inside the single registered fetcher, on which anchor
the arriving source row came from; where that discriminator lives on a projected source row is a
genuine design problem. And there is no measured demand: the downstream measurement attributed no
error to a multi-parent batched shape.

Decision: the deferral stays, and this item's contribution is to make it precise instead of open.
The catch-all comment currently explains itself as "the BatchKey carriers and composite NodeId
references ... per-parent metadata this shape check doesn't inspect", which goes stale on the NodeId
half the moment the capability arm ships. Rewrite it to state the rule from the design section plus
today's resident: the Table-sourced `BatchedTableField` arm, whose rows-method address is
nested-type-keyed while its key derivation is per-anchor. The comment must not carry the
three-class census above; an unguarded inventory in a code comment rots silently, so the census
lives in this item body and, at Done, in the changelog entry, while the migrated witness test is
what pins the resident. While there: both the catch-all and the class-mismatch site name the leaf
class in prose while calling the single-argument `deferred(...)`, dropping the typed variant
payload; use the class-carrying overload. No successor roadmap item is filed: a deferred rejection
whose mechanism is documented at the rejection site does not need a standing item, and a future
admission should be filed fresh, with demand in hand, against whatever the minting and registration
machinery looks like then. This mirrors how this item's own `LookupTableField` question was closed
by attrition (see "Closed questions" below).

## Implementation

All in `GraphitronSchemaValidator`, plus fixtures:

- One arm in `compareNestedFieldsShape` ahead of the catch-all: both sides
  `instanceof ResultKeyAliasedField`, comparing `domainReturnType()`, with a one-line comment that
  the alias basis is the result key (shared by the name match already done) so nothing else feeds a
  shared artifact.
- Rearm the class-mismatch check: when the mismatched pair's `ResultKeyAliasedField` membership
  differs, reject through the class-carrying `Rejection.deferred` overload with the reader-terms
  wording from the design section; other mismatches keep the structural arm.
- Rewrite the catch-all comment per the previous section, and move both deferral sites onto the
  class-carrying overload.
- Rewrite `validateNestingParentCompat`'s javadoc: its closing sentence, "Non-ColumnBackedField
  leaves reject at nested depth when the nesting type is shared", has been stale since R23 admitted
  `TableField` and goes further stale here. State the address rule from the design section, with
  each arm named as an instance of it.
- `graphitron-sakila-example`: extend the R23 execution fixture. `OccupantLocation` (shared by
  `Customer` and `Store`, both holding an FK to `address`) gains one leaf per admitted class: an
  `@externalField` leaf backed by a helper taking `Table<?>` (both anchors must be accepted), and a
  scalar `@field` + `@reference` leaf entering the path via `{table: "address"}` so each anchor
  infers its own FK and both terminate on the same `address` column.

## Tests

- Unit (`NestingFieldValidationTest`): a shared `ComputedField` leaf and a shared Direct-compaction
  `ColumnBackedReferenceField` leaf across two parents, no error. A hand-built pair with divergent
  `domainReturnType()`, rejected: the arm's one comparison is testable at unit tier exactly as the
  `ColumnBackedField` arm's `columnClass` case is. A mixed-membership pair (`ColumnBackedField`
  under one parent, `ColumnBackedReferenceField` under the other), pinning the rearmed deferred
  message. Migrate the deferral witness: `multiParentCompat_nonColumnLeaf_rejectedAcrossParents`
  currently pins the catch-all message on a `ColumnBackedReferenceField`, which this item admits;
  rebuild the witness on a Table-sourced `BatchedTableField` so the deferral message keeps a live
  pin.
- Pipeline (`GraphitronSchemaBuilderTest`): a shared nested type carrying both leaves classifies
  per parent and validates clean. And the cross-parent R646 pin: a helper typed on one anchor's
  concrete table under a shared nested type produces the assignability rejection. Both parents'
  instances land on the same nested coordinate (a nested leaf's `parentTypeName` is the nested
  type), so the observable is a rejection at that coordinate naming the mismatching anchor's table
  in its message; per-anchor attribution is prose-recoverable, not structural. That test is what
  proves the per-instance check makes the compare-one-thing admission safe; if it does not fire,
  the admission design is wrong and the arm must not ship.
- Execution (`GraphQLQueryTest`): the extended `OccupantLocation` fixture resolves both new leaves
  under both parents, asserted against the equivalent flat reads (the convention of R645's
  `nestingField_projectedLeaves_agreeWithTheirFlatSiblings`).
- Corpus: no `@classified` verdict is expected to flip, since this gate is validation-time, not
  classification-time; confirm rather than assume at implementation time.

## Closed questions this item carries

- **`LookupTableField` re-scoping** (carried from R23, closed 2026-08-13 while specifying R645):
  needs no decision, because the leaf no longer exists. R432 folded the record-sourced lookup pair
  onto the source-gated `BatchedLookupTableField` and the inline `LookupTableField` onto
  `TableField` with a `lookup()` facet; the inline survivor is exactly the `TableField` arm R23
  already admitted across parents, and the record-sourced survivor is not nest-wireable.
- **The original five-variant enumeration** (`SplitTableField`, `SplitLookupTableField`,
  `RecordTableField`, `RecordLookupTableField`, `Record*MethodField`): dissolved by R431/R432/R314
  into the source-gated `BatchedTableField` / `BatchedLookupTableField` and the service leaves. Of
  the successors, only the Table-sourced `BatchedTableField` arm is nest-wireable, and it is the
  deferral this item documents.

## Retired vocabulary

For the Done-gate retirement sweep. All currently true, all become false:

- "the BatchKey carriers and composite NodeId references" (the `compareNestedFieldsShape` catch-all
  comment; the composite-NodeId half stops being this gate's business when the reference arm ships,
  and the BatchKey half gets the precise restatement)
- "Non-ColumnBackedField leaves reject at nested depth when the nesting type is shared"
  (`validateNestingParentCompat` javadoc; already stale for `TableField`)
- "classifies as ColumnBackedReferenceField which is not yet supported across multiple parents"
  (the expected message in `NestingFieldValidationTest.multiParentCompat_nonColumnLeaf_rejectedAcrossParents`;
  the witness migrates to a leaf that stays deferred)

## Not in scope

- Multi-parent admission of the Table-sourced `BatchedTableField`: the design problem above, to be
  filed fresh on demand.
- The mixed-classification edge (rooted-at-child collapse diverging per parent); the existing
  class-mismatch structural rejection is the intended verdict.
- Any nested-depth widening: `isNestedWireableLeaf` is untouched. A leaf newly admitted at nested
  depth brings its multi-parent question with it.
