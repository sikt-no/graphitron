---
id: R965
title: "A column-bound filter slot divines the tenant whatever @condition does to its predicate"
status: In Review
bucket: bug
priority: 2
theme: classification-model
depends-on: []
created: 2026-09-22
last-updated: 2026-09-24
---

# A column-bound filter slot divines the tenant whatever @condition does to its predicate

## Goal

A query field whose argument, or whose filter input's field, is bound to the tenant column routes on
that value whatever an authored `@condition` does to the predicate for it. Today it does not: when the
`@condition` takes the predicate over (an `override: true` on the field or on the argument, or any
`@condition` on the input field itself), the build rejects the field with "no argument or input field
maps to tenant column 'X'", although the argument names exactly that column. *Tenant binding* is how
a generated fetcher picks the tenant database a query runs on; a *tenant-scoped* table is one carrying
the column the Mojo's `<tenantColumn>` names, and a field reaching one builds only when something in
scope *divines* that column, meaning supplies one value for it. After this item, `override:` and an
input field's `@condition` decide which SQL predicate is emitted and nothing else; which tenant the
statement routes to is decided by the column binding alone.

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

In the sis schema this shape accounts for 11 of the 121 root fields a tenant-configured build rejects
(`brukere`, `personProfiler`, `studenter`, `emner`, `emnerV2`, `esiKandidater`, `evuKurs`,
`studieoppbygninger`, `utvekslingsavtaler`, `personProfilerGittFeideBrukere`,
`studenterGittFeideBrukere`), and more than that in effect: a child of a tenant-scoped type inherits its
tenant only when every path into the type binds one, so `Query.personProfiler` failing keeps every
`PersonProfil` child red too.

The shapes this item moves are wider than that pair, and the table below is the discriminating set it
delivers. Every row is a schema built against the sakila fixture catalog with `film_id` as the tenant
column, its verdict read off `GraphitronSchema.tenantBindingOf` (measured 2026-09-22, re-measured
2026-09-23):

| SDL shape, on a field returning tenant-scoped `film` | today | when this lands |
|------------------------------------------------------|-------|-----------------|
| `films(filmId: Int @field(name: "film_id"))` | `ArgumentBound` | unchanged |
| the same, with a *sibling* argument carrying `@condition(override: true)` | `ArgumentBound` | unchanged |
| the same, with a *field-level* `@condition(override: true)` | rejected | `ArgumentBound` |
| the tenant argument itself carrying `@condition(override: true)` | rejected | `ArgumentBound` |
| the tenant argument carrying its own `@condition`, no `override` anywhere | `ArgumentBound` | unchanged |
| a filter input whose field binds the column *by name*, under a field-level `@condition(override: true)` | rejected | `ArgumentBound` |
| a filter input whose field binds the column *by name* and carries *its own* `@condition`, no `override` anywhere | rejected | `ArgumentBound` |
| a filter input whose field binds the column *by name* and carries *its own* `@condition(override: true)` | rejected | `ArgumentBound` |
| the tenant argument as a `@lookupKey`, under a field-level `@condition(override: true)` | `ArgumentBound` | unchanged |
| a composite `@nodeId @lookupKey` argument whose decoded key embeds the tenant column | rejected | rejected |
| a `@nodeId` argument or filter-input field whose decoded key embeds the tenant column | `ArgumentBound` | unchanged |
| the same, under a field-level `@condition(override: true)` | rejected | `ArgumentBound` |
| a field where nothing binds the tenant column | rejected | rejected |

Four things the table says that its rows do not say one at a time:

- **The trigger is an override over the tenant argument's own predicate, wherever it sits.** The
  reported field classified only because its override sat on a sibling (row two); the same override
  on the tenant argument itself rejects (row four).
- **`override:` is not needed.** An input field carrying an ordinary `@condition` replaces its implicit
  predicate rather than stacking with it (the documented filter-input semantics), so row seven loses
  the binding with no override anywhere in the schema.
- **Encoded ids behave like any other wire shape.** A `@nodeId` argument whose decoded key embeds the
  tenant column already routes on the decoded tenant (row eleven), so under an override it moves with
  the rest (row twelve).
- **Two rows stay rejected, and neither is about emission.** Row ten is a routing fact: decoded
  `@lookupKey` ids each carry their own tenant, so a batch of them has no single tenant to route one
  statement on, and they keep partitioning per id as they do today. Row thirteen is the obligation
  this axis exists for: routing tenant data through the default connection when nothing named the
  tenant is the leak it prevents.

One shape changes its message but not its verdict. A multi-table polymorphic `@nodeId` argument (a
field returning a union of tenant-scoped tables, filtered by one `id: ID! @nodeId` argument) decodes
the same wire id differently per member table, so there is no single decode to route on. Under a
field-level override it rejects today with the generic "nothing maps to the tenant column"; after this
item it rejects with the specific text it already gets without an override, which names the shape. That
holds whatever order the union lists its members in.

## Mechanism

`TenantBindingIndex.Fold.directBinding` collects a coordinate's tenant slots per operation member.
For a condition member (the WHERE contribution against one table) it calls `collectFromFilters`, which
walks `GeneratedConditionFilter.bodyParams()`, the implicit predicates the generator emits, and mints a
`TenantBinding.BoundSlot` for every `BodyParam` whose column is the tenant column (through
`collectFromBodyParam` and `collectFromRow`). A slot therefore exists only where a predicate was
emitted.

`FieldBuilder.projectFilters` is the single construction site of `GeneratedConditionFilter`, and it
declines to emit an implicit predicate in three places:

- `ScalarArg.suppressedByFieldOverride()` on the `ColumnBackedArg` and `ColumnBackedReferenceArg`
  arms: a field-level `@condition(override: true)` suppresses every classified argument's predicate.
- `argCondition().override()` on the same arms: an argument-level override suppresses that argument's
  predicate.
- `walkInputFieldConditions`, which emits an input field's implicit predicate only under
  `!enclosingOverride && condition().isEmpty() && !lookupBoundNames.contains(name)`. The middle clause
  is the replace-rather-than-stack rule for input fields, so this arm drops the predicate with no
  override present.

