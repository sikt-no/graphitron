---
id: R965
title: "Column-bound argument slots divine the tenant under a field-level @condition override"
status: Spec
bucket: bug
priority: 2
theme: classification-model
depends-on: []
created: 2026-09-22
last-updated: 2026-09-23
---

# Column-bound argument slots divine the tenant under a field-level @condition override

## Goal

A query field whose argument, or whose filter input's field, is bound to the tenant column keeps its
tenant binding whatever an authored `@condition` does to the predicate for that argument. Today it
loses it: the field is rejected at build time with "no argument or input field maps to tenant column
'X'", although the argument names exactly that column, so a schema that is correct by the author's
reading does not generate. *Tenant binding* is how a generated fetcher knows which tenant database to
route a query to; a *tenant-scoped* table is one carrying the column named by the Mojo's
`<tenantColumn>`, and a field reaching such a table is accepted only when something in scope can
*divine* that column, meaning supply one value for it. Whether a predicate is emitted for an argument
is an emission decision; where the tenant value comes from is a classification fact, and the two
should not be tied together.

The separation holds for every wire shape, including an encoded node id. It did not when this plan
was first written: the tenant component of a node key existed only after a decode the predicate path
performed, so dropping the predicate dropped the only place the value was produced, and the plan
carried a clause preserving today's behaviour for those carriers. R966 has since landed and made a
bound slot carry its *transform* beside its location, so the decode is a property of the slot
(`TenantBinding.SlotProjection.DecodedKeySlot`, resolved by `accessOf` and rendered by
`TenantDslEmitter.projected`) rather than of the predicate. The clause is therefore never written and
the mint predicate is uniform, which is the outcome this plan's seam section predicted for the order
the two items actually landed in.

One family still stays out, and it is a routing fact rather than an emission one: a `@lookupKey`
decoded key carries one tenant per row, so a list of them has no single tenant to route a statement
on. That exclusion is the lookup clause below, it is owned by `collectFromLookup` today, and it is
unaffected by anything here.

The pair that reported it, both real sis fields (2026-09-22 spike,
`<tenantColumn>INSTITUSJONSNR_EIER</tenantColumn>`):

```graphql
type Query {
  # classifies today: the override sits on a sibling argument
  personProfilerGittFodselsnumre(...): [PersonProfil]

  # rejected today: the same column binding, but the override sits on the field
  personProfilerGittFeideBrukere(
    eierOrganisasjonskode: String! @field(name: "INSTITUSJONSNR_EIER"),
    feideBrukere: [String!]! @field(name: "FEIDE_BRUKER") @lookupKey
  ): [PersonProfil]
    @condition(condition: {...}, contextArguments: ["feideDomene"], override: true)
}
```

`eierOrganisasjonskode` binds `INSTITUSJONSNR_EIER`, which is the tenant column. The field is rejected
anyway.

The reach is wider than that pair, and the discriminating set is what this item delivers. Every row
below is a schema that was built against the sakila fixture catalog with `film_id` as the tenant
column and its verdict read off `GraphitronSchema.tenantBindingOf`, not a reading of the code
(2026-09-22 spike; the `today` column re-checked against the post-R966 tree on 2026-09-23, where no
verdict moved, only the read behind the tenth):

| SDL shape, on a field returning tenant-scoped `film` | today | when this lands |
|------------------------------------------------------|-------|-----------------|
| `films(filmId: Int @field(name: "film_id"))` | `ArgumentBound` | unchanged |
| the same, with a *sibling* argument carrying `@condition(override: true)` | `ArgumentBound` | unchanged |
| the same, with a *field-level* `@condition(override: true)` | rejected | `ArgumentBound` |
| the tenant argument itself carrying `@condition(override: true)` | rejected | `ArgumentBound` |
| the tenant argument carrying its own `@condition`, no `override` anywhere | `ArgumentBound` | unchanged |
| a filter input whose field binds the column *by name*, under a field-level `@condition(override: true)` | rejected | `ArgumentBound` |
| a filter input whose field binds the column *by name* and carries *its own* `@condition`, no `override` anywhere | rejected | `ArgumentBound` |
| the tenant argument as a `@lookupKey`, under a field-level `@condition(override: true)` | `ArgumentBound` | unchanged |
| a composite `@nodeId @lookupKey` argument whose decoded key embeds the tenant column | rejected | rejected |
| a `@nodeId` argument or filter-input field whose decoded key embeds the tenant column | `ArgumentBound` | unchanged |
| the same, under a field-level `@condition(override: true)` | rejected | `ArgumentBound` |
| a field where nothing binds the tenant column | rejected | rejected |

Five rows are worth reading twice. The fourth says the Backlog framing was half right: it is not that
an argument-level override is safe and a field-level one is not, it is that an override anywhere over
the *tenant argument's own* predicate loses the binding, and the reported field only classified
because its override sat on a sibling. The seventh says `override:` is not even necessary to trigger
this: an input field carrying an ordinary `@condition` replaces its implicit predicate too, so the
same binding disappears with no override in the schema at all.

The ninth, tenth and eleventh are the decoded-key family, and they now split on one line rather than
sharing a reason. The ninth stays rejecting because `@lookupKey` routes it to the per-row family,
where one tenant per row belongs; that is the lookup clause, and it is the only decoded-key exclusion
this item keeps. The tenth is pure regression: it classifies `ArgumentBound` today reading the
decoded slot, and R966 pins that with `sameTableNodeIdFilterDivinesTheDecodedSlot`, so this item only
has to not break it. The eleventh is the row this item moves, and it moves because R966 landed
first: the suppression drops the predicate, the ledger mints anyway, and `accessOf` resolves the
slot's projection to the tenant component of the decoded key, so the field builds and routes where it
used to reject. Under the pre-R966 read it would have built and failed at request time, which is why
the earlier draft of this plan kept it rejecting.

A carrier the uniform mint predicate now reaches, and should: a multi-table polymorphic `@nodeId`
argument, whose `CallSiteExtraction.PruneOnMismatch` leaf `accessOf` *declines*, because the same
wire id decodes differently per participant and there is no single decode to route on. Suppressed,
such a field rejects today with the generic "nothing maps to the tenant column"; after this item it
rejects with that decline's own text, naming the shape. The verdict does not move, only the message,
and it moves in the direction the decline channel exists for. It holds whatever order the union lists
its members in, including an order whose first participant's key omits the tenant column, because
the fold reads each participant's ledger rows on their own, the way it reads each participant's
filters today.

The last row is the obligation that does not move: a field that binds nothing
to the tenant column still rejects, because routing tenant data through a default connection when
nothing named the tenant is the leak this axis exists to prevent.

## Mechanism (verified against the tree, 2026-09-23, post-R966)

`TenantBindingIndex.Fold.directBinding` discovers an operation's tenant slots per member. For a
condition member it delegates to `collectFromFilters`, which walks
`GeneratedConditionFilter.bodyParams()` and, through `collectFromBodyParam` and `collectFromRow`,
mints a `TenantBinding.BoundSlot` for every `BodyParam` whose column matches the tenant column. The
slot therefore exists only where a predicate was emitted. R966 renamed these three and gave the slot
a second axis; it did not change where the condition path sources them from, so this is the same
defect the item was filed against.

`FieldBuilder.projectFilters` is the single construction site of `GeneratedConditionFilter`, and it
declines to emit a predicate in three places, each of which removes a tenant slot as a side effect:

- `suppressedByFieldOverride` on the `ColumnBackedArg` / `ColumnBackedReferenceArg` arms: a
  field-level `@condition(override: true)` suppresses every classified argument's implicit predicate.
  The comment at the site reads "Either flag flipping true suppresses the implicit predicate for
  every classified field".
- `argCondition().override()` on the same arms: an argument-level override suppresses that one
  argument's predicate. Where the override sits on the tenant argument itself, its slot goes with it.
- `walkInputFieldConditions`, whose implicit predicate for a `ColumnBackedField` is conditioned on
  `!enclosingOverride && cf.condition().isEmpty() && !lookupBoundNames.contains(...)`. The middle
  clause is the documented input-field semantics (an authored `@condition` on an input field
  *replaces* the implicit equality rather than stacking with it, unlike the top-level argument case),
  so this arm loses the tenant slot with no override present anywhere.

The column binding survives every one of them. `ConditionResolver.resolveArg` documents the condition
axis as coexisting with the column-binding axis on the same argument; `ArgumentRef.ScalarArg.ColumnBackedArg`
still carries `columns()` beside `suppressedByFieldOverride`, and `InputField.ColumnBackedField` still
carries `columns()` beside `condition()`. The fold reads the wrong axis.

The other direct-slot paths do not have this problem, and the reason is the design this item
generalises: `collectFromLookup` reads `LookupMapping.ColumnMapping`, `collectFromTableInput` reads
`ArgumentRef.InputTypeArg.TableInputArg`, and `collectFromWhereKeys` reads `Dml.whereKeyColumns()`.
All three are classification carriers rather than emitted
predicates, so suppression cannot reach them. The eighth row of the goal table is that fact measured:
a `@lookupKey` tenant argument still classifies under a field-level override, because its slot never
came from a predicate.

## Implementation

The fold gets the column-binding axis as a fact of its own, minted where the walk holds it.

**Why this fact travels walk-side, and what retires it.** The store is where a new fact belongs
(`pipeline-overview.adoc`, "Classification gathers (transitional)": new facts land only in the store,
never in a new leaf type or walk-side registry), and the raw materials are already captured
(`graphitron_argument_binding_entry` for an argument's `@field` binding, `graphitron_field_column_scope`
for the table its names resolve against). This item cannot read them, and the reason is the pipeline's
stage order rather than a preference: `GraphQLRewriteGenerator.runPipeline` classifies before it
captures, so at the moment `TenantBindingIndex` runs, this run's rows are not in the store yet. A
store-sourced answer means moving the tenant verdict itself downstream of capture, which is a
re-platforming of the axis (its verdicts feed emitters, not only the error stream) and not a bug fix.
So the carrier below is interim by construction, and this plan says so rather than letting the next
reader infer it: it is keyed and grained the way the relation would be, one row per coordinate,
filtered table and slot. Grain is the half that carries over cleanly; the row's `CallSiteExtraction` column is the half
that does not, being an emit carrier no relation can hold, so the retirement is a repoint of one
reader *plus* a re-derivation of the extraction-to-read mapping from stored facts. That is a smaller
job than dismantling a leaf component and a larger one than the word "repoint" suggests, and the plan
would rather say so than have a later reader discover it. It retires when the tenant fold re-sources
onto the store, with the rest of the transitional surface.

