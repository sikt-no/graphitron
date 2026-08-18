---
id: R323
title: "Multi-parent NestingField sharing: admit the projected leaves, retire the open BatchKey question"
status: In Progress
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
| `BatchedTableField`, Table-sourced | `@splitQuery` batched read, plus its lookup-keyed instances | deferral stays, made precise |

The third row is one class covering two authoring shapes, because `isNestedWireableLeaf` gates
`BatchedTableField` on `sourceShape()` alone: a lookup-keyed instance (`lookup()` carrying a
resolved `LookupResolution`) is nest-wireable on exactly the same terms as a plain `@splitQuery`
one, and lands on the same deferral for the same reason. The census is a class census, so it needs
no fourth row, but the deferral's restatement has to be worded at class granularity rather than
naming `@splitQuery` alone.

Every other `BatchKeyField` carrier is rejected per-variant at nested depth by
`validateVariantIsSupportedAtNestedDepth` before the multi-parent question can matter; for those the
catch-all fires redundantly on top of the per-variant rejection. Enumerated against the capability's
own membership list, that is the Record-sourced `BatchedTableField` arm, `ServiceTableField`,
`ServiceRecordField`, `BatchedPivotField`, the batched polymorphic pair `BatchedInterfaceField` /
`BatchedUnionField`, and `BatchedTableInterfaceField`, the discriminated interface child's batched
half. The last of those is the newest member (it raised `LeafRatchetTest`'s `CHILD_FIELD_LEAVES` pin
from 22 to 23), and it is named separately from the polymorphic pair because the capability's own
javadoc separates them; `isNestedWireableLeaf`'s `default` arm covers it either way.
The keyed-lookup family contributes no separate entry to that list: the lookup leaves no longer have
classes of their own (see "Closed questions" below), so their record-sourced survivor *is* the
Record-sourced `BatchedTableField` arm already named. If a future item admits one of these at nested
depth, its multi-parent question arrives with that item, exactly as R645's nested-depth admissions
surfaced the two projected leaves here.

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