In all three the column binding survives on the carrier: `ArgumentRef.ScalarArg.ColumnBackedArg` and
`ColumnBackedReferenceArg` still carry `columns()`, and `InputField.ColumnBackedField` and
`ColumnBackedReferenceField` still carry `columns()` beside `condition()`. The fold reads the wrong
axis.

A fourth suppression happens earlier, at classification, and there the binding does not survive.
`BuildContext.classifyInputFieldInternal` resolves a plain input field's column and then, when the
field's own condition is `override: true`, mints `InputField.ConditionOwnedField`, which records no
column (both the column-resolved and the column-missing outcome mint it). This is row eight. The
argument side has no such fork: a scalar argument under its own override stays `ColumnBackedArg`, and
so do the `@nodeId` input leaves under their own override (`inputFieldFromNodeIdResolved`); only their
no-route arms become condition-owned.

The fold's other direct-binding descents do not have the problem, because they read classification
carriers rather than emitted predicates: `collectFromLookup` reads `LookupMapping.ColumnMapping`,
`collectFromTableInput` reads the `ArgumentRef.InputTypeArg.TableInputArg` envelope, and
`collectFromWhereKeys` reads `Dml.whereKeyColumns()`. Row nine is that fact measured: a `@lookupKey`
tenant argument keeps divining under a field-level override because its slot never came from a
predicate. This item moves the condition path onto the same footing.

Two facts about the fold the plan leans on. A bound slot carries its transform beside its location:
`accessOf` resolves a node-id extraction to `TenantBinding.SlotProjection.DecodedKeySlot`, and
`TenantDslEmitter.projected` renders the decode helper for it off the slot, not off a predicate, so a
decoded tenant does not need an emitted predicate to be read (row twelve). And `collectFromLookup`
skips `LookupMapping.ColumnMapping.LookupArg.DecodedRecord` on purpose, since decoded lookup ids carry
per-id tenants; that skip is row ten, and this item leaves it alone.

## Implementation

The fold gets the column-binding axis as a fact of its own, minted where the walk holds it and read
where it reads the other classification carriers.

**Where the fact lives, and why there.** Every filter slot's column binding is already captured in the
store (`graphitron_argument_column_match` and `graphitron_input_field_column_match`, with
`graphitron_field_column_scope` naming the table the names resolve against), and that is where the
tenant fold belongs once it re-sources. It cannot read those rows here: `GraphQLRewriteGenerator.runPipeline`
classifies before it captures, so this run's rows do not exist yet when `TenantBindingIndex` runs, and
moving the tenant verdict downstream of capture re-platforms the whole axis (its verdicts feed
emitters, not only the error stream). So what this item adds is a transitional read path for a
walk-side reader: a ledger the walk fills and the fold reads, retiring with that reader when the fold
re-sources onto the store. `pipeline-overview.adoc` forbids a walk-side registry for a *new* fact; this
one carries an existing fact to an existing reader, is grained the way the relation is (one row per
coordinate, filtered table and slot), and has `NodeIdDecodeLedger` as its shape precedent. Two notes
bind the re-sourcing. The row's `CallSiteExtraction` is an emit carrier no relation holds, so the
retirement is a repoint plus a re-derivation of the extraction-to-read mapping from stored facts. And
the re-sourced fold must read the column-match relations, not the ranked winner of
`graphitron_input_field_filter_role`: that relation ranks `CONDITION_OWNED` above `NAME_MATCHED`, which
restates this item's coupling in SQL and would bring back the goal table's eighth row.

**`ColumnBindingLedger`.** A new final class in `no.sikt.graphitron.rewrite`. Rows are keyed by
`(FieldCoordinates, TableRef)`, and each row is a `List<ColumnBoundSlot>`, where
`ColumnBoundSlot(String slotName, List<ColumnRef> columns, CallSiteExtraction extraction)` answers
"which columns does this argument or input field bind, and how is its value read at the call site". The
columns are the ones the implicit predicate binds (`predicateColumns(binding, columns)` on the reference
arms, so a `@reference`-reached tenant column divines exactly as today's `RemoteColumnPredicate` unwrap
lets it), and the extraction is the one the predicate carries. The columns are a list because a
node-key carrier binds a tuple. The ledger names no tenancy type: the fold supplies the tenancy reading
when it reads a row.

The table is in the key because on a multi-table polymorphic coordinate both walks run once per
participant table, and one slot can bind a different tuple per participant: a bare
`occ(id: ID! @nodeId)` over `union Occ = Inventory | FilmActor` binds `[inventory_id]` on one and
`(actor_id, film_id)` on the other, since each decodes the id as its own node type. The key is the
`(coordinate, table)` a condition member carries (`OperationMember.Condition.table()`), so the fold
reads one row per member and `SlotCollector`'s match-then-dedupe stays the only dedupe across
participants.

**One question per slot, asked ahead of the emission decision.** Two private static functions in
`FieldBuilder`, each an exhaustive switch with no `default`, answer "which columns does this carrier
bind":

- `columnBindingOf(ArgumentRef)`, called at the top of `projectFilters`' loop body.
  - `ColumnBackedArg` answers its `columns()` with its `extraction()`, unless `isLookupKey()`.
  - Both `ColumnBackedReferenceArg` arms answer `predicateColumns(binding(), columns())` with their
    `extraction()`. They carry no lookup flag: an FK target is a filter, not a lookup.
  - Every other arm answers nothing: `ConditionOwnedArg` (a no-route `@nodeId` that resolved no
    column), `UnboundArg`, `UnclassifiedArg`, `OrderByArg`, `PaginationArgRef`, and the two
    `InputTypeArg` arms, whose fields the walk answers for.
- `columnBindingOf(InputField, outerArgName, leafPath)`, called at the top of
  `walkInputFieldConditions`' loop body when `!lookupBoundNames.contains(f.name())`.
  - `ColumnBackedField` answers its `columns()`.
  - `ColumnBackedReferenceField` answers `predicateColumns(binding(), columns())`.
  - `ConditionOwnedField` answers its `resolvedColumn()` when present (below).
  - `NestingField` and `UnboundField` answer nothing; a nesting field's own fields answer on the
    recursive visit.

  The extraction is the wrapped `NestedInputField(outerArgName, leafPath, leaf)` a body param would
  carry, since `accessOf` reads the location from the wrapper and the transform from the leaf. The leaf
  is the body param's too: `implicitBodyParam` rewrites a `Direct` leaf on an `ID`-typed field to
  `JooqConvert`, and that rewrite moves into a helper both it and this function call, so the
  substitution is written once. A `ConditionOwnedField` builds no body param and takes the same
  rewrite.