**A walk-minted ledger, tenancy-neutral.** New `ColumnBindingLedger` in `no.sikt.graphitron.rewrite`,
rows keyed by `FieldCoordinates` and the `TableRef` the coordinate's filters resolve against, each
row a list of
`ColumnBoundSlot(String slotName, List<ColumnRef> columns, CallSiteExtraction extraction)`: every
argument and input field on the predicate path whose classification resolved a column, whatever
`projectFilters` then decided to emit for it. "On the predicate path" is doing work and the mint
predicate below states it exactly; a `@lookupKey` slot is not on it, and keeps the owner it has. It knows nothing about tenancy; it answers "which column does this slot name, and how is its value read at the
call site", which the classification has already answered and then been throwing away. The tuple is a
list because a composite carrier (the node-key case) binds several columns at once, and the columns
recorded are the ones the predicate would have bound (`predicateColumns(binding, columns)` on the
reference arms), so a `@reference`-reached tenant column keeps divining exactly as today's
`RemoteColumnPredicate` unwrap does. `NodeIdDecodeLedger` is the shape precedent, not the provenance
precedent: that ledger records what the walk *did*, which no relation could hold, while this one
records a function of the SDL and the catalog that a relation will hold.

**The row carries the extraction, and `accessOf` stays the one resolver.** A row holds the
`CallSiteExtraction` the classification already resolved for that slot beside the columns the
predicate would have bound, and the fold turns the pair into a `TenantBinding.BoundSlot` through the
existing `accessOf`, which is where the tenancy projection belongs: the ledger stays free of tenancy
vocabulary, and the extraction-to-access mapping stays in one function rather than being copied to a
second site. The honest cost is that `CallSiteExtraction` is an emission carrier (its decode arms
reach `HelperRef`), so a row holding one is not yet the plain value row the relation this prefigures
would hold; that conversion belongs with the re-sourcing, not ahead of it.

The ledger read hands `accessOf` the same three things `collectFromRow` hands it today, which is what
makes this a re-sourcing rather than a new resolution path: the row's extraction, a fallback read of
`TenantBinding.SlotRead.TopLevelArg.INSTANCE`, and the matching column's index in the row's own
column list. The index needs no new component on the row, and that is worth stating because it is the
one place a reader would expect one. `accessOf`'s `decodeSlot` parameter is the tenant column's
position in the key tuple a decode returns, and today `collectFromRow` derives exactly that from the
position in `rowEq.columns()`, which is `predicateColumns(binding, columns)`, which is what the row
records. The alignment holds on both bindings: `FilterBinding.Local` contributes `ownTableColumns`,
the lifted FK tuple R966's write path already reads at the decode slot its position names, and
`FilterBinding.Remote` contributes the carrier's own columns, which are the target NodeType's key
tuple and so are the decode's output by construction.

A nested input field needs no special handling either. Its row carries the wrapped
`NestedInputField(outerArgName, leafPath, leaf)` extraction, and `accessOf` matches that arm first,
taking the read from it and the transform from its leaf, so the fallback is ignored exactly as it is
on today's condition path, where the body param carries the same wrapped extraction.

One thing `accessOf` owes on the way past, carried over from this plan's earlier draft and unchanged
in force by R966. Its leaf switch ends in `default -> SlotProjection.Raw`, which reads "the wire value
already is the tenant value". That is the right answer for `Direct`, `JooqConvert`, `EnumValueOf`
and `ContextArg`, and the wrong one, silently, for any future extraction arm that decodes. Making the leaf switch
exhaustive turns a new arm into a compile error asking which projection it is. It changes no verdict
today, because every arm reaching it keeps today's answer, and the four record-shaped arms
(`NodeIdDecodeRecord`, `NodeIdDecodePolymorphicRecord`, `InputBean`, `JooqRecord`) throw an invariant
naming the carrier rather than answering: they are whole-input extractions minted by
`InputBeanResolver`, `ServiceCatalog` and the fetcher generator for arguments that bind no single
column, so no column-bound slot can carry one, which is what makes the exhaustiveness worth more than
a `default` under a longer spelling.

The two location arms differ at the leaf switch, because the first switch treats them differently.
`NestedInputField` unwraps to its leaf, so it reaches the leaf switch only through a doubly-wrapped
extraction nothing mints, and throws the same invariant. `ContextArg` does not unwrap: its first-switch
arm sets the `SlotRead.ContextArg` location and passes the extraction itself on as the leaf, so a bare
`ContextArg` reaches the leaf switch on every slot that carries one and resolves `Raw` today through
the `default`. It keeps `Raw` as an explicit arm. A context value is a value the caller supplied, not
an encoding of one, which is the same reading `Direct` gets; and throwing there would turn the
first-switch arm, and the `SlotRead.ContextArg` renderings in `TenantDslEmitter` and
`RoutineWriteCommands.slotReadOf`, into a guaranteed crash. Nothing mints a `ContextArg` on a
column-bound carrier today (its one construction is `MethodRef`, for method parameters), so this
decides what the switch says, not any verdict.

**Held and threaded like the decode ledger.** A `private final ColumnBindingLedger` field on
`BuildContext` beside `decodeLedger`, with a package-private accessor; `GraphitronSchemaBuilder` reads
it off the context at schema assembly, the same read it already makes for `bctx.decodeLedger()`, and
passes it into `TenantBindingIndex.compute` beside `operationMembers`. `compute` keeps its
not-computed sentinel discipline: a `ColumnBindingLedger.EMPTY` compared by reference identity, so a
hand-built schema that never ran the walk is refused rather than silently classifying everything
unbound, exactly as the `OperationMemberRelation.EMPTY` check does today.

**Two mint sites, five arms, and the predicate at each.** The mint predicate is stated per arm rather
than as "before the suppression test", because each guard in these two functions mixes clauses of two
kinds and only one of them is a suppression the ledger may record through.

A *suppression* clause says a predicate the slot would otherwise have contributed is being dropped in
favour of authored SQL. That is emission, and recording through it is the whole of this item. The
clauses are `!autoSuppressed` on the argument arms and `!enclosingOverride &&
<carrier>.condition().isEmpty()` on the input-field arms, the second pair being the documented
replace-rather-than-stack semantics for an input field, which is a suppression under another
spelling.

A *lookup* clause says the slot is not on the predicate path at all, because `@lookupKey` routes it to
`LookupMappingResolver` and the VALUES+JOIN input-rows helper instead. That is a different mechanism,
which mints its own tenant slots through `collectFromLookup`, and the ledger records nothing there.
The clauses are `!ca.isLookupKey()` and `!lookupBoundNames.contains(<carrier>.name())`.

There is no third kind. An earlier draft of this plan carried a *decode* clause, making a decoding
carrier honour the suppression it honours today, because the fold could not then read the tenant
component out of an encoded key. R966 landed that read, so the clause has nothing left to protect and
is not written. What it would have cost is worth recording for the reader who finds the goal table's
eleventh row surprising: five spellings of a predicate, a `decodesAKey` helper with five callers, and
a comment at each site explaining a coupling between classification and emission that no longer
exists.

Taken together, the predicate at each arm is that arm's own guard with its suppression clauses
dropped, and nothing else moved.

In `projectFilters`:

- `ColumnBackedArg`, whose guard is `!autoSuppressed && !ca.isLookupKey()`: mint when
  `!ca.isLookupKey()`.
- both `ColumnBackedReferenceArg` arms, whose guard is `!autoSuppressed` alone: mint unconditionally.
  They have no `isLookupKey` slot by construction, an FK-target being a filter and not a lookup, so
  there is no lookup clause to honour either.

In `walkInputFieldConditions`, at the site that already computes `leafPath`, which is the nested read
path the slot needs:

- `ColumnBackedField` and `ColumnBackedReferenceField`, whose guard is
  `!enclosingOverride && <carrier>.condition().isEmpty() && !lookupBoundNames.contains(<carrier>.name())`:
  mint when `!lookupBoundNames.contains(<carrier>.name())`. The extraction the row carries here is the
  wrapped `NestedInputField(outerArgName, leafPath, leaf)` the body param would have carried and not
  the bare leaf, because that is what `accessOf` reads to resolve a `NestedInput` location while
  taking the transform from the leaf. "Would have carried" is exact: `implicitBodyParam` rewrites a
  `Direct` leaf on an `ID`-typed field to `JooqConvert` before wrapping it, and the row takes the
  post-rewrite leaf, so the mint reuses the wrapping `implicitBodyParam` does rather than wrapping
  `<carrier>.extraction()` a second way. Both leaves resolve `Raw`, so no verdict hangs on it; what
  hangs on it is the row being what it says it is.

Completeness is structural rather than reviewed, and the lookup clause is what makes the claim true in
both directions. Those five arms are exactly where the `BodyParam`s `collectFromFilters`
reads are constructed, and `projectFilters` is the only construction site of
`GeneratedConditionFilter` in the tree, so nothing on the predicate path escapes the ledger. The
lookup clause is the other half: without it the ledger's domain would be strictly *larger* than the
predicate path's, and larger in the one direction where a larger domain is wrong rather than merely
redundant. Stated as a set, the ledger's domain is the predicate path's domain plus the suppressed
column bindings, which is this item restated without prose.

`LookupMappingResolver` routes a composite `@lookupKey` `ColumnBackedArg`, and
an input argument's `InputColumnBindingGroup.DecodedRecordGroup`, to
`LookupMapping.ColumnMapping.LookupArg.DecodedRecord`, which `collectFromLookup` skips on purpose: a
decoded node id carries its own tenant per row, so a list of them has no single tenant to route the
statement on. A ledger minted ahead of the lookup clause would hand the fold one anyway, and a
cross-tenant `ids:` batch would read whichever database the first decoded key pointed at. That is the
ninth row of the goal table, and it is the one decoded-key exclusion that survives R966: the read
R966 widened resolves a single decode, which is exactly what a per-row family does not have.

The scalar half of the lookup exclusion is inert rather than load-bearing, and saying so keeps that
clause from looking arbitrary: a scalar `@lookupKey` argument and a `MapGroup` input field are
already minted by `collectFromLookup` under the same slot names with the same accesses, so a
duplicate ledger mint would change no verdict. The clause is uniform because the rule is about which
mechanism owns a slot, not about which duplicates happen to be harmless.

**Why the filtered table is in the key.** Both sites run once per participant on a multi-table
polymorphic coordinate, and the same slot name can bind a different column tuple on each. A bare
`@nodeId` argument over `union Occ = Inventory | FilmActor` binds `[inventory_id]` for one participant
and `(actor_id, film_id)` for the other, because each participant decodes the id as its own node
type. A slot's columns are therefore a fact of the coordinate, the table the filters resolve against
and the slot, and a row keyed by coordinate and slot alone would have to throw one participant's
tuple away. Keeping the first would drop whichever participant classified second, *before* the fold
has matched either against the tenant column. That is not the dedupe `SlotCollector` does. It matches
first and dedupes after, among resolved slots only, and it keeps every distinct decline. In the
`Inventory`-first order the first-mint row would carry no tenant column, and the field would lose the
`PruneOnMismatch` decline it rejects with today, in either member order, with no override present.