## Design: one admission arm, on the alias-read capability plus the anchor-address premise

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
`requireAliasedWriteArm`) and the read side (`FetcherEmitter`'s method-backed fall-through), both of
which throw on an unhandled member. Both admitted leaves are members, and `ColumnBackedField`
deliberately is not. So the admission is **one arm keyed on the capability**,
`instanceof ResultKeyAliasedField` on both sides (the upstream class-equality check already
guarantees both sides are the same class), not two class-identity arms.

### The membership set is four, and two of the four constrain the arm

The capability has four implementors, not two: `ColumnBackedReferenceField`, `ComputedField`,
`TableField`, and `PivotField`. The two beyond the admitted pair are not incidental, and getting
them wrong is the most likely way to ship this arm broken.

**`TableField` makes arm ordering load-bearing.** It is a member, so a capability arm placed above
the existing `TableField` arm swallows it and its `filters()` comparison silently stops running.
That comparison is not decorative: one generated condition method serves every reuse site, so
diverging filters at two sites is a real conflict. Nothing catches the loss, because the
filters-divergence message has no test pinning it (the string lives in the validator and nowhere
else). The arm therefore goes **below** the `TableField` arm and immediately above the catch-all,
and this item adds the missing negative test so the ordering constraint is enforced mechanically
rather than remembered.

**`PivotField` is why the capability alone is not the admission predicate.** A pivot leaf projects
under a result-key alias and is a legitimate member, but the unit it mints is addressed
`GeneratedUnits.pivotUnit(parentTypeName, fieldName)`, and a nested leaf's `parentTypeName` is the
nested type. So its address does not carry the anchor, which is exactly the address rule's failure
mode, while its capability membership says nothing about that. The capability tracks the alias
write/read contract; multi-parent safety is a different fact, and the two coincide for three
members and diverge for the fourth.

Today `isNestedWireableLeaf` keeps `PivotField` off this gate entirely, so the divergence is
unreachable and no rejection changes. But an arm keyed on membership alone would pre-answer the
multi-parent question in the affirmative for any future member, with nothing to force the check.
`development-principles.adoc` names this shape directly: a capability is a drift risk when it is a
hand-declared marker not bound to the base facts it should track. So the arm carries **both** the
capability check and the address premise as a stated condition, with `PivotField` named as the
member the premise excludes and the reason spelled out at the site. A future item that widens
`isNestedWireableLeaf` past this list revisits this arm as part of its own work, and the arm's
comment is where it finds out that it has to.

(While in the file: `ResultKeyAliasedField`'s own javadoc says "the four families" and then names
three, omitting `PivotField`. One-line fix, same edit.)

### What the arm compares

The base comparison is `domainReturnType()`. The one shared artifact these leaves touch is the
read, and one `<NestedType>Fetchers` class carries one typed read per coordinate: the peeled helper
return type for `ComputedField`, the terminal column's type for a Direct reference. It is the exact
read-side analogue of the `ColumnBackedField` arm's `columnClass` comparison.

The reference half compares **one fact more: the terminal table of `joinPath()`**, read as the last
step's `targetTable()`, and this is not belt-and-braces. Read it off the model, not through
`ServiceCatalog.terminalTableForReference`: `GraphitronSchemaValidator` holds no catalog (it is
stateless, `validate(GraphitronSchema)` and nothing else), and the model read needs none.
`JoinStep` permits only `Hop`, and the `HasTargetTable` capability every permit mixes in guarantees
a pre-resolved `targetTable()`, so the read is total on a non-empty path with no `Optional` to
unwrap. It is also strictly the fact wanted: the catalog method returns empty for a non-FK-derived
terminal step, where the arm still wants the two sides compared. The path cannot be empty on a valid
schema, because `validateColumnBackedReferenceField` rejects an empty `joinPath()` outright
("@reference path is required") on each parent instance. The original draft justified comparing only
`domainReturnType()` on the grounds that the terminal column derives from the single SDL declaration
on the shared type, so it is identical across parents by construction. That is true for the
`{table: ...}`-entry form this item targets, and false for a `{key: ...}` first step, because there
the terminal derives from the SDL *plus* the anchor's position on the named FK:

```graphql
type Touched {
    when: LocalDateTime @field(name: "LAST_UPDATE")
                        @reference(path: [{key: "customer_address_id_fkey"}])
}
type Customer @table(name: "customer") { touched: Touched }
type Address  @table(name: "address")  { touched: Touched }
```

`JooqCatalog.foreignKeyTouchesTable` admits a named FK from **either** endpoint, and
`BuildContext.synthesizeFkJoin` infers traversal direction from which side the source sits on. So
under the `customer` anchor this is a forward hop reading `address.last_update`, and under the
`address` anchor it is the reverse hop reading `customer.last_update`. Both classify
`ColumnBackedReferenceField`; both terminal columns are `timestamp` in the fixture database, so
`domainReturnType()` matches and a `domainReturnType()`-only arm admits the pair. One field on a
shared type, two different tables read, no diagnostic.

The generated SQL is not wrong (each anchor renders its own capped correlated subselect off its own
projection unit, so each returns what its own path asked for), which is precisely why no existing
enforcer catches it and why this belongs here rather than in a bug item. Comparing the terminal
table closes it at the cost of one line, and the comparison is anchor-independent for every shape
this item admits: `{table: "address"}` under `customer` and under `store` terminates on `address`
both times. Terminal *table* rather than terminal *column name* is the right grain, because the
divergence above shares a column name and differs only in which table it comes from.

Everything else the leaves store stays uncompared, being per-anchor join topology each anchor
renders itself: the reference leaf's `joinPath` / `parentCorrelation` (its inferred FK entry into
the shared path), the computed leaf's anchor table handed to the helper. Note the asymmetry is
deliberate: `joinPath` diverging is the admitted case, `joinPath`'s *terminus* diverging is not.

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
  per parent instance (R645) at *every* arity, not only composite, and the per-variant walk and this
  walk both always run, so the carrier stays rejected regardless of sharing.