The emission switches that follow are untouched. The mint predicate is therefore "the carrier binds a
column, and the slot is not lookup-bound". Every other clause of the emission guards drops out without
being named, and every one of them is a suppression: `!autoSuppressed` on the argument arms, and
`!enclosingOverride && condition().isEmpty()` on the input-field arms. A suppression drops a predicate
in favour of authored SQL; that is emission, and answering ahead of it is the whole of this item.

The lookup clauses stay because they say something else: the slot is not on the predicate path at all.
`@lookupKey` routes it to `LookupMappingResolver` and the VALUES+JOIN input-rows helper, and
`collectFromLookup` owns its tenant slot. For a scalar `@lookupKey` argument or a `MapGroup` input field
the clause is inert, since `collectFromLookup` mints the same slot with the same access. For a decoded
key it is load-bearing: `LookupMappingResolver` routes a composite `@lookupKey` `ColumnBackedArg`, and an
input's `InputColumnBindingGroup.DecodedRecordGroup`, to `LookupMapping.ColumnMapping.LookupArg.DecodedRecord`,
which `collectFromLookup` skips on purpose because each decoded id carries its own tenant. A ledger slot
there would hand the fold one tenant for the whole batch, and a cross-tenant `ids:` batch would read
whichever database the first key pointed at. That is the goal table's tenth row.

**Recorded once per filter surface a leaf carries.** `projectForFilter` owns the row: it creates the
accumulator, passes it into `projectFilters`, which passes it into `walkInputFieldConditions` beside
`implicitBodyParams`, and calls `ColumnBindingLedger.record(coordinate, rt, slots, filters)` immediately
before returning `TableFieldComponents.Ok`, with the final filter list. So a field that
`projectForFilter` rejects leaves no row. `record` makes two checks, each throwing
`IllegalStateException` naming the coordinate:

- *A non-empty row comes with non-empty filters.* This holds by construction, since every suppression
  is an authored `@condition` whose own filter lands in the same list (the argument's, the enclosing
  input's, the input field's own, or the field-level one `projectForFilter` appends). It is stated as a
  check because it is what guarantees a condition member exists wherever a row matters: a leaf carrying
  non-empty filters mints one (`OperationMemberRelation.memberKindsOf`), and a row with no member would
  be the bug one level up, a binding nobody reads.
- *A repeat record at one key carries an equal row.* With the table in the key, a repeat is the same
  classification of the same slots, so a different row is a walk defect, and keeping either silently
  would let the verdict read a slot the leaf does not carry. (`NodeIdDecodeLedger` keeps its first mint
  instead because its key has no table, so its repeats are genuinely different participants.)

**`ConditionOwnedField` keeps the column it resolved.** The carrier gains
`Optional<ColumnRef> resolvedColumn`: present at `classifyInputFieldInternal`'s column-resolved arm,
empty at its column-miss arm, and empty at `inputFieldFromNodeIdResolved`'s `AuthorOwnedPredicate` arm,
where no route resolved. It is `Optional<ColumnRef>` rather than a column list with an extraction
because the only path resolving a column here is the plain single-column lookup, whose extraction is
`Direct` by construction. The carrier's identity does not change and no reader moves: every reader
(the rail membership keeping it out of `LookupKeyField` and `SetField`, `MutationInputResolver`'s
INSERT refusal, the `UpdateRowsWalker` / `DeleteRowsWalker` refusals, `EnumMappingResolver`'s skip, the
filter walk firing the method, and the validator, context-argument and fetcher-generator arms)
branches on carrier identity or on the condition. The component's javadoc names it the binding axis,
beside the condition axis, not a carrier role; `graphitron_input_field_carrier_role` rightly keeps
saying the field drives no rail. The component adds no variant, so `LeafRatchetTest` does not move, and
it retires with the ledger. It widens a transitional record, and what makes that acceptable is that the
classifier already computes the column and throws it away, while the carrier's siblings
(`ColumnBackedField`, `ColumnBackedArg`, and the `@nodeId` input leaves under their own override)
already carry it.

**Held and threaded like the decode ledger.** A `private final ColumnBindingLedger` on `BuildContext`
beside `decodeLedger`, with a package-private accessor. `GraphitronSchemaBuilder` reads it at schema
assembly, runs the containment check below, and passes it to `TenantBindingIndex.compute` beside
`operationMembers`. The fold refuses a `ColumnBindingLedger.EMPTY` sentinel by reference identity, in
the same constructor that refuses `OperationMemberRelation.EMPTY`, so a hand-built schema that never
ran the walk is refused rather than silently classifying every condition path unbound.

**The containment check: every emitted predicate has its slot.** The compiler owns half of the ledger's
completeness: a new `ArgumentRef` or `InputField` arm is a compile error in `columnBindingOf`, and since
the question is asked ahead of the emission guards, no edit to a guard can remove a row, including a
suppression carried by carrier identity (row eight). The other half is a correspondence the compiler
cannot see: every body param the emission arms construct must come from a carrier `columnBindingOf`
answers for, or a new predicate arm reproduces this item's bug with nothing failing. Today that holds
at every construction site; the check keeps it holding.

`ColumnBindingLedger.requireCovers(OperationMemberRelation)`, called in `GraphitronSchemaBuilder`
directly after `OperationMemberRelation.compute` and not gated on tenancy. For every
`OperationMember.Condition`, every `BodyParam` in its `GeneratedConditionFilter`s (unwrapping
`RemoteColumnPredicate`) must have a slot in the row at `(coordinate, member.table())` with the same
name, the same columns and an equal extraction; otherwise it throws `IllegalStateException` saying it is
a generator invariant failure and naming the coordinate, table and slot. The extraction is compared
because it is half of what the fold reads: a slot matching on name and columns with a different
extraction would move the verdict's read or transform silently. Every `CallSiteExtraction` arm is a
record, so the comparison is value equality.