So the key is `(FieldCoordinates, TableRef)`, where the `TableRef` is the `rt` that `projectFilters`
receives. That is the same table the coordinate's condition member names as `Condition.table()`:
the multi-table loop hands `tb.table()` both to `resolveTableFieldComponents` and to the
`ParticipantFilters` that `OperationMember.Condition.OnParticipant` is minted from, and the
single-table paths hand the return type's table, which `OnReturnTable` reads back as
`sgf.returnType().table()`. The containment pin below reads rows by `Condition.table()`, so any path
where the two disagree turns it red on the first fixture that reaches it, rather than silently losing
a slot. The ledger's only dedupe is first-mint-wins on slot name *within* one `(coordinate,
table)` row, which absorbs a repeat visit of the same classification (`NodeIdDecodeLedger` keys
first-mint-wins for the same reason) and never compares one participant with another.

**The fold reads it.** `Fold.directBinding` replaces
`case OperationMember.Condition c -> collectFromFilters(c.filters())` with a read of the ledger row at
`(coordinate, c.table())`, calling `collector.add` once per column that `matchesTenantColumn` accepts
and resolving its access through `accessOf`, which is what `collectFromBodyParam` and
`collectFromRow` do between them today. The read is per member, as today's is: one condition member
per participant, each reading its own participant's row, and `SlotCollector` matching then deduping
across them exactly as it does now. `directBinding` gains the coordinate as a parameter to do it; its
four callers in the fold already hold the coordinate they pass the members for. `collectFromFilters`, `collectFromBodyParam` and
`collectFromRow` retire together: with the ledger in place nothing in production reads a `BodyParam`
to answer a classification question, which is the one-place property this item is after. The
`RemoteColumnPredicate` unwrap those functions perform retires with them, because the ledger records
`predicateColumns(binding, columns)` at mint time and so never sees the wrapper. The containment pin
below still reads `bodyParams()`, and deliberately: it reads them to enforce that the ledger covers
them, which is the opposite of deriving a classification from them.

Nothing else about the fold moves. `directBinding` keeps its `SlotCollector`, its dedupe-by-name and
its `DirectBinding` result, so a ledger row whose access comes back `SlotAccess.Declined` declines the
coordinate through the same channel every other carrier uses, and `divines()` keeps its meaning.

**The seam with R966, which has landed.** R966 put a second component on
`TenantBinding.BoundSlot`, a projection axis beside the location axis, and routed every minting site
through `accessOf`, which replaced `readOf` and now answers both axes plus a decline. It landed
first, so this plan is written against its fold rather than predicting it, and the prediction this
plan made for that order held: the decode clause is never written, the mint predicate is uniform, and
the goal table's eleventh row moves to `ArgumentBound` instead of staying rejected.

What remains of the seam is one direction, and it is additive. This item removes
`collectFromFilters` as a minting site and puts the ledger read in its place, so R966's minting-site
list becomes the ledger read, `collectFromLookup`, `collectFromTableInput` and `collectFromWhereKeys`.
No R966 surface changes: `accessOf`, `SlotAccess`, `SlotCollector` and `DirectBinding` are read as
they are, and the ledger hands `accessOf` the same three arguments `collectFromRow` hands it today.

An earlier draft of this plan worried about a naming inversion, a tenancy-neutral ledger reaching a
type nested under `TenantBinding`. It does not arise: the row holds a `CallSiteExtraction` and a
column list and nothing else, and the fold supplies the `TenantBinding.SlotRead` fallback at the call
to `accessOf`. The ledger names no tenancy type, which is the property that keeps it retirable onto a
relation.

**What stays.** `collectFromLookup`, `collectFromTableInput` and `collectFromWhereKeys` stay
as they are. They already read classification carriers (`LookupMapping.ColumnMapping`, the
`TableInputArg` envelope, `Dml.whereKeyColumns()`), which is why they are already override-proof, and
this item leaves the fold with three direct-binding descents where it has four today rather than
adding a fifth. `collectFromLookup`
also stays the *sole* owner of lookup-bound slots, which the lookup clauses on the mint predicate are
what enforce; its `DecodedRecord` skip therefore keeps meaning what it says. Giving a decoded key a
tenant verdict of its own is the per-row family's question, not this axis's, and it belongs with the
node-dispatch facts that already partition per decoded tenant.

The gap an earlier draft recorded here as R966's is closed: `collectFromInputFields` now handles
`InputField.ColumnBackedReferenceField`, reading a `FilterBinding.Local` tuple at its decode slot and
declining a `FilterBinding.Remote` one, so the write path no longer drops the carrier its query-path
twin handles. Nothing in this item touches that descent.

**The emitter audit the producer relaxation owes.** Widening which coordinates classify
`ArgumentBound` is a producer relaxation, so every emit site assuming the old population was audited:
`TenantDslEmitter.dslExpression` throws on `ArgumentBound` at its expression-only site, justified by
service operations contributing no argument slots, and that claim survives because the mint stays
inside `projectFilters`, which no `@service` coordinate reaches. `TenantDslEmitter.slotReads` and
`TenantAcquisitionFragments.slotRead` render over the slot's two axes alone and assume no matching
body param, so the overridden-predicate case needs nothing new from them; `TenantDslEmitter.projected`
renders the decode helper for a `DecodedKeySlot` projection off the slot, not off a predicate, which
is what lets the goal table's eleventh row route at all. `MultiTablePolymorphicEmitter`'s
`ArgumentBound` read is per-participant and unaffected: the ledger is keyed by coordinate and
participant table and read per condition member, so the deduped slot list it produces is the one
today's per-member read produces.

**The disclosed gap.** A wire-valued argument whose own predicate an author overrode now divines the
tenant. That is the intended reading, since routing chooses a database and an argument bound to the tenant column
names a tenant whatever predicate runs inside that database. The cost is that a condition method
reinterpreting the value (a prefix match, a deliberately cross-tenant `IN`) routes on a value the
author did not mean as a tenant id, and nothing at build time enforces that it did not; the generated
`divinedTenant` agreement guard is a runtime check and not an enforcer. There is no SDL way to say
"names the column, does not divine", and inventing one here would widen this item into a directive
surface. Stated as a gap, to be an item of its own if an author need appears.

## Retired vocabulary

- `TenantBindingIndex.Fold.collectFromFilters`, `collectFromBodyParam` and `collectFromRow`: the
  predicate-derived slot mint, and with them the fold's `RemoteColumnPredicate` unwrap. `accessOf`
  survives and keeps its name; prose describing the tenant fold as reading body params
  is stale after this item, with the one exception the containment pin makes explicit, which reads
  them to check coverage rather than to classify.
- `decodesAKey`: never written. It is named here because two rounds of this item's review argued
  about it, so a reader meeting the term in the findings below should know it did not reach the tree.

## Tests

`TenantBindingClassificationTest` (L2 unit tier, the file that already carries one fixture per
`TenantBinding` arm) takes the goal table as cases: the five rejecting rows that become
`ArgumentBound`, the five unchanged rows as regression, and the two rows that stay rejected
asserting the rejection is still typed `Rejection.AuthorError.NoTenantBinding`. Each `ArgumentBound`
assertion names the slot and both its axes, so a nested filter-input row pins
`NestedInput(filter, [filmId])` with a `Raw` projection and not merely that something bound.

Two of those fixtures carry more weight than the rest, because each is a case where a wrong mint
predicate still produces a green build. Both sit on a field that also carries a `@condition`, so the
coordinate mints a condition member and the ledger read actually fires:

- The ninth: a composite `@nodeId @lookupKey` argument whose decoded key embeds the tenant column,
  with a plain `@condition` and no `override` anywhere. It fails if the mint predicate drops its
  lookup clauses, and the absence of an override is what makes that true rather than incidental: with
  no suppression active, the lookup clause is the only thing keeping this row out of the ledger. A
  reader who simplifies it away gets a green build and row nine classifying `ArgumentBound`, which is
  a cross-tenant `ids:` batch reading one tenant's database.
- The eleventh: a same-table `@nodeId` argument at arity 1 whose decoded key is the tenant column,
  under a field-level `@condition(override: true)`, asserting `ArgumentBound` with the slot's
  projection pinned to `DecodedKeySlot` at the right index. This is the row this item moves, and it is
  the one assertion that proves the two axes compose: the location comes from the suppressed carrier
  the ledger recorded, and the transform comes from `accessOf` reading its extraction. Arity 1 is
  deliberate, being the shape a column-count discriminator would get wrong.

Both fail in the direction a green build would not otherwise show, since a wrong answer there is
a verdict rather than an error. The tenth row's argument half needs no fixture of this item's: R966
landed `sameTableNodeIdFilterDivinesTheDecodedSlot` on exactly that shape, and this item only has to
leave it green. Its filter-input half has no pin in the tree, and this item re-sources exactly that
read (a nested `@nodeId` field's slot moves from a body param to a ledger row), so it gets one
regression case: a query filter input whose `@nodeId` field decodes a key embedding `film_id`,
asserting `ArgumentBound` with a `NestedInput` read and a `DecodedKeySlot` projection.

The multi-table polymorphic shape gets two cases on one fixture of its own. The fixture
`MultiTableFilterLoweringTest` carries for `PruneOnMismatch` (`Customer | Staff`) does not serve:
neither table carries `film_id`, so under this test file's tenant column the field classifies
untenanted and there is no rejection to assert. The fixture here is a bare
`occ(id: ID! @nodeId): [Occ!]!` over `union Occ = Inventory | FilmActor`, with `Inventory` keyed on
`inventory_id` and `FilmActor` on `(actor_id, film_id)`: both tables are tenant-scoped, and the
member order puts first the participant whose key omits the tenant column, which is the order that
tells a per-participant read from a coordinate-wide first-mint dedupe.

- With no `@condition` anywhere, it rejects `Query.occ` with the `PruneOnMismatch` decline text. That
  is today's verdict and today's message, measured on this tree in both member orders; the case pins
  that the re-sourcing did not change it.
- Under a field-level `@condition(override: true)` (`TestConditionStub.lifterFieldCondition`, which
  binds no argument, so the dispatched-`@nodeId` divergence refusal does not fire first), it still
  rejects with the decline text rather than the generic "nothing maps to the tenant column". It pins
  that the uniform mint predicate reaches the declining carrier and that the decline channel, not a
  mint-side clause, is what holds it.

**The containment pin, which is the enforcer the census owes.** Today's `collectFromFilters` is total by
accident of where it reads: it walks `gcf.bodyParams()`, so a sixth column-bound arm added to
`projectFilters` tomorrow contributes a tenant slot with no further work. The ledger inverts that.
Totality becomes a correspondence between the arms that construct a `BodyParam` and the arms that
mint a row, and an arm that emits a predicate and forgets a mint reproduces this item's own bug, a
field that names the tenant column rejected for not naming it, with nothing in the tree failing. The
five-arm census above is true as written (verified: `bodyParams` is appended at
`FieldBuilder.java:2825`, `2832`, `2852`, `2868` and, through `implicitBodyParams`, `2954` and `3011`
folded in at `2772` and `2788`, and nowhere else), but a census true when written is the "unguarded census" the enforcer principle names
as a drift smell.

So `ColumnBindingLedgerContainmentTest`, a meta-test in `graphitron`'s test tier, driven over every
fixture schema the tier already builds rather than over one fixture of its own: for each condition
member, every surviving `BodyParam` in its filters naming a column (unwrapping
`RemoteColumnPredicate`) has a ledger entry under the same slot name, in the row at
`(coordinate, member.table())`, carrying that column. A new arm that emits without minting turns it
red on whatever existing fixture first reaches the arm, and so does a classification path whose `rt`
is not the table its condition member names, which is the one alignment the ledger's key relies on.
Reading by member rather than by coordinate is what makes the pin and the key agree: the `Customer |
Staff` fixture emits a `staff_id` body param for slot `id` on one participant and a `customer_id` one
on the other, and each finds its own row.

This is not the oracle the shadow tests (`ColumnMatchShadowTest` and siblings) are, and the difference
is the one `fact-model.adoc` draws under "Name the row, not the question": what is forbidden is the
*total-agreement* test, asserting the replacement equals the predecessor, which would install
`collectFromFilters` as normative and pin its gaps. What is asked for instead is "agreement asserted
where the two are meant to agree, and each deliberate departure asserted in the direction it was meant
to go". Containment in one direction is exactly that. It keeps no predecessor alive, since
`bodyParams()` is a live production that stays; it asserts nothing about the ledger-only direction,
which is where both deliberate departures live (the suppressed column bindings this item adds,
and the lookup slots); and the unchanged rows of the goal table remain the
regression statement for the verdicts themselves.

The compile tier gets one field in
`graphitron-sakila-example/src/main/resources/graphql/multitenant.graphqls`, that module's proof that
every `TenantBinding` arm emits valid Java 17: a root whose tenant argument sits under a field-level
`@condition(override: true)`, using `tilgangAdminOnly` from
`no.sikt.graphitron.rewrite.test.conditions.InputFieldConditionFixtures` (the class the example's other
`@condition` fixtures already use, and its one method binding no argument). It earns a place in a
fixture whose discipline is "one field per arm, nothing else" by being the only field there whose slot
cannot come from a predicate.

No execution-tier case: the arm's runtime behaviour is unchanged, only which schemas reach it.

## Documentation

`docs/manual/how-to/condition-cascade.adoc` gains one sentence in each override section saying that
`override:` governs predicate emission and never the tenant routing a column-bound argument divines,
cross-referencing `tenant-scoping.adoc`. `docs/manual/how-to/tenant-scoping.adoc` already promises that
"an argument bound to the tenant column routes the statement and hands the tenant down its subtree"; it
needs no change, because this item is what makes the promise true.

## Other solutions we've considered

**Stop suppressing the tenant argument's predicate.** The fold would find its body param again with no
new fact anywhere. Rejected: it changes the SQL an author explicitly asked to own, and it does it by
making the emission decision depend on the tenant column, which is the coupling this item exists to
remove.

**Emit the suppressed `BodyParam`s carrying a `suppressed` flag.** Every consumer of `bodyParams()`
would have to filter, and the renderer's contract, that a body param is a predicate, is worth more than
the one reader it would save.

**Carry the slots as a `TableFieldComponents` component and an `SqlGeneratingField` accessor.** The
fact would ride an existing surface instead of a new one, but that surface is implemented by every
SQL-generating leaf variant and read by every generator, and the fact has exactly one reader. It is
also the leaf-extension half of what the pipeline doc rules out, with no retirement story: a ledger of
value rows repoints at a relation, a leaf component has to be dismantled.

**Source the fact from the store now.** The documented destination, and unreachable at this stage
order: classification runs before capture, so the relations this would read hold the previous run's
rows, if any. See the first Implementation paragraph.

**Mint the decoded-key carriers too, and table the request-time failure.** Considered and rejected
while R966 was still in flight, and recorded because the two rounds of findings below turn on it. The
proposal was a uniform mint predicate with no decode clause, and two goal-table rows saying those
shapes move from a build error to a request-time one until R966 widened the read. Rejected then: a
build error an author can see is worth more than a request error their consumers see, and a plan that
knowingly ships a wrong read because a sibling item will fix it stops being reviewable on its own
terms. R966 landing first is what makes the uniform predicate correct rather than merely cheaper, so
this plan now has the end state the proposal wanted without the interval it would have shipped.

**Depend on R966.** Rejected as a *dependency* rather than as a design, and the distinction survived
the event: the two items composed in either order, a `depends-on` would have bought sequencing this
item did not need while putting eleven sis roots behind a hundred and five, and R966 landed first
anyway. What the alternative was right about is now simply the tree.

## Provenance

A sis spike on 2026-09-22 configured `<tenantColumn>INSTITUSJONSNR_EIER</tenantColumn>` against the
sis schema and counted 875 `Rejection.AuthorError.NoTenantBinding` errors, against 0 on the same tree
and database without the setting. 121 of those are root fields; the remaining 750 are children
cascaded by the every-path fold in `TenantBindingIndex.tenantContextOf`, which demands a tenant
context on every path into a tenant-scoped type. 11 of the 121 roots are this item: `brukere`,
`personProfiler`, `studenter`, `emner`, `emnerV2`, `esiKandidater`, `evuKurs`, `studieoppbygninger`,
`utvekslingsavtaler`, `personProfilerGittFeideBrukere`, `studenterGittFeideBrukere`. The count
understates the reach, because `Query.personProfiler` is among them and its failure keeps every
`PersonProfil` child red through the every-path fold.

105 more of the 121 are R966, the sibling classifier gap for write inputs keyed by a decoded node id,
which has since landed and sits In Review. The two were independent and neither unblocked the other;
the cascade this one clears is its own, the 11 roots plus every `PersonProfil` child that
`Query.personProfiler` keeps red. Those 11 are still red on the tree R966 left, since R966 widened how
a slot is read and this item is what mints the slot at all.

Every one of the 875 rejections printed without file:line coordinates, because the fold's rejections
carry `SourceLocation.EMPTY`. That is R523; sis is its motivating multi-file case.

## Reviewer findings

### Round 1 (2026-09-22, Spec -> Ready, reviewer session 01KrD23r4zJgrVAVMqLjcyja)

Verdict: withhold. One blocking finding on question two. Question one passes cleanly: the goal reads
without reconstruction from the plan (a schema whose argument or filter-input field is annotated with
the tenant column, and which also carries an authored `@condition`, stops failing the build and starts
routing to the tenant that argument names), the mechanism section matches the tree line for line, and
every symbol, test, fixture and doc sentence the spec names exists as named.

**Finding 1 (question two: architecture fit). The mint predicate is stated against the override
guards only. Each of the three arms it names also carries a lookup-key clause, and the plan does not
say which side of it the ledger sits on.**

The Implementation section places the mints "before the `autoSuppressed` test" and "outside the
`!enclosingOverride && condition().isEmpty()` guard". Neither guard has that shape in the tree:

- `projectFilters`, `ColumnBackedArg` arm: `if (!autoSuppressed && !ca.isLookupKey())`
  (`FieldBuilder.java:2809`). "Before the `autoSuppressed` test" is before both clauses.
- `walkInputFieldConditions`, `ColumnBackedField` arm:
  `!enclosingOverride && cf.condition().isEmpty() && !lookupBoundNames.contains(cf.name())`
  (`FieldBuilder.java:2946-2948`). The Mechanism section quotes all three clauses correctly; the
  Implementation section restates the same guard with the third dropped.
- `ColumnBackedReferenceField` arm: the identical three-clause guard (`FieldBuilder.java:2992-2994`).

One direction of the unstated choice is inert and the other is not, which is why the plan reads as
safe on a first pass. Inert: a scalar `@lookupKey` argument and a `MapGroup` input field are already
minted by `Fold.slotsFromLookup` under the same slot names with the same reads, and `directSlots`
dedupes by slot name, so a duplicate ledger mint changes no verdict. Not inert: `LookupMappingResolver`
routes a *composite* `@lookupKey` `ColumnBackedArg` and an input argument's
`InputColumnBindingGroup.DecodedRecordGroup` to `LookupMapping.ColumnMapping.LookupArg.DecodedRecord`
(`LookupMappingResolver.java:53-77`), and `slotsFromLookup` skips that arm deliberately: "Decoded
node-id lookups carry per-id tenants (the per-row family), not a single argument value; they classify
through the node dispatch facts, never as an ArgumentBound slot" (`TenantBindingIndex.java:700-703`).
A ledger minted ahead of the lookup clause records those slots with the tenant column on them, the
fold's ledger read turns them into an `ArgumentBound` slot, and a coordinate that today partitions per
decoded id would route one statement on one key. A cross-tenant `ids:` batch then reads a single
tenant's database. The shape is reachable inside this item's own subject matter, since the ledger row
is read only where a `Condition` member exists and any `@condition` on such a field mints one, and it
is not a row of the goal table.

This is also what the plan's completeness argument rests on: "those two sites are exactly where the
`BodyParam`s `slotsFromFilters` reads are constructed ... so the ledger's domain is the predicate
path's domain by construction". Under the literal reading the ledger's domain is strictly larger than
the predicate path's, and larger in the one direction where the lookup axis holds an explicit
non-divining decision. That sentence is what a Done-gate reviewer would lean on instead of re-deriving
the domain.

What would satisfy it: state the mint predicate at each of the three arms with its lookup clause, and
say which way the decoded-key shape goes. If lookup-bound slots stay out of the ledger, that is one
clause per mint plus a sentence that the lookup axis keeps its single owner. If they go in, the goal
table owes a row for the decoded-key shape saying what it classifies as today and after, and the plan
owes what `readOf` returns for `CallSiteExtraction.NodeIdDecodeKeys` once the switch is made
exhaustive, since that extraction reaches the `default` arm today and reads as `TopLevelArg`.

**Non-blocking, noticed on the way past.**

- `tilgangAdminOnly` resolves `table.field("rollekode", String.class)` off the table handed in
  (`InputFieldConditionFixtures.java:192`), which is a routine result table at its existing call site.
  On a `film`-bound field in `multitenant.graphqls` the emitted Java compiles, which is the whole of
  what that tier asks, but the predicate names a column `film` does not carry. Harmless while the item
  adds no execution case, as it says it does not; worth knowing if one is ever added there.
- The not-computed sentinel check lives in the `Fold` constructor (`TenantBindingIndex.java:180`),
  not in `compute`, which delegates to it. Nothing changes for the implementer.
- `directSlots(List<OperationMember> members)` takes no coordinate; `armOf` holds it and would pass it
  down. A signature change the plan does not mention and an implementer will not miss.

#### Author response (2026-09-22, same session taking the author role at the user's direction)

Finding 1 addressed by keeping lookup-bound slots out of the ledger, which is the answer that leaves
`slotsFromLookup` the sole owner of the lookup axis and keeps the item's own "classification carriers,
not emitted predicates" reading intact. "Two mint sites, and why they are complete" is rewritten as
"Two mint sites, five arms, and the predicate at each": the predicate is now stated per arm with its
lookup clause, the two kinds of guard clause are distinguished (a suppression, which the ledger
records through, against a lookup routing, which it does not), and the completeness claim is restated
in both directions rather than only the one it held in.

The decoded-key shape is now the ninth row of the goal table, rejected today and rejected after, with
the reason stated where a reader checks it rather than only in the Implementation. `## Tests` pins it,
and says why that fixture carries more weight than its neighbours: it is the one case that turns red
if the lookup clauses are dropped, and the wrong answer there is a verdict rather than an error, so a
green build would not otherwise show it. The stale count beside it ("the three rejecting rows") is
corrected to four in the same edit.