- An unreachable FK on one anchor: a `{key: ...}` path element whose named FK touches neither end of
  that anchor's table is rejected during classification by `BuildContext`'s FK-connection check
  ("key 'X' does not connect to table 'Y'", with the FK's two endpoint tables as the candidate
  hint), per anchor. `FieldBuilder` then hoists that nested `UnclassifiedField` to the *outer* field
  coordinate, so the non-resolving parent's nesting field is no longer a `NestingField` at all and
  the shared group drops below two members. This gate never runs on such a schema, which is the
  correct division of labour and not something the arm re-checks.

**The mixed-classification edge is rearmed, not fixed.** One SDL leaf can classify
`ColumnBackedField` under one anchor and `ColumnBackedReferenceField` under another. Exactly one
route reaches that: an `@nodeId(typeName: ...)` reference, where `FieldBuilder`'s FK-mirror collapse
(`fkMirrorSourceColumns`) emits a plain `ColumnBackedField` when the single hop's target-side columns
positionally equal the target node's key columns, and a `ColumnBackedReferenceField` otherwise. Two
anchors with different FK orientations to the same node target land on different sides of that
collapse. No other shape can diverge, because directives are per-declaration: a scalar
`@field` + `@reference` always classifies `ColumnBackedReferenceField`, `@externalField` always
`ComputedField`, and `@splitQuery` is a property of the SDL rather than of the anchor.

Rejecting the pair is right, and today it rejects as `Rejection.structural`, an author error naming
two model-internal class names for a divergence that is a property of the two anchors' FK topology
rather than of anything authored. When the mismatched pair's `ResultKeyAliasedField` membership
differs, route the rejection through the class-carrying `Rejection.deferred` overload instead. Word
it on the FK-orientation fact, which is the thing that actually differs and the thing the author can
act on: the two anchors enter the same node target from opposite ends of their foreign keys, so one
resolves to the parent's own key columns and the other needs a join, and one generated fetchers class
carries one read. Do **not** word it as "one parent reads a typed column, the other reads a projected
alias": on the only reachable route the `ColumnBackedReferenceField` side carries a
`NodeIdEncodeKeys` compaction and never reaches emission at all, so the projected-alias read it names
is a read that side never performs. Other class mismatches keep the structural arm.

Because that side is independently rejected on its own account, the rearmed message has no reachable
schema that produces it *alone*; it always co-occurs with the per-variant rejection naming the
missing capability. That is worth stating rather than hiding, since the unit test below is hand-built
and will pass regardless of reachability. The class-carrying overload takes one class, and two are in
play at a mismatch site; pass the representative's, matching the argument order the surrounding
messages already use ("classifies as X on the first but Y on the second").

The admission target is the `{table: ...}`-entry form, where each anchor infers its own FK. A
`{key: "..."}` first step is out of the admitted population, but not for the reason the earlier draft
gave ("resolvable from one anchor only"): a named FK is resolvable from *either* of its two endpoint
tables, so a nesting type shared by exactly those two tables resolves the same `{key:}` under both
anchors in opposite directions. That case is handled by the terminal-table comparison above, not by
this paragraph. It is only when the named FK touches neither end of an anchor's table that the leaf
rejects during classification and this gate is never reached.

## The BatchKey carrier: the deferral becomes the documented end state

The `SourceShape.Table` arm of `BatchedTableField` is the one `BatchKeyField` carrier admitted at
nested depth, and for it the catch-all does real work. Both of its authoring shapes ride the same
arm: a plain `@splitQuery` batched read, and a lookup-keyed instance, since `isNestedWireableLeaf`
gates on `sourceShape()` and reads nothing about `lookup()`. The conflict is now precisely stateable
against the decomposed model, which is what the 2026-07-15 addendum asked for:

- A nested leaf's `parentTypeName` is the nested type's own name, not the anchor's: the nesting arm
  of `FieldBuilder.classifyChildFieldOnTableType` recurses with the nested SDL type as the parent
  name, threading the anchor's table only for resolution. `GeneratedUnits.rowsMethod` therefore mints
  the identical `<NestedType>Fetchers#rows<Field>` method reference for every sharing parent, and the
  nested coordinate gets exactly one DataFetcher registration.
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
what pins the resident. While there: the catch-all names the leaf class in prose while calling the
single-argument `deferred(...)`, dropping the typed variant payload; move it onto the class-carrying
overload. That is the only site to move. The class-mismatch site is not a second deferral site
today, it calls `Rejection.structural(...)`, which has no class-carrying overload, so the only
change there is the membership-differs rearm in the bullet above; mismatches that keep the
structural arm keep the single-argument call. No successor roadmap item is filed: a deferred rejection
whose mechanism is documented at the rejection site does not need a standing item, and a future
admission should be filed fresh, with demand in hand, against whatever the minting and registration
machinery looks like then. This mirrors how this item's own `LookupTableField` question was closed
by attrition (see "Closed questions" below).

## Implementation

All in `GraphitronSchemaValidator`, plus fixtures:

- One arm in `compareNestedFieldsShape`, positioned **below the `TableField` arm** and immediately
  above the catch-all. Placement is a correctness constraint, not a style choice: `TableField` is a
  `ResultKeyAliasedField`, so an arm placed higher swallows it and silently drops its `filters()`
  comparison. Both sides `instanceof ResultKeyAliasedField`, comparing `domainReturnType()`, plus the
  resolved terminal table for the `ColumnBackedReferenceField` half. The comment states the address
  premise (every unit and method address these leaves mint under a nested type carries the anchor)
  and names `PivotField` as the member the premise excludes, so a later widening of
  `isNestedWireableLeaf` finds the constraint at the site.