It sits where both sides first exist, after the member relation is minted and before the tenant fold
reads either, and it follows `OperationMembers.validateAgainstDeclaredShape`, which fences the same
relation on every build the same way. Its domain is exactly the fold's read domain: the condition
members of the flat classified index. It also enforces the one alignment the key relies on, that the
table `projectForFilter` received is the table its condition member names. Reading by member is what
makes the check and the key agree: `MultiTableFilterLoweringTest`'s `Customer | Staff` bare-`@nodeId`
fixture emits a `staff_id` body param for slot `id` on one participant and a `customer_id` one on the
other, and each finds its own row.

It is ungated because gating would take its reach away: most schemas the test tier builds configure no
tenant column, so a check inside the fold would run on a handful of fixtures, while this one runs on
every schema any test in any tier builds, and a new predicate arm's own tests trip it. The price is
stated: a missed mint on an arm no tenant consumer uses fails the build of every consumer that reaches
the arm, though the defect would have been harmless there. An internal invariant failing loudly is the
posture the member relation already takes.

It is not the oracle the shadow tests (`ColumnMatchShadowTest` and siblings) are, and the difference is
the one `fact-model.adoc` draws under "Name the row, not the question". What is forbidden is the
*total-agreement* test, asserting a replacement equals its predecessor, which would make the
predecessor normative and pin its gaps. What is asked for is "agreement asserted where the two are meant
to agree, and each deliberate departure asserted in the direction it was meant to go". Containment in
one direction is that: `bodyParams()` is a live production that stays, not a predecessor kept alive,
and the check asserts nothing about the ledger-only direction, which is where both deliberate
departures live (the suppressed bindings this item adds, and the lookup slots it keeps out). The check
reads body params to verify coverage; nothing reads them to classify.

**The fold reads it.** `Fold.directBinding` gains the coordinate as a parameter (its four callers
already hold the one they pass members for) and replaces
`case OperationMember.Condition c -> collectFromFilters(c.filters(), collector)` with a read of the row
at `(coordinate, c.table())`. For each slot and each index `i` whose column `matchesTenantColumn`
accepts, it calls `collector.add(slotName, column, accessOf(extraction, TenantBinding.SlotRead.TopLevelArg.INSTANCE, i))`,
which is what `collectFromBodyParam` and `collectFromRow` do between them today: the same three
arguments to `accessOf`, from a ledger row instead of a body param. The index is the decode slot
`accessOf` wants, because the row records `predicateColumns(binding, columns)`, which is the tuple the
decode produces: a `FilterBinding.Local` carrier's lifted own-table tuple, or a `FilterBinding.Remote`
carrier's target key. A nested input field needs nothing extra, since `accessOf` matches the
`NestedInputField` wrapper first and ignores the fallback. `collectFromFilters`, `collectFromBodyParam`
and `collectFromRow` retire, and the fold's `RemoteColumnPredicate` unwrap with them.

Nothing else about the fold moves: `accessOf`, `SlotAccess`, `SlotCollector` and `DirectBinding` are
read as they are, so a slot whose access comes back `SlotAccess.Declined` (the multi-table
`PruneOnMismatch` leaf) declines the coordinate through the channel every other carrier uses.
`collectFromLookup`, `collectFromTableInput` and `collectFromWhereKeys` stay as they are, and
`collectFromLookup` stays the sole owner of lookup-bound slots, which the lookup clauses of the mint
predicate enforce. Giving a decoded lookup key a tenant verdict of its own is the per-row family's
question and belongs with the node-dispatch facts that already partition per decoded tenant.

**The emitter audit the producer relaxation owes.** More coordinates classify `ArgumentBound`, so every
emit site assuming the old population was checked. No leaf type newly reaches `ArgumentBound`, since a
suppression never changes a field's leaf type and every shape this item moves has an unsuppressed twin
that classifies `ArgumentBound` today. `TenantDslEmitter.dslExpression` throws on `ArgumentBound` at its
expression-only site on the ground that `@service` operations contribute no argument slots, which
survives because the mint stays inside `projectForFilter`, which no service coordinate reaches.
`TenantDslEmitter.slotReads` and `TenantAcquisitionFragments.slotRead` render from the slot's two axes
alone and assume no matching body param; `TenantDslEmitter.projected` renders the decode helper for a
`DecodedKeySlot` off the slot, which is what lets row twelve route. `MultiTablePolymorphicEmitter` reads
`ArgumentBound` per participant, and the per-member read produces the slot list it gets today.

**The disclosed gap.** A wire-valued argument whose own predicate an author overrode now divines the
tenant. That is the intended reading, since routing chooses a database and an argument bound to the
tenant column names a tenant whatever predicate runs inside it. The cost is that a condition method
reinterpreting the value (a prefix match, a deliberately cross-tenant `IN`) routes on a value the
author did not mean as a tenant id, and nothing at build time says so; the generated `divinedTenant`
agreement guard checks at request time. There is no SDL way to say "names the column, does not
divine", and inventing one would widen this item into a directive surface. Stated as a gap, to be an
item of its own if an author need appears.

## Retired vocabulary

- `TenantBindingIndex.Fold.collectFromFilters`, `collectFromBodyParam` and `collectFromRow`, and with
  them the fold's `RemoteColumnPredicate` unwrap. `accessOf` keeps its name. Prose describing the
  tenant fold as reading body params is stale after this item; the containment check reads them to
  verify coverage, not to classify.