`readOf`'s exhaustive switch now says what each arm answers instead of only that it becomes
exhaustive: every arm reaching it today keeps today's answer, `NodeIdDecodeKeys` is written out
because it is the non-obvious one, and the four record-shaped arms throw rather than defaulting, which
is what makes the exhaustiveness worth more than a `default` under a longer spelling.

Non-blocking items 2 and 3 left as they are: both describe the tree accurately and neither changes
what the implementer builds. Item 1, the `tilgangAdminOnly` fixture naming a column `film` does not
carry, is left standing deliberately, the compile tier asking only that the emitted Java compiles and
the item adding no execution case; it is recorded here so a later execution case does not rediscover
it as a surprise.

Author and reviewer were the same session for round 1 and this revision, so the `Spec -> Ready`
sign-off needs a session that has committed neither.

### Round 2 (2026-09-22, Spec -> Ready, reviewer session 011oYfTpr6ETNvTtxnkJK7tR)

Verdict: withhold. One blocking finding on question two. Round 1's finding is settled: the mint
predicate now reads per arm with its lookup clause, the five arms and their guards match the tree
verbatim (`FieldBuilder.java:2809`, `2846`, `2863`, `2946-2948`, `2992-2994`), the decoded-key
exclusion is the ninth goal-table row, and the test that pins it is named. Question one passes: the
goal states what changes for a consumer without reconstruction from the plan, and the sis provenance
makes the value concrete.