- Rearm the class-mismatch check: when the mismatched pair's `ResultKeyAliasedField` membership
  differs, reject through the class-carrying `Rejection.deferred` overload (representative's class)
  with the FK-orientation wording from the design section; other mismatches keep the structural arm.
- Rewrite the catch-all comment per the previous section, and move the catch-all's own
  `deferred(...)` call onto the class-carrying overload. That is the only existing call that moves,
  per the previous section: the class-mismatch site calls `Rejection.structural(...)`, which has no
  class-carrying overload, and the bullet above is what puts the rearmed membership-differs route on
  `deferred` in the first place.
- `ResultKeyAliasedField` javadoc: its "four families" sentence names three. Add `PivotField`.
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
  `domainReturnType()`, rejected: testable at unit tier exactly as the `ColumnBackedField` arm's
  `columnClass` case is. A hand-built pair agreeing on `domainReturnType()` but with divergent
  terminal tables, rejected: the reverse-`{key:}` shape from the design section, and the one case
  that fails without the second comparison. A mixed-membership pair (`ColumnBackedField` under one
  parent, `ColumnBackedReferenceField` under the other), pinning the rearmed deferred message, with
  the test's own comment recording that the pair is hand-built and that the reachable schema for it
  co-rejects on the `NodeIdEncodeKeys` capability. Migrate the deferral witness:
  `multiParentCompat_nonColumnLeaf_rejectedAcrossParents` currently pins the catch-all message on a
  `ColumnBackedReferenceField`, which this item admits; rebuild the witness on a Table-sourced
  `BatchedTableField` so the deferral message keeps a live pin.
- Unit, the ordering guard: a shared `TableField` pair with divergent `filters()`, pinning the
  "different condition filters at the two reuse sites" message. That message has no test today, so
  the arm-placement constraint above is currently review-only; this is what makes it a build failure
  instead. Non-negotiable, since it is the only mechanical protection against the most likely way to
  mis-implement this item.
- Pipeline (`GraphitronSchemaBuilderTest`): a shared nested type carrying both leaves classifies
  per parent and validates clean. And the cross-parent R646 pin: a helper typed on one anchor's
  concrete table under a shared nested type produces the assignability rejection.

  Assert this one at the **outer** field coordinate, not the nested one. The check runs at classify
  time (`ExternalFieldDirectiveResolver.resolve` into `ServiceCatalog`'s parent-table layers), and the
  nesting arm of `FieldBuilder.classifyChildFieldOnTableType` rewraps a nested `UnclassifiedField` onto
  the embedding field with a `"nested type '<T>' field '<f>': "` prefix, so for a helper typed on
  `Customer`'s table the observable is `Store.location` classifying `UnclassifiedField` with that
  prefix and the anchor's table named in the message. `R58TypedRejectionPipelineTest.
  unknownColumn_throughNestedRewrapPreservesTypedShape` is the existing pin on that rewrap behaviour
  and the convention to follow. Per-anchor attribution is therefore *structural*, which is stronger
  than the earlier draft claimed.

  One consequence to assert deliberately rather than discover: because the rejecting parent's field is
  no longer a `NestingField`, the shared group drops to one member and `compareNestedFieldsShape` does
  not run at all on that schema. So this test proves the per-instance check fires; it does not
  exercise the interaction between that check and the new arm, and must not be described as if it
  does. If it does not fire, the admission design is wrong and the arm must not ship.
- Execution (`GraphQLQueryTest`): the extended `OccupantLocation` fixture resolves both new leaves
  under both parents, asserted against the equivalent flat reads (the convention of R645's
  `nestingField_projectedLeaves_agreeWithTheirFlatSiblings`).
- Corpus: no `@classified` verdict is expected to flip, since this gate is validation-time, not
  classification-time; confirm rather than assume at implementation time.

## Closed questions this item carries

- **`LookupTableField` re-scoping** (carried from R23, closed 2026-08-13 while specifying R645):
  needs no decision, because the leaf no longer exists. Neither does its batched sibling: the
  dissolution folded *both* lookup leaves onto their fetch siblings plus a `lookup()`
  (`LookupResolution`) facet, so the survivors are `TableField` inline and `BatchedTableField`
  batched, source-gated as usual. `LeafRatchetTest`'s `CHILD_FIELD_LEAVES` history line records that
  fold as the 24-to-22 drop, naming `LookupTableField` and `BatchedLookupTableField`.
  `RetiredVocabularyGuardTest` does not carry those two spellings; the retired lookup names it maps
  onto "the {table,record}-sourced lookup-keyed `BatchedTableField` arm" are the earlier
  `SplitLookupTableField` / `RecordLookupTableField` pair from the five-variant dissolution below.
  Both retirements land on the same survivor, so the conclusion is unchanged either way. The inline survivor is exactly the
  `TableField` arm R23 already admitted across parents; the record-sourced batched survivor is not
  nest-wireable; the table-sourced batched survivor is the deferral this item documents, which is why
  that row is worded at class granularity.
- **The original five-variant enumeration** (`SplitTableField`, `SplitLookupTableField`,
  `RecordTableField`, `RecordLookupTableField`, `Record*MethodField`): dissolved by R431/R432/R314
  into the source-gated `BatchedTableField` and the service leaves, with lookup carried as a facet
  rather than as its own class. Of the successors, only the Table-sourced `BatchedTableField` arm is
  nest-wireable, and it is the deferral this item documents.

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
- The mixed-classification edge (the `@nodeId` FK-mirror collapse diverging per anchor); the
  rejection stays, only its kind and wording change.
- Any nested-depth widening: `isNestedWireableLeaf` is untouched. A leaf newly admitted at nested
  depth brings its multi-parent question with it, and for an alias-projecting one the new arm's
  comment is where that obligation is recorded.
- The cardinality of a reverse-direction `{key: ...}` hop under a scalar `@reference`. The reverse
  hop off an FK's referenced side is one-to-many, and the scalar term renders `.limit(1)`
  (`PathFragments.scalarInnerSelect`), so it silently returns one arbitrary row. That is
  pre-existing behaviour on the flat path, identical with or without a shared nesting type, and the
  cap is what makes the term row-neutral by design. Sharing neither introduces nor worsens it, so
  this item leaves it alone and files nothing; if it should change, it changes for flat and nested
  together in an item of its own.