- The "not recorded" rationale for `InputField.ConditionOwnedField`: its javadoc's "whether the field's
  name also resolves a column on the resolving table is deliberately not recorded: the column would be
  dead storage either way", the comment at the column-resolved mint in
  `BuildContext.classifyInputFieldInternal` ("the resolved column is unused by construction ... the
  column is deliberately not recorded"), and the "records no column" in the javadoc of
  `InputFieldCarrierRoleTest.aConditionOwnedFieldIsNoCarrier`. The carrier still drives no rail, which
  that test's assertion keeps saying; what retires is the claim that it holds no column.

## Tests

`TenantBindingClassificationTest` (`@UnitTier`, with `film_id` as the tenant column) takes the goal
table as cases: the six rows that become `ArgumentBound`, the five unchanged rows as regression, and
the two rows that stay rejected asserting the rejection is still typed
`Rejection.AuthorError.NoTenantBinding`. Each `ArgumentBound` assertion names the slot and both its
axes, so a nested filter-input row pins `NestedInput(filter, [filmId])` with a `Raw` projection, not
merely that something bound.

Three of those fixtures carry more weight than the rest, because in each a wrong mint still gives a
green build, the wrong answer being a verdict rather than an error. All three sit on a field that also
carries a `@condition`, so the coordinate mints a condition member and the ledger read fires.

- *Row eight*: a plain filter-input field `filmId: ID @field(name: "film_id")` carrying its own
  `@condition(condition: {...inputColumnCondition}, override: true)` (the shape
  `INPUT_IMPLICIT_CONDITION_EXPLICIT_OVERRIDE_SUPPRESSES_OWN` already builds), asserting `ArgumentBound`
  with a `NestedInput(filter, [filmId])` read and a `Raw` projection. It fails if `columnBindingOf`
  answers nothing for `ConditionOwnedField`, or if the classifier stops setting `resolvedColumn`. The
  containment check cannot see either, since this shape emits no body param, so this fixture and the
  `GraphitronSchemaBuilderTest` assertions below are row eight's enforcers.
- *Row ten*: a composite `@nodeId @lookupKey` argument whose decoded key embeds `film_id`, with a plain
  `@condition` and no `override` anywhere. With no suppression active, the lookup clause is the only
  thing keeping this slot out of the ledger, so the fixture fails if the clause is simplified away;
  without it the row classifies `ArgumentBound`, which is a cross-tenant `ids:` batch reading one
  tenant's database.
- *Row twelve*: a same-table `@nodeId` argument at arity 1 whose decoded key is `film_id`, under a
  field-level `@condition(override: true)`, asserting `ArgumentBound` with the projection pinned to
  `DecodedKeySlot` at index 0. It is the assertion that the two axes compose: the location comes from
  the suppressed carrier the ledger recorded, the transform from `accessOf` reading its extraction.
  Arity 1 is deliberate, being the shape a column-count discriminator would get wrong.

Row eleven's argument half is `sameTableNodeIdFilterDivinesTheDecodedSlot`, already in the file, which
this item only has to leave green. Its filter-input half has no pin, and this item re-sources exactly
that read (a nested `@nodeId` field's slot moves from a body param to a ledger row), so it gets one: a
query filter input whose `@nodeId` field decodes a key embedding `film_id`, asserting `ArgumentBound`
with a `NestedInput` read and a `DecodedKeySlot` projection.

The multi-table polymorphic shape gets its own fixture, because `MultiTableFilterLoweringTest`'s
`Customer | Staff` reaches no tenant-scoped table under `film_id` and so classifies untenanted. The
fixture is a bare `occ(id: ID! @nodeId): [Occ!]!` over `union Occ = Inventory | FilmActor`, `Inventory`
keyed on `inventory_id` and `FilmActor` on `(actor_id, film_id)`: both tables are tenant-scoped, and the
member order puts first the participant whose key omits `film_id`, the order that tells a
per-participant read from a coordinate-wide one.

- With no `@condition`, it rejects `Query.occ` with the `PruneOnMismatch` decline text, today's verdict
  and message in both member orders; the case pins that the re-sourcing did not change them.
- Under a field-level `@condition(override: true)` (`TestConditionStub.lifterFieldCondition`, which
  binds no argument, so the dispatched-`@nodeId` divergence refusal does not fire first), it rejects
  with the decline text rather than the generic "nothing maps to the tenant column". It pins that the
  mint reaches the declining carrier and that the decline channel, not a mint-side clause, holds it.

`ColumnBindingLedgerTest` (`@UnitTier`, beside the class) pins the ledger's own checks on hand-built inputs, since a check nobody
has seen fire is not yet an enforcer:

- `requireCovers` throws when a condition member's body param has no slot in its row, when the slot
  sits in the row of a different table (the key misalignment), and when name and columns match but the
  extraction differs; each message names the coordinate, table and slot.
- `requireCovers` passes when a row carries slots beyond the body params (the suppressed bindings) and
  when a composite `RowEq` is wrapped in `RemoteColumnPredicate`.
- A repeat record at one key passes with an equal row and throws with a different one; a non-empty row
  recorded with empty filters throws.

Beyond those, the check runs on every schema build in every tier, which is its coverage: both
multi-table fixtures above exercise the per-participant key on every run, and every existing fixture
that emits a column-bound predicate exercises the covered direction.

The carrier change is pinned where the carrier's classification already is. In
`GraphitronSchemaBuilderTest`, `INPUT_IMPLICIT_CONDITION_EXPLICIT_OVERRIDE_SUPPRESSES_OWN` (column
resolved) asserts `resolvedColumn` present and naming `film_id`, and `CONDITION_OWNED_FIELD` and
`plainInput_overrideTrueWithoutMatchingColumn_classifiesAsConditionOwnedField` (column missing) assert it
empty. *As implemented:* input-field carriers are not retained on the model past the walk, so these
assertions read `resolvedColumn` through the one ledger row it feeds (a slot `filmId` over `[film_id]`
with a `NestedInputField(filter, [filmId], JooqConvert)` extraction, and no row slot at all for the
column-miss shape). The ledger surfaces for that on `GraphitronSchemaBuilder.Bundle` as
`columnBindings`, beside `decodeLedger`, which is there for the same reason; the two column-resolved and
column-missing assertions are the `conditionOwnedField_*` tests beside the plain-input one. The emission assertions those cases already make stay as they are, which is the statement that
the binding axis moved and emission did not. `UpdateRowsWalkerTest` and `DeleteRowsWalkerTest`
construct the carrier directly and gain the argument, with no assertion change.

The compile tier gets one field in `graphitron-sakila-example/src/main/resources/graphql/multitenant.graphqls`,
that module's proof that every `TenantBinding` arm emits valid Java 17: a root whose tenant argument sits
under a field-level `@condition(override: true)`, using `tilgangAdminOnly` from
`no.sikt.graphitron.rewrite.test.conditions.InputFieldConditionFixtures` (the class the example's other
`@condition` fixtures use, and its one method binding no argument). It earns a place in a fixture whose
discipline is one field per arm by being the only field there whose slot cannot come from a predicate.
It stays compile-only, like the rest of that file: the predicate `tilgangAdminOnly` builds names
`rollekode`, which `film` does not carry.

No execution-tier case: the arm's runtime behaviour is unchanged, only which schemas reach it.

## Documentation

`docs/manual/how-to/condition-cascade.adoc` gains one sentence in each of its two override sections
(argument-level and field-level) and in the filter-input cascade section, saying that `override:` and
an input field's own `@condition` govern predicate emission and never the tenant routing a
column-bound argument or input field divines, cross-referencing `tenant-scoping.adoc`. The
filter-input sentence names the input field's own `@condition(override: true)` explicitly, since that
is the case a reader of the replace-rather-than-stack rule is most likely to wonder about.
`docs/manual/how-to/tenant-scoping.adoc` already promises that "an argument bound to the tenant column
routes the statement and hands the tenant down its subtree"; it needs no change, because this item is
what makes the promise true.

## Other solutions we've considered

**Stop suppressing the tenant argument's predicate.** The fold would find its body param again with no
new fact anywhere. Rejected: it changes the SQL an author explicitly asked to own, and it does so by
making the emission decision depend on the tenant column, which is the coupling this item removes.

**Emit the suppressed `BodyParam`s carrying a `suppressed` flag.** Every consumer of `bodyParams()`
would have to filter, and the renderer's contract, that a body param is a predicate, is worth more than
the one reader it would save.

**Carry the slots on the leaf, as a `TableFieldComponents` component and an `SqlGeneratingField`
accessor.** The fact would ride an existing surface, but that surface is implemented by every
SQL-generating leaf variant and read by every generator, and the fact has one reader. It is the
leaf-extension half of what the pipeline doc rules out, with no retirement story: a ledger of value
rows repoints at a relation, a leaf component has to be dismantled.

**Carry the slots on `OperationMember.Condition`.** The member is already keyed `(coordinate, table)`,
so the ledger row is naturally its payload, and the fold would read one relation instead of two. It
loses on where the member's payloads come from: `OperationMemberRelation.payloadsFor` extracts them
from leaf-carried resolutions, so the slots would have to ride the leaf first (the alternative above),
or the member production would join the walk-side ledger in, which gives the member relation a second
producer input for one reader. `OperationMembers.membersOf`, the leaf-derived projection the
membership-agreement pin compares against, has no ledger to join, so the two productions would carry
different `Condition` payloads.

**Reclassify a column-resolved override field as `ColumnBackedField`.** The argument side and the
`@nodeId` input leaves already work this way, and it would need no new component. Rejected because the
carrier change reaches every reader, and two would change behaviour silently rather than fail:
`MutationInputResolver.admitMutationInputFields` admits a non-composite `ColumnBackedField` on INSERT
without reading its `condition()`, so a field refused today would be admitted with its authored
condition dropped; and `EnumMappingResolver.buildLookupBindings` would turn it into a `MapGroup` lookup
binding. It would also move the carrier into the `LookupKeyField` / `SetField` rails its javadoc keeps
it out of, and reshape a leaf population `LeafRatchetTest` pins. Changing the leaf taxonomy belongs in
its own item.

**Mint the input-field column at the classification site.** `classifyInputFieldInternal` holds the
column and could record it directly. Rejected on keying: that function is keyed on the definition
(input type, field, resolving table), and one input type is shared across use sites, while a ledger
row is keyed on the use site (the coordinate, plus the `NestedInputField(outerArgName, leafPath, leaf)`
extraction, which exists only in the walk). The carrier is the right home for the definition-keyed
fact and the walk for the use-keyed join.

**Leave the input field's own override out of this item.** Row eight would read rejected before and
after, with the goal and documentation sentences narrowed to match. Rejected because the goal already
covers the shape and the repair is one component on one record; stopping short would apply the item's
rule to three of four suppressions, leaving out the one made at classification, where the next reader
would least expect it.

**Source the fact from the store now.** The documented destination, and unreachable at this stage
order: classification runs before capture, so the relations this would read hold the previous run's
rows, if any. See the first Implementation paragraph.

**Enforce the predicate-path half with a test over built schemas.** A meta-test asserting that every
emitted body param has a ledger row needs a harness enumerating the schemas the tier builds, and none
exists: `TestSchemaHelper.buildSchema` is a plain static call, and the cross-schema pins
(`OperationMemberMintPinTest`, `NodeIdDecodeCoverageRatchetTest`) run over `CorpusDocuments`, whose
condition members reach one of the body-param shapes. Named floor fixtures per arm would guard the arms
that exist today and not the next one, which arrives with its own test class rather than a corpus
document. The build-time check runs wherever a schema is built, which is the reach the enforcer needs.

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

#### Author response (2026-09-23, reviewer session 01VUCQBqSCcDLos3JxKYJXpS taking the author role at the user's direction, after consulting the principles-architect agent)

Finding 7 addressed in the direction that moves the shape. `InputField.ConditionOwnedField` gains
`Optional<ColumnRef> resolvedColumn`: present on the column-resolved outcome, empty on the column
miss and on the no-route `@nodeId` arm. The goal table gains that shape as its eighth row
(rejected, then `ArgumentBound`), which shifts the later row numbers by one. The Mechanism now names
the classification-time suppression as the fourth site, the one where the binding does not survive.

The mint moved as a consequence, and this is the larger change for the returning reviewer to audit.
The five per-arm mints inside the emission guards are replaced by one question per slot, asked ahead
of the emission switch: `columnBindingOf(ArgumentRef)` and `columnBindingOf(InputField, ...)`, each an
exhaustive switch with no `default`. The mint predicate is now "binds a column and is not
lookup-bound". The compiler owns the suppressed half of the ledger's domain and the containment pin
owns the predicate-path half. A per-arm mint could not have reached `ConditionOwnedField` without a
sixth arm the pin cannot see. `implicitBodyParam`'s `Direct`-to-`JooqConvert` rewrite moves into a
helper shared by both paths, so the substitution is not written twice.

The other two options your finding named are recorded under `## Other solutions we've considered`,
with why each lost:

- Reclassifying as `ColumnBackedField` would silently admit the field on INSERT and drop its
  condition, because `admitMutationInputFields` does not read `condition()` on that carrier. It
  would also make the field a `MapGroup` lookup binding.
- Scoping the shape out would leave the item's thesis applied to three of four suppressions.

The component's tension with "new facts land only in the store" is stated in the plan rather than
left to be inferred. So is a retirement note: when the fold is re-sourced onto the store, it must
read the column-match facts, not the ranked `intent_input_field_filter_role` winner, which repeats
this coupling in SQL.

Tests gain the eighth-row fixture as a third load-bearing case, since the containment pin cannot see
a shape that emits no body param. They also gain `resolvedColumn` assertions on the three
`GraphitronSchemaBuilderTest` cases that classify the carrier, and constructor churn in the two
walker tests. The documentation sentence now covers input fields, including their own override.
`## Retired vocabulary` declares the "deliberately not recorded" rationale in the carrier's javadoc,
the `BuildContext` comment and `InputFieldCarrierRoleTest`.

This session wrote both round 5 and this revision, so `Spec -> Ready` needs a session that has
committed neither.

### Round 6 (2026-09-24, Spec -> Ready, reviewer session 01B41uiTShFtDzZhqBARcpJq)

Verdict: withhold. One blocking finding on question two. It is narrow, and it is the only thing
standing between this plan and Ready.

Question one passes. When this lands, a consumer whose query field binds the tenant column on an
argument or a filter-input field, and whose authored `@condition` replaces that slot's implicit
predicate (by `override:` at field or argument level, by an input field's own `@condition`, or by an
input field's own `@condition(override: true)`), gets a schema that builds and routes on that slot,
where today the build fails with "no argument or input field maps to tenant column". The outcome is
reachable. Round 5's finding is settled: `classifyInputFieldInternal` really does drop the resolved
column at `BuildContext.java:3058`, the three `ConditionOwnedField` mint sites are the only ones in
main (`3058`, `3085`, `3173`), no reader destructures the record, and only `UpdateRowsWalkerTest` and
`DeleteRowsWalkerTest` construct it. The mint ahead of the emission switch fits the tree better
than the per-arm mints did. It is the shape `columnBindingOf` needs to be exhaustive, and
`ArgumentRef`'s nine arms and `InputField`'s five match the plan's lists exactly.

**Finding 8 (question two: architecture fit). The containment pin, which the plan makes the sole
enforcer of the predicate-path half of the ledger's domain, is specified over a harness that does
not exist. The nearest harness that does exist cannot deliver what the plan claims for the pin.**

The completeness argument has two enforcers. The compiler owns the suppressed half; the plan
states that part and it holds. The other half rests on `ColumnBindingLedgerContainmentTest`, "driven
over every fixture schema the tier already builds rather than over one fixture of its own", which
the plan says turns red "on whatever existing fixture first reaches the arm". The plan also says it
catches a misaligned `rt` on the `Customer | Staff` fixture. Nothing in the test tier enumerates the
schemas other tests build:

- `TestSchemaHelper.buildSchema` is a plain static call to `GraphitronSchemaBuilder.build`. The one
  autodetected extension is `ClassificationTraceContextExtension`, and it does not collect schemas.
- The `Customer | Staff` bare-`@nodeId` fixture the plan names lives inline in
  `MultiTableFilterLoweringTest`. No other test can reach it.
- The tier's cross-schema pins run over `CorpusDocuments.documents()`, sometimes with named
  fixtures added: `OperationMemberMintPinTest`, `ExemptionRegistry`, `NodeIdDecodeCoverageRatchetTest`.
  `ExemptionRegistry`'s javadoc uses "every fixture schema" to mean exactly that corpus. That makes
  the corpus the reading an implementer will land on. Measured on this tree, the corpus holds three
  condition members in two documents (`polymorphic-filter`'s two `OnParticipant` rows and one
  `OnReturnTable` in `faceted-connection`). All three are plain `@field` scalar arguments. So the
  corpus reaches one of the five body-param arms the census counts, and none of the reference, row
  or input-field arms. `OperationMemberMintPinTest` says the same thing from the other side: it adds
  per-kind floor fixtures because "the corpus is thin" on CONDITION.

On the corpus, then, the pin is close to vacuous for four of the five arms. It does not catch "a new
arm that emits without minting", because a new arm arrives with its own test class, not with a
corpus document. That leaves the five-arm census unguarded, which is the drift smell the plan quotes
to justify the pin. An implementer following the plan has to design the harness themselves, and the
choice decides whether the completeness argument holds. That is the redesign-as-you-go this gate
exists to catch, and it lands on the half of the argument the compiler cannot back.

What would satisfy it: name the harness the pin runs over, and make the pin's claims match what that
harness reaches. Which harness is the author's call. These would each do it:

- the corpus plus named floor fixtures, one per body-param arm and one multi-table participant
  pair, in the `OperationMemberMintPinTest` shape, with the "whatever existing fixture first reaches
  the arm" claim weakened to what floors give;
- a check that runs on every schema build in the test tier, which is what the claim as written
  needs, stating where it hooks in;
- containment checked where both sides are produced, so that every build enforces it and not only
  every test.

Whichever it is, the `rt`-alignment claim has to name a fixture that harness actually reaches.

**Corrected in this commit, none of it changing what the implementer builds.**

- The five-arm census cited stale line numbers. `bodyParams` is now appended at
  `FieldBuilder.java:3036`, `3043`, `3063`, `3079`, and through `implicitBodyParams` at `3165` and
  `3222`, folded in at `2983` and `2999`. The census itself still holds: nowhere else, and
  `GeneratedConditionFilter` is constructed only at `3092`, inside `projectFilters`, whose one caller
  is `projectForFilter`.
- R955 landed after the last revision and renamed the store relations the retirement paragraph
  names. `intent_argument_column_match`, `intent_input_field_column_match`,
  `intent_input_field_filter_role` and `intent_input_field_carrier_role` are now the `graphitron_*`
  stage tables of the same names. The ranking claim still holds: `CONDITION_OWNED` ranks 7 and
  `NAME_MATCHED` 8 in `graphitron_input_field_filter_role_rule`.
- The R966 rework made `collectFromInputFields`' `FilterBinding.Remote` arm a no-op, because
  `MutationInputResolver` rejects that carrier before the write classifies. The "What stays"
  paragraph said the arm declines. The plan does not touch that descent either way.

**Non-blocking.**

- The R966 rework also removed `declineRoutineWriteDecodes`. The plan body no longer names it, and
  the earlier rounds that do name it are history.
- The plan lists five `ConditionOwnedField` readers. `ContextArgumentClassifier`,
  `GraphitronSchemaValidator` (two arms), `ParentSourceBinding` and `TypeFetcherGenerator` also read
  it. Every one branches on carrier identity or the condition, so "no current reader moves" holds.
- The pin needs the ledger visible to a test. `Bundle` already carries `decodeLedger` for the same
  reason, so this is a signature detail, not a design one.

Verified this round beyond the above. The goal-table tallies match: six moving, five unchanged, two
still rejecting. The emit guards read as the Mechanism quotes them. `directBinding`'s four callers
(`282`, `383`, `495`, `1090`) each hold a `FieldCoordinates`. `OperationMember.Condition.table()`
exists on both arms. The `rt` alignment holds on the participant loop (`FieldBuilder.java:1212` and
`1228`) and on the routine chain (`3663`). R382's changes since the last revision (the
`PolymorphicOrderBy` member) touch neither the condition members nor the fold's direct-binding
read. `accessOf`'s two switches read as the plan describes, with `ContextArg` passing itself as the
leaf, and `MethodRef` is its only construction. `implicitBodyParam`'s `Direct`-to-`JooqConvert`
rewrite is where the plan says it is. `TestConditionStub.inputColumnCondition` /
`lifterFieldCondition`, `InputFieldConditionFixtures.tilgangAdminOnly`, `multitenant.graphqls`,
`sameTableNodeIdFilterDivinesTheDecodedSlot`, the three `GraphitronSchemaBuilderTest` cases,
`InputFieldCarrierRoleTest.aConditionOwnedFieldIsNoCarrier`, `Rejection.AuthorError.NoTenantBinding`,
`MutationInputResolver`'s INSERT refusal and `EnumMappingResolver.buildLookupBindings`' skip all
exist as named. So do the `tenant-scoping.adoc` promise (line 35), the `pipeline-overview.adoc`
heading (line 28), the `fact-model.adoc` "Name the row" heading (line 31) and its quote (line 39),
and both override sections of `condition-cascade.adoc`.

#### Author response (2026-09-24, session 01ABw9PkCVJQNGg9EQyXeyHo, after consulting the principles-architect agent)

Finding 8 addressed with the third way out the finding offered: containment is checked where both
sides are produced, so every build enforces it and not only every test. `ColumnBindingLedgerContainmentTest`
is gone. In its place `ColumnBindingLedger.requireCovers(OperationMemberRelation)` runs in
`GraphitronSchemaBuilder` directly after the member relation is minted, ungated on tenancy, following
`OperationMembers.validateAgainstDeclaredShape`, which already fences the same relation with
`IllegalStateException` on every build. For every condition member it requires each body param to have a
slot in the row at `(coordinate, member.table())` with the same name, the same columns and an equal
extraction. The extraction is compared because it is half of what the fold reads. That puts the
enforcer's reach where the claim needs it: every schema any test in any tier builds, and every consumer
build, so a new predicate arm's own tests trip it. The `rt`-alignment claim now names fixtures the check
reaches, since it runs on `MultiTableFilterLoweringTest`'s `Customer | Staff` and this item's
`Inventory | FilmActor` on every run. The line-number census goes with the test, because the check is
what it was standing in for. `ColumnBindingLedgerTest` pins that the check fires. The cost of running it
ungated is stated in the plan: a missed mint on an arm no tenant consumer uses fails the build of every
consumer that reaches it.

Three tightenings came out of the consultation, and they are worth the returning reviewer's attention.

- The row is recorded by `projectForFilter` immediately before it returns `TableFieldComponents.Ok`,
  with its final filter list, so a rejected field leaves no row.
- A repeat record at one key must carry an equal row and throws otherwise. This replaces first-mint-wins,
  which the table in the key made unnecessary and which would have hidden a divergent second
  classification.
- A non-empty row recorded with empty filters throws. This enforces, rather than argues, what round 3
  noted as implicit: a condition member exists wherever a row matters. A relation-level reverse check
  (every non-empty row has a member) was considered and not taken. `OperationMemberRelation`'s
  domain-boundary javadoc records that a nested coordinate can alias a flat one, so a row can be
  legitimately unread, and a check at that grain could fire on a correct schema.

One scope reduction. The exhaustive leaf switch in `accessOf` is dropped from this item and filed as
R971. The ledger hands `accessOf` exactly the extractions body params already carry, plus a wrapped
`Direct` / `JooqConvert` from `ConditionOwnedField`, which `ColumnBackedField` body params already
produce. So the hardening is independent of this bug and changes no verdict here.

The plan body is rewritten in place, per `roadmap/workflow.adoc` § Item file conventions ("A revision
improves the plan in place"; "Anchor code references in item bodies on symbols, not line numbers").

- **Removed:** the draft-history narration, the `decodesAKey` retired-vocabulary entry for a term that
  never reached the tree, and the two alternatives R966's landing made moot (minting decoded-key carriers
  with a tabled request-time failure, and depending on R966).
- **Relocated:** generator mechanism moved out of the Goal into Mechanism. The sis reach moved into the
  Goal, and `## Provenance` went, since the item settles no question.
- **Retitled:** the title is widened to the goal's actual scope, which was never field-level overrides
  alone.
- **Added:** two alternatives, carrying the slots on `OperationMember.Condition` (round 3's
  non-blocking note, costed against the two `Condition` productions) and a test over built schemas
  (why the check replaced it).
- **Carried over:** round 6's corrections (the R955 relation names, the `FilterBinding.Remote` no-op)
  are in the body. On the non-blocking notes: the reader list now includes the validator,
  context-argument and fetcher-generator arms, and the `Bundle` visibility note is moot because the
  check takes the relation directly.

Nothing in the design's substance moved beyond the enforcer. The ledger, its key, the mint predicate,
`ConditionOwnedField.resolvedColumn` and the fold's read are as round 6 verified them.

This session wrote the revision, so `Spec -> Ready` needs a session that has committed neither.