**Finding 2 (question two: architecture fit). The ledger admits `NodeIdDecodeKeys`-extraction
carriers that `@lookupKey` does not exclude, and the plan justifies their read with a claim that is
false about the emitted code.**

The mint predicate keeps the *lookup* half of the decoded-key family out and lets the *filter* half
in, on a discriminator (`isLookupKey`) that is orthogonal to the concern row nine exists for. Two
carrier families reach the ledger with a `NodeIdDecodeKeys` extraction:

- A same-table composite `@nodeId` `ColumnBackedArg` carries `isLookupKey == false` when `@nodeId`
  targets the field's own table without an explicit `@lookupKey` (`FieldBuilder.java:2236-2240`, and
  the comment at `2810-2816` says so). The mint predicate is `!ca.isLookupKey()`, so it mints.
- Both `ColumnBackedReferenceArg` arms, and `InputField.ColumnBackedReferenceField`, declare their
  extraction as `CallSiteExtraction.NodeIdDecodeKeys` at *every* arity (`ArgumentRef.java:183`), and
  the plan mints on those arms unconditionally. This is not a composite-only corner.

For both families the ledger row holds the key tuple, the fold keeps the component matching the
tenant column, and `readOf(NodeIdDecodeKeys)` yields `TopLevelArg`. What `TopLevelArg` renders is
`env.<Object>getArgument(name)` (`TenantAcquisitionFragments.java:124-125`), the *encoded* wire id.
Nothing decodes it. So the plan's sentence, "the slot reads the whole top-level argument and the
divined key is the tenant component of the tuple the decode produces", is wrong about the emitted
code: the divined key is the base64 text. R966, already Ready on this same fold, states the same
fact as a defect it fixes ("already mints a slot that reads the encoded ids as tenant keys ... it
fails at request time on a schema that should route").

It fails closed rather than leaking, which is why this is a correctness-of-the-plan finding and not
a security one: `divinedTenant` parses a String to `Integer`/`Long` for a numeric tenant column and
throws `NumberFormatException` (`ConnectionRuntimeClassGenerator.java:1982-1985`), and a String
tenant column yields a key no tenant map holds.

What makes it blocking is the reach and the tabling, not the runtime severity. Today these carriers
mint a slot only where a body param survives, so a field-level or argument-level override rejects
them at build time. After this item they classify `ArgumentBound` and fail at request time instead,
which is a verdict change the goal table does not carry, on shapes constructible in the same sakila
fixture catalog the table is measured against (`@node(keyColumns: ["actor_id", "film_id"])` with
`film_id` as the tenant column, an FK-target `@nodeId` argument whose `FilterBinding.Local` tuple
lifts `film_id`). The plan is not overlooking the family by accident: the ledger row's column list is
justified by it ("a composite carrier (the node-key case) binds several columns at once"). It admits
the family, states the wrong reason for the read being correct, and leaves the row out.

Round 1's standard applies unchanged: row nine was added because "the mechanism below could reach it
by accident and must not". The same argument reaches one carrier over, and `tenant-scoping.adoc:35`
already promises that "node ids and federation representations carry their tenant inside the key and
partition per row", which is the promise a single-tenant `ArgumentBound` verdict over an encoded id
contradicts.

The seam paragraph understates this in the same place. It reads the R966 collision as being about
`readOf`'s signature and concludes "one fewer site and no change to its shape". The other half of
the seam is `readOf`'s *answer* for `NodeIdDecodeKeys`: this item writes today's answer into an
exhaustive switch as the deliberate one, with a justifying comment the implementer will put in the
tree, and hands R966 more coordinates carrying the defect than it has today.

What would satisfy it. Any of three, stated in the plan rather than left to the implementer:

1. Keep `NodeIdDecodeKeys`-extraction carriers out of the ledger for the same reason row nine keeps
   the lookup half out, making the discriminator the extraction rather than `isLookupKey`, with a
   goal-table row for the non-`@lookupKey` composite and for the FK-target reference shape.
2. Admit them deliberately, with goal-table rows saying the verdict moves from rejected to
   `ArgumentBound` that fails at request time until R966 lands, and the `readOf` prose corrected to
   say the answer is today's known-wrong one carried forward rather than the tenant component of the
   decoded tuple.
3. Depend on R966 and take its widened `readOf`, which resolves the projection axis and makes the
   admission correct on arrival. This reverses the sequencing the Provenance section argues for, so
   it needs that paragraph revised too.

**Non-blocking, noticed on the way past.**

- `readOf`'s exhaustive switch is worth having whichever way finding 2 goes, and the four
  record-shaped arms throwing is the right call: `InputBeanResolver` and the fetcher generator mint
  those for whole-input extractions that bind no single column, so no column-bound slot can carry
  one.
- Round 1's non-blocking item 1 stands and is now checkable: `SchemaSdlEmissionTest` iterates output
  packages asserting no internal types leak, and `TenantDivinedRoutingExecutionTest` is per-named-
  field, so adding the `multitenant.graphqls` field breaks neither. The `rollekode` predicate on a
  `film`-bound field stays compile-only as the plan says.

Verified along the way, so a later round need not redo it: every symbol, method, test, fixture, doc
sentence and store relation the plan names exists as named; the five mint-site guards match the tree
verbatim; `GraphitronSchemaBuilder.buildBundle` runs before `capturedFrom` in
`GraphQLRewriteGenerator.runPipeline`, so the store really is unreachable at this stage order;
`NodeIdDecodeLedger` is the first-mint-wins coordinate-keyed precedent the plan claims; the emitter
audit holds, including `MultiTablePolymorphicEmitter` reaching the arm through
`TenantDslEmitter.resolveByName` once per coordinate.

#### Author response (2026-09-22, same session taking the author role at the user's direction)

Finding 2 addressed by making the discriminator the **extraction** rather than `@lookupKey`, and by
correcting the `readOf` prose the finding called false.

The finding offered three ways out and the plan takes the first, in the form the finding did not
state: not a flat exclusion of decoded-key carriers, which would have been a regression, but a
*decode clause* that makes those carriers honour the suppression clauses they honour today. The
distinction matters and is worth recording, because the flat reading is the one an implementer would
reach for. A same-table composite `@nodeId` argument with no override mints a slot in the tree today
through `collectFromBodyParam`'s `RowEq` / `RowIn` arms. Excluding the family outright while retiring
`collectFromBodyParam` in the same commit would take that slot away and turn a schema that builds
into one that rejects, which is a breaking change well outside this item. Honouring the suppression
instead leaves the decoded-key family at exactly today's population, in both directions, so no
verdict moves for it at all.

What that buys beyond closing the finding is a reason where there were two. The plan previously
excluded the `@lookupKey` decoded key (row nine) on lookup-ownership grounds and admitted its filter
twin with no reasoning. Both now rest on one fact: the tenant component of a node key exists only
after a decode the predicate path performs, so for those carriers the emission decision and the
classification fact genuinely are coupled, and this item's thesis has a boundary there. `@lookupKey`
is not what makes a decoded key unroutable on this axis; the decode is, and lookup routing is one of
two places it shows up. The Goal section now says this before the table rather than leaving it to the
Implementation.

Two new goal-table rows, tenth and eleventh, so the family's verdicts are where a reader checks them:
the decoded-key shape without `@lookupKey` unchanged at `ArgumentBound` with no override, and
rejected under one. `## Tests` pins all three decoded-key rows and says what each one catches, the
tenth being the preservation pin that fails if the clause is written as a flat exclusion. Its
assertion pins the defective read on purpose, so R966's change to that read surfaces here as a test
to update.

`readOf`'s `NodeIdDecodeKeys` paragraph is rewritten. The claim the finding called false, that the
divined key is the tenant component of the decoded tuple, is gone; the answer is now stated as
carried forward rather than endorsed, naming `TenantAcquisitionFragments.slotRead` and what it
actually renders, and naming R966 as where the fix belongs.

The seam paragraph gains the other half of the collision the finding named. `decodesAKey` is R966's
to retire: widening `readOf`'s return type makes every call site a compile error and the clause's
site is one of them, so no further forcing function is proposed. If R966 lands first the clause is
never written and the eleventh row moves with it. No `depends-on` is added, and the plan now says
why: the two compose in either order, and the boundary is worth stating in the plan that defines the
ledger even when the clause enforcing it is short-lived. That is the finding's third way out
considered and declined, with the reasoning in the text rather than only here.

One precision on the finding itself, left in place above rather than edited, since correcting a
reviewer's record silently is worse than carrying it. It grouped `InputField.ColumnBackedReferenceField`
with `ArgumentRef.ScalarArg.ColumnBackedReferenceArg` as declaring `NodeIdDecodeKeys` at every arity,
citing `ArgumentRef.java:183`. That citation covers the argument carrier only; the input-field carrier
declares a plain `CallSiteExtraction` (`InputField.java:163`). The over-generalisation does not weaken
the finding, it sharpens the answer: a per-carrier rule would have got that carrier wrong in both
directions, and the extraction test gets a locally-resolving `@reference` on it widening and a
`@nodeId` on it preserved.

The `principles-architect` consultation on this revision changed five further things, and they are
worth listing because three of them are stronger than the finding that prompted the revision.

The largest is the containment pin, now in `## Tests`. Today's `slotsFromFilters` is total by accident
of where it reads, walking `bodyParams()`, so a sixth column-bound arm gets a tenant slot for free.
The ledger turns that into a five-arm correspondence with no enforcer, and an arm that emits without
minting reproduces this item's own bug with nothing failing. The plan had ruled out an agreement test
on the grounds that keeping a predecessor as oracle pins its gaps, which conflated two shapes:
`fact-model.adoc` forbids the *total-agreement* test and asks for "agreement asserted where the two
are meant to agree, and each deliberate departure asserted in the direction it was meant to go". A
one-directional containment pin over `bodyParams()`, a live production that stays, is that shape and
needs no predecessor. The five-arm census is verified accurate as written, and is now backed rather
than asserted.

The seam's forcing function was named wrongly and is fixed. Widening `readOf`'s return type makes
every *call site* a compile error, but `decodesAKey` and the five guards are in `FieldBuilder` and are
not call sites, so R966 could clear every compile error and leave the clause standing, narrowing the
ledger forever for the family it had just made correct. The real force was already in the plan
unrecognised: the eleventh row's fixture asserts a rejection on a shape R966 makes route, so R966
meets the lift as a red test it cannot leave green.

The retirement claim is weakened to what is true. "Repointing one reader and not a remodelling" was
overstated by the row's own `CallSiteExtraction` column, which the plan elsewhere concedes no relation
can hold; the retirement is a repoint plus a re-derivation of the extraction-to-read mapping.

Two smaller ones. `decodesAKey` now carries the comment naming the coupling it preserves, because
"honour the arm's guard verbatim" is written at each site as no code at all and so reads as nothing.
And the Provenance sequencing sentence is corrected: this item unblocks *its own* cascade, the 11
roots and `personProfiler`'s children, not R966's 105, so "smaller change first" is the whole of the
ordering argument.

One consultation point is recorded and declined. It read the decode clause as subsuming the lookup
clause for row nine, which does not hold: that fixture carries a plain `@condition` and no override,
so no suppression is active and `decodesAKey` gates nothing there. The lookup clause is row nine's
sole enforcer. The misreading is itself evidence the text invited it, so the ninth bullet in `## Tests`
now says which clause enforces that row and that the two clauses share a reason rather than a
population.

The consultation also leaned toward the finding's third way out, a `depends-on` on R966, on the
grounds that most of the above disappears under it. Declined, and the Other-solutions entry carries
the reasoning: the items compose in either order, a dependency puts 11 sis roots behind 105 for no
sequencing this item needs, and the containment pin, which is the strongest thing to come out of the
consultation, is owed either way.

Author and reviewer were the same session for round 2 and this revision, at the user's direction, so
`Spec -> Ready` needs a session that has committed neither.

### Round 3 (2026-09-22, Spec -> Ready, reviewer session 019isgQ8XRLDYQn2F4kVtpEz)

Verdict: withhold. One blocking finding on question two, and it is not a finding about the plan's
judgment: R966 landed on trunk while this item sat in review, and the contingency this plan wrote
down for that case has fired.

Question one passes, and nothing below disturbs it. The Goal states what changes for a consumer
without reconstruction from the plan: a field whose argument or filter-input field is annotated with
the tenant column, and which also carries an authored `@condition` that replaces the implicit
predicate for that argument, stops failing the build and starts routing to the tenant that argument
names. The outcome is still reachable, and the bug is still live at the site the plan names:
`collectFromFilters` (round 2 read it as `slotsFromFilters`) still derives the slot by walking
`gcf.bodyParams()`, and `FieldBuilder` is untouched by R966, so every mint-site claim in the
Mechanism section holds as written.

**Finding 3 (question two: architecture fit). The plan's Implementation is specified against a
resolver R966 replaced, and the defect its decode clause exists to contain is fixed, so the plan
cannot be handed to an implementer without the rebase it anticipates but cannot perform on itself.**

R966 landed at `4bb378e` ("a bound tenant slot carries its transform, not just its location") and
moved to In Review at `04882fa`. It rewrote the fold this item edits. What changed under the plan:

- `readOf` no longer exists. Its replacement is
  `accessOf(CallSiteExtraction extraction, TenantBinding.SlotRead fallbackRead, int decodeSlot)`,
  returning a `SlotAccess` carrying both axes. The plan's central Implementation paragraph, "The row
  carries the extraction, and `readOf` stays the one resolver", specifies an arm-by-arm exhaustive
  switch over a one-axis function that is gone, and asserts that `readOf` "keeps its name and its
  single-home property".
- `accessOf` resolves a `NodeIdDecodeKeys` extraction to
  `TenantBinding.SlotProjection.DecodedKeySlot(nid.decodeMethod(), decodeSlot)`, and
  `TenantDslEmitter.projected` renders the class's own decode helper for it. The claim the whole
  decode clause rests on, that such a slot's read hands `divinedTenant` the encoded text rather than
  the decoded component, is no longer true about the tree.
- `slotsFromFilters`, `slotsFromLookup`, `slotsFromTableInput` and `directSlots` are all renamed to
  the `collect*` family around a `SlotCollector`. The `## Retired vocabulary` section declares
  symbols R966 already retired, and "What stays" names two descents by names they no longer carry.
- The fold gained a fourth collection arm (`OperationMember.Write.Dml` through
  `collectFromWhereKeys`) and a `declineRoutineWriteDecodes` pass, so "two direct-slot descents where
  it has three today" is no longer the count the change is measured against.

The plan is not silent on this. The seam section says exactly what this item looks like on the other
side: "If R966 lands first instead, this item rebases onto the widened `readOf`, the clause is never
written, and the eleventh row and its test move with it." That reasoning still looks right to me, and
declining the `depends-on` to keep 11 sis roots out from behind 105 still looks like the better call.
What a plan cannot do is apply its own contingency. As the text stands, the mint predicate carries a
decode clause at all five arms, `decodesAKey` is specified as a function with five callers and a
comment to write, goal-table rows ten and eleven carry decoded-key verdicts, and three of the four
fixtures `## Tests` calls the weightiest exist to pin a defect that is fixed.

What makes it blocking rather than a stale citation is that one of those fixtures now contradicts
trunk. Row ten is the preservation pin, asserting `ArgumentBound` "with the slot reading
`TopLevelArg`", and its "assertion pins the defective read deliberately". R966 landed
`sameTableNodeIdFilterDivinesTheDecodedSlot` in the same file, asserting the same shape divines the
decoded slot. An implementer following the plan writes a test that fails against a test already
green beside it, and the plan gives them no signal that the fixture, rather than their work, is what
moved.

There is also a shape in the tree now that the plan would want and could not have known to ask for.
`SlotAccess.Declined` and `SlotCollector.decline` give the fold a first-class "this slot is reached
and cannot route" channel, which `accessOf` already uses for `PruneOnMismatch`. Any exclusion this
item still needs has a home there, in the fold, rather than as a predicate in `FieldBuilder`. That is
a better answer than `decodesAKey` to the question round 2 asked, and it arrived while the plan sat
in review.

What would satisfy it. Rebase the plan body onto the post-R966 fold and state the four decisions the
rebase forces, none of which I should settle for you:

1. What the ledger row hands `accessOf`, and where its `decodeSlot` index comes from. Today that
   index is the tenant column's position in the tuple, derived at the read site by `collectFromRow`.
   A row recording a column list can carry it or let the fold derive it, and the plan should say
   which.
2. Whether the decode clause survives at all. By the plan's own seam paragraph it does not, which
   collapses `decodesAKey`, its five callers and its comment, and makes the mint predicate uniform.
   If something is still owed for these carriers, say what and put it in `SlotAccess.Declined`.
3. What goal-table rows nine, ten and eleven say now. Row nine's lookup exclusion still stands and
   still needs the lookup clause: `collectFromLookup` still skips `DecodedRecord` deliberately. Rows
   ten and eleven are the ones R966 moved, and row eleven is the one this item makes `ArgumentBound`.
4. Which fixtures `## Tests` pins, given that row ten's is now R966's and asserts the opposite. The
   containment pin is unaffected in substance and still worth having; it now reads
   `collectFromFilters`.

Verified along the way, so the next round need not redo it. Everything in the Mechanism section
holds against today's `FieldBuilder`, which R966 did not touch: the five mint-site guards read
verbatim as quoted (`2809`, `2846`, `2863`, `2946-2948`, `2992-2994`); the five-arm census is exact
(`bodyParams` appended at `2825`, `2832`, `2852`, `2868`, and through `implicitBodyParams` at `2954`
and `3011` folded in at `2772` and `2788`, nowhere else); `projectFilters` is the sole construction
site of `GeneratedConditionFilter` (`2881`), and its two `walkInputFieldConditions` call sites are
its only ones, so the completeness argument closes. The mint predicate as stated is implied by each
arm's emit guard at all five arms, so the containment pin holds by construction and is a drift
enforcer rather than an oracle, which is the shape `fact-model.adoc` asks for (quote checked
verbatim at line 39). `CallSiteExtraction` has exactly the ten arms the plan counts, and the four
record-shaped ones are minted only in `ServiceCatalog`, `TypeFetcherGenerator` and
`InputBeanResolver`, never on a column-bound carrier, so the invariant throw is dead by
construction. The extraction is the right discriminator for the reason given: an arity-1 same-table
`@nodeId` produces a `ColumnBackedArg` with a `NodeIdDecodeKeys` extraction
(`FieldBuilder.java:2344-2349`), and `InputField.ColumnBackedReferenceField` declares a plain
`CallSiteExtraction` (`InputField.java:163`) where `ArgumentRef.ScalarArg.ColumnBackedReferenceArg`
narrows (`ArgumentRef.java:183`). The stage-order argument is sound: `GraphitronSchemaBuilder.buildBundle`
runs at `GraphQLRewriteGenerator.java:563` and `capturedFrom` at `590`, so the store really is
unreachable here. `NodeIdDecodeLedger` is the precedent the plan claims, held on `BuildContext` with
a package-private accessor and keyed first-mint-wins by `putIfAbsent`. The rejection message the Goal
quotes is verbatim at `TenantBindingIndex.java:314`, the `tenant-scoping.adoc` promise at line 35,
and `tilgangAdminOnly(Table<?>)` binds no argument as the compile-tier fixture needs
(`InputFieldConditionFixtures.java:192`).

**Non-blocking, noticed on the way past.**

- The "Other solutions" section does not consider carrying the slots as a component on
  `OperationMember.Condition` itself, which is the narrowest "ride an existing surface" option and
  the one a reader may reach for before the ledger. The facts that rule it out are already nearby:
  the member is keyed `(coordinate, table)` with one member per polymorphic participant while the
  ledger is coordinate-keyed, and its constructor refuses an empty filter surface. Worth a line if
  the section is being edited anyway; it changes nothing an implementer builds.
- A load-bearing fact the plan leaves implicit: the ledger read fires only where a `Condition` member
  exists, and that member exists only where filters are non-empty. It is sound, because every
  suppression in this item's scope comes from an authored `@condition` that itself contributes a
  filter, so a member always exists where a row matters. `## Tests` leans on it for the decoded-key
  fixtures without ever stating it as the general reason.
- The plan's sentence about what `TopLevelArg` renders elides a hop: the fold's type is
  `TenantBinding.SlotRead` and the renderer's is `TenantAcquisition.SlotRead`, mapped by
  `RoutineWriteCommands.slotReadOf`. Accurate in substance, and both types exist as named.
- Round 1's non-blocking item 1 stands and is confirmed: `rollekode` is not a `film` column, and the
  fixture stays compile-only as the plan says.

#### Author response (2026-09-23, same session taking the author role at the user's direction)

Finding 3 addressed by rebasing the plan body onto the post-R966 fold. The finding was that the plan
specified a resolver R966 replaced and defended a clause against a defect R966 fixed, and that one of
its pinned fixtures contradicted a test already green on trunk.

The four decisions the rebase forced, settled here rather than left to the implementer:

1. *What the ledger row hands `accessOf`, and where the decode slot comes from.* The row needs no new
   component. It records `predicateColumns(binding, columns)`, which is what today's `BodyParam`
   carries, and `accessOf`'s `decodeSlot` is the matching column's index in that list, which is what
   `collectFromRow` derives from `rowEq.columns()` today. The alignment holds on both bindings:
   `FilterBinding.Local` contributes the lifted own-table tuple R966's write path already reads at
   the decode slot its position names, and `FilterBinding.Remote` contributes the target NodeType's
   key tuple, which is the decode's own output. So the ledger read hands `accessOf` the row's
   extraction, a `TopLevelArg` fallback and that index: the same three arguments, from a ledger row
   instead of a body param. That is what makes this a re-sourcing rather than a second resolution
   path, and it is now stated where the Implementation describes the row.
2. *Whether the decode clause survives.* It does not, per this plan's own seam. `decodesAKey`, its
   five callers and its comment are gone, and the mint predicate at each of the five arms is that
   arm's guard with the suppression clauses dropped and the lookup clause kept. The clause is named
   once in `## Retired vocabulary` as never written, because two rounds of findings below argue about
   it and a reader meeting the term deserves to know it did not reach the tree.
3. *What rows nine, ten and eleven say.* Nine is unchanged and keeps the lookup clause as its sole
   enforcer; `collectFromLookup` still skips `DecodedRecord`, and a per-row family has no single
   decode for the widened read to resolve. Ten is unchanged and is now pure regression, pinned by
   R966's own `sameTableNodeIdFilterDivinesTheDecodedSlot`. Eleven moves from rejected to
   `ArgumentBound`, which is this item delivering one more shape than the pre-R966 draft could. The
   Goal's second paragraph, which set the thesis's boundary at encoded node ids, is replaced: the
   separation now holds for every wire shape, and the only exclusion left is a routing fact rather
   than an emission one.
4. *Which fixtures `## Tests` pins.* Two carry weight instead of three. Row nine's is unchanged. Row
   ten's is dropped, being R966's. Row eleven's is new and is the one assertion proving the two axes
   compose: the location from the suppressed carrier the ledger recorded, the transform from
   `accessOf` reading its extraction, pinned as `DecodedKeySlot` at the right index on an arity-1
   carrier.

Three further changes the rebase surfaced, none of them the finding's.

The uniform mint predicate reaches a carrier the old one did not: a multi-table polymorphic `@nodeId`
argument, whose `PruneOnMismatch` leaf `accessOf` declines. Suppressed, such a field rejects today
with the generic message and after this item with the decline's own text. The verdict does not move,
only the message, and it moves toward the channel R966 built for it. The Goal records it and `##
Tests` pins it over the `PruneOnMismatch` shape `MultiTableFilterLoweringTest` already carries.

The "descent this item does not close" is closed. `collectFromInputFields` now handles
`InputField.ColumnBackedReferenceField`, reading a `Local` tuple at its decode slot and declining a
`Remote` one, so the gap the earlier draft recorded as R966's is gone and the paragraph says so.

The naming-inversion worry is retired rather than carried: the row holds a `CallSiteExtraction` and a
column list, and the fold supplies the `TenantBinding.SlotRead` fallback at the call site, so the
ledger names no tenancy type at all.

What did not move, and was re-verified against today's tree rather than assumed: every mint-site
guard, the five-arm census, the sole `GeneratedConditionFilter` construction site, the containment
pin's premise and its `fact-model.adoc` justification, the stage-order argument, the
`NodeIdDecodeLedger` precedent, the threading and sentinel discipline, and the emitter audit, whose
three claims all still hold on the post-R966 emitter (`dslExpression` still throws on `ArgumentBound`
with the same justification, and `projected` renders the decode helper off the slot rather than off a
predicate, which is what lets row eleven route).

Author and reviewer were the same session for round 3 and this revision, at the user's direction, so
`Spec -> Ready` needs a session that has committed neither.

### Round 4 (2026-09-23, Spec -> Ready, reviewer session 01Fcw42ptsU6nBajgPdqiaJv)

Verdict: withhold. One blocking finding on question two, plus two smaller claims about the code that
are false as written and change what the implementer builds.

Question one passes. When this lands, a consumer whose query field names the tenant column on an
argument or filter-input field, and whose authored `@condition` replaces that argument's implicit
predicate (by `override:` at either level, or by an input field's own `@condition`), gets a schema
that generates and routes on that argument instead of a "no argument or input field maps to tenant
column" build error. The outcome is reachable: the Mechanism section holds against today's tree.

**Finding 4 (question two: architecture fit). The ledger's per-coordinate dedupe is not the dedupe
`SlotCollector` does, and the plan's own containment pin rejects it.**

The Implementation says the ledger "dedupes by slot name per coordinate, keeping the first mint,
which is the dedup `SlotCollector` does across members today". That is not what `SlotCollector`
does. It dedupes *after* `matchesTenantColumn` and only among `Resolved` slots (`seenNames.add` sits
inside the `Resolved` arm), and it does not dedupe declines by slot at all. A ledger that keeps the
first mint per slot name dedupes *before* the tenant match, so on a multi-table polymorphic
coordinate whose participants bind the same slot name to different column tuples, the row the fold
reads is whichever participant ran first. That is not an override-only effect: the ledger read
replaces `collectFromFilters` for every condition member.

Measured rather than read. On this tree, with `film_id` as the tenant column, a bare
`occ(id: ID! @nodeId): [Occ!]!` over `union Occ = Inventory | FilmActor` (Inventory keyed on
`inventory_id`, FilmActor on `(actor_id, film_id)`, both tenant-scoped) rejects `Query.occ` with the
`PruneOnMismatch` decline text in *both* member orders, because today each participant's body param
is matched on its own. Under the specified ledger, the `Inventory | FilmActor` order keeps
Inventory's `[inventory_id]` row for slot `id`, drops FilmActor's, and the field falls to the generic
"nothing maps" message. The verdict holds; the message regresses on a field with no override in it,
against "Nothing else about the fold moves" and against the goal's own polymorphic paragraph.

The plan does contain the check that catches this: `ColumnBindingLedgerContainmentTest` asks that
every surviving `BodyParam` naming a column have a ledger row under the same slot name *carrying that
column*. The `Customer | Staff` bare-`@nodeId` fixture `MultiTableFilterLoweringTest` already builds
emits a `staff_id` body param for slot `id` on the Staff participant, and a first-mint-wins row for
`id` carries `customer_id`. The pin goes red on its first run, so an implementer following the plan
has to redesign the dedupe to get green.

What would satisfy it: say how the ledger keeps a polymorphic coordinate's per-participant tuples, so
that the fold's match-then-dedupe in `SlotCollector` stays the only dedupe, and so that the
containment pin and the dedupe agree. Keeping every participant's mint, deduping on the whole row, or
keying rows by participant as well as coordinate would each do it. Which one is the author's call,
and it touches the "keyed and grained the way the relation would be" claim, which is why I am not
settling it here.

**Finding 5 (question two, smaller). The multi-table polymorphic test names a fixture that never
reaches the tenant axis.** `## Tests` pins the decline "over the `PruneOnMismatch` fixture
`MultiTableFilterLoweringTest` already carries". That fixture is `Customer | Staff` keyed on their
own PKs, and `TenantBindingClassificationTest` builds with `film_id` as the tenant column. Neither
`customer` nor `staff` carries `film_id`, so the field classifies untenanted and there is no
rejection to assert. The case needs participants that are all tenant-scoped with the tenant column
in their node keys (`FilmActor | FilmCategory`, say), and once finding 4 is settled it should also
use a member order where the first participant's key omits the tenant column, since that is the
order that tells the two dedupes apart.

**Finding 6 (question two, smaller). The exhaustive-leaf-switch paragraph misreads `accessOf`'s first
switch.** It says the location arms `NestedInputField` and `ContextArg` "reach the leaf switch only
through a doubly-wrapped extraction nothing mints, so they throw the same invariant". That holds for
`NestedInputField`, whose arm unwraps to `nested.leaf()`. It does not hold for `ContextArg`: its arm
sets `read = SlotRead.ContextArg.INSTANCE` and `leaf = extraction`, so a bare `ContextArg` reaches the
leaf switch as itself and today resolves `Raw` through the `default`. A `ContextArg -> throw` in the
leaf switch would make the first switch's `ContextArg` arm, and the `SlotRead.ContextArg` renderings
in `TenantDslEmitter` and `RoutineWriteCommands.slotReadOf`, unreachable except as a crash. Nothing
mints a `ContextArg` on a column-bound carrier today (its only construction is `MethodRef`, for
method parameters), so no verdict moves either way, but the implementer has to choose: `ContextArg ->
Raw`, or remove the first-switch arm and its renderings. Say which.

Verified, so the next round need not redo it. The five mint-site guards and their clauses read as
quoted (`FieldBuilder.java` 2804-2809, 2844-2846, 2861-2863, 2946-2948, 2992-2994); `bodyParams` is
appended at 2825, 2832, 2852, 2868, and through `implicitBodyParams` at 2954 and 3011 folded in at
2772 and 2788; `GeneratedConditionFilter` is constructed only at 2881; `projectFilters` has one caller
(`projectForFilter`, 2605). A tenant-column scalar argument under its own `@condition(override: true)`
classifies `ColumnBackedArg` rather than `ConditionOwnedArg`, which only the no-route `@nodeId` case
mints (2223), so goal-table row four reaches the arm the plan names. Every suppression in scope comes
from an authored `@condition` that contributes a filter (the field-level one is appended after
`projectFilters` returns), so a condition member exists wherever a suppressed row matters.
`accessOf`, `SlotAccess.Declined`, `SlotCollector`, `declineRoutineWriteDecodes`, the four `collect*`
descents, `TenantBinding.SlotProjection.DecodedKeySlot`, `TenantDslEmitter.projected` /
`dslExpression` / `slotReads`, `TenantAcquisitionFragments.slotRead`, `NodeIdDecodeLedger` on
`BuildContext` with a package-private accessor, `OperationMemberRelation.EMPTY`, the rejection text
(`TenantBindingIndex.java:333`), `sameTableNodeIdFilterDivinesTheDecodedSlot`,
`ColumnMatchShadowTest`, `tilgangAdminOnly(Table<?>)`, `multitenant.graphqls`, the
`tenant-scoping.adoc` promise (line 35), the `fact-model.adoc` quote (line 39) and the
`pipeline-overview.adoc` heading (line 28) all exist as named. `CallSiteExtraction`'s leaves are the
eleven records the exhaustive switch needs to cover, with `ThrowOnMismatch` and `PruneOnMismatch`
under the sealed `NodeIdDecodeKeys`. The goal-table tallies (five moving, five unchanged, two still
rejecting) match the rows.

**Non-blocking.**

- `implicitBodyParam` rewrites a `Direct` leaf on an `ID`-typed input field to `JooqConvert` before
  wrapping it. The input-field row's extraction should be the wrapped *post-substitution* leaf if it
  is meant to be "what the body param would have carried". Both leaves resolve `Raw`, so no verdict
  depends on it.
- Row ten covers "a `@nodeId` argument or filter-input field", but
  `sameTableNodeIdFilterDivinesTheDecodedSlot` pins only the argument half.

#### Author response (2026-09-23, reviewer session 01Fcw42ptsU6nBajgPdqiaJv taking the author role at the user's direction)

Finding 4 addressed by putting the filtered table in the ledger's key and reading it per condition
member. Of the three repairs the finding listed, this is the one that makes the plan's grain claim
true rather than merely consistent. A slot's columns are a fact of the coordinate, the table the
filters resolve against, and the slot; the old "one row per coordinate and slot" grain was false for
a multi-table polymorphic coordinate, and the first-mint dedupe was what hid that. Keying by
`(FieldCoordinates, TableRef)` and reading at `(coordinate, Condition.table())` restores today's
per-member read exactly, so `SlotCollector`'s match-then-dedupe is again the only cross-participant
dedupe and "Nothing else about the fold moves" holds as written. The alignment it relies on
(`projectFilters`' `rt` is the table the condition member names) is stated with the paths that
supply it, and the containment pin now reads by member table, so it enforces that alignment on every
fixture instead of contradicting the key. The rejected alternatives: keeping every mint in one
coordinate-wide row would work but leaves the grain unsayable, and deduping on the whole row keeps
the right tuples while still pretending the table is not part of the fact. `directBinding` takes the
coordinate as a parameter; all four of its callers already hold one.

Finding 5 addressed with a fixture of this item's own, `Inventory | FilmActor` in the order that
puts the key without `film_id` first, carrying two cases: the no-override regression, which is the
case a coordinate-wide first-mint dedupe would have broken, and the override case the finding was
about. The field-level condition is `TestConditionStub.lifterFieldCondition`, which binds no
argument, so the dispatched-`@nodeId` divergence refusal cannot fire ahead of the tenant fold.

Finding 6 addressed: `ContextArg` keeps `Raw` as an explicit leaf arm, for the reason the finding
gave (its first-switch arm passes the bare extraction on as the leaf), and only `NestedInputField`
throws the doubly-wrapped invariant.

Both non-blocking notes taken. The input-field mint reuses `implicitBodyParam`'s wrapping, so the
row carries the post-rewrite `JooqConvert` leaf a body param would have. The filter-input half of
goal-table row ten gets a regression case, since nothing in the tree pins it and this item
re-sources exactly that read.

The reviewer of round 4 wrote this revision, so `Spec -> Ready` needs a session that has committed
neither.

### Round 5 (2026-09-23, Spec -> Ready, reviewer session 01VUCQBqSCcDLos3JxKYJXpS)

Verdict: withhold. One blocking finding, on question one with a question-two root. Round 4's three
findings are resolved: the `(FieldCoordinates, TableRef)` key reads back at `Condition.table()` on
every classification path I followed (`FieldBuilder.java:1208` and `1224` for the participant loop,
and `OperationMemberRelation.payloadsFor` returns `sgf.returnType().table()` for the single-table
arms, including the routine chain, whose `routineChainComponents` passes
`walk.tb().returnType().table()` as `rt`). The new fixture is tenant-scoped on both participants, and
`ContextArg -> Raw` matches `accessOf`'s first switch.

**Finding 7 (question one: the goal names a shape the plan does not reach; the root is in question
two's mechanism). An input field that binds the tenant column and carries its own
`@condition(override: true)` keeps rejecting, because the classifier drops its column before
`projectFilters` ever sees it.**

The goal promises that a field "whose argument, or whose filter input's field, is bound to the tenant
column keeps its tenant binding whatever an authored `@condition` does to the predicate". The plan
reaches that for arguments, but not for input fields. `BuildContext.classifyInputFieldInternal`
(`BuildContext.java:3052-3060`) resolves the column and then, when the field's own condition is
`override: true`, mints `InputField.ConditionOwnedField` instead of `ColumnBackedField`. The comment
there says "the column is deliberately not recorded", and the record carries no `columns()`
(`InputField.java:234-242`). The ledger mints only on the `ColumnBackedField` and
`ColumnBackedReferenceField` arms of `walkInputFieldConditions`. Its `ConditionOwnedField` arm
(`FieldBuilder.java:3023-3028`) holds nothing a row could be built from. The argument side does not
have this asymmetry: a scalar argument under its own override stays `ColumnBackedArg` (only the
no-route `@nodeId` case mints `ConditionOwnedArg`, `FieldBuilder.java:2223`). That is why goal-table
row four moves and its input-field twin does not.

Measured on this tree with `film_id` as the tenant column:
`input FilmInput { filmId: ID @field(name: "film_id") @condition(condition: {...inputColumnCondition}, override: true) }`
on `films(filter: FilmInput): [Film!]!` rejects with "no argument or input field maps to tenant
column 'film_id'". That is the same rejection rows six and seven carry today. Under the plan as
written it still rejects, and the goal table has no row saying so. Three statements in the spec are
then untrue for this shape:

- the goal's opening sentence;
- the Mechanism's "The column binding survives every one of them". It lists three suppression sites
  in `projectFilters`, but a fourth suppression happens at classification, where the binding does not
  survive;
- the Implementation's "the ledger's domain is the predicate path's domain plus the suppressed column
  bindings". This is a suppressed column binding the ledger's domain excludes.

The Documentation sentence planned for `condition-cascade.adoc` would also be false for it. That
sentence says `override:` "never" governs the tenant routing a column-bound argument divines, and it
sits in the section a reader of the input-field override semantics consults.

This is not scope I would like to see added. The goal's own wording already covers the shape. It
blocks because an implementer following the plan ships a documented guarantee that one natural shape
breaks, and nothing in the plan's tests would show it: the containment pin reads `BodyParam`s, and
this shape emits none.

What would satisfy it: say which way this shape goes, and make the goal table, the Mechanism and the
Documentation sentence agree with that.

- If it should move, the classification has to keep the column. Either `ConditionOwnedField` carries
  its resolved column (it currently shares a carrier with the column-miss outcome), or a
  column-resolved override field stays `ColumnBackedField`, as the argument side does. Then name the
  mint arm and the goal-table row. Either route touches a carrier `LeafRatchetTest` and
  `GraphitronSchemaBuilderTest` (`CONDITION_OWNED_FIELD`,
  `INPUT_IMPLICIT_CONDITION_EXPLICIT_OVERRIDE_SUPPRESSES_OWN`) pin, so the plan should say which.
- If it should stay out, add a goal-table row (rejected, rejected) with the reason. Narrow the goal
  sentence and the Documentation sentence to what does move. Then this item and the gap it leaves
  can both be named in one sentence.

Which one is the author's call.

**Non-blocking.**

- The emitter audit's service claim holds, and on firmer ground than the one it gives. A suppression
  never changes a field's leaf type, and every shape this item moves has an unsuppressed twin that
  classifies `ArgumentBound` today (rows one and two against rows three to seven). So no leaf type
  newly reaches `ArgumentBound`, whichever emit site one audits.
  `ChildField.ServiceTableField` is minted with `List.of()` filters (`FieldBuilder.java:7147-7148`),
  so no service coordinate reaches `projectFilters`, as the spec says.
