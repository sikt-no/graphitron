---
id: R965
title: "Column-bound argument slots divine the tenant under a field-level @condition override"
status: Spec
bucket: bug
priority: 2
theme: classification-model
depends-on: []
created: 2026-09-22
last-updated: 2026-09-22
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
(2026-09-22 spike):

| SDL shape, on a field returning tenant-scoped `film` | today | when this lands |
|------------------------------------------------------|-------|-----------------|
| `films(filmId: Int @field(name: "film_id"))` | `ArgumentBound` | unchanged |
| the same, with a *sibling* argument carrying `@condition(override: true)` | `ArgumentBound` | unchanged |
| the same, with a *field-level* `@condition(override: true)` | rejected | `ArgumentBound` |
| the tenant argument itself carrying `@condition(override: true)` | rejected | `ArgumentBound` |
| the tenant argument carrying its own `@condition`, no `override` anywhere | `ArgumentBound` | unchanged |
| a filter input whose field binds the column, under a field-level `@condition(override: true)` | rejected | `ArgumentBound` |
| a filter input whose field binds the column and carries *its own* `@condition`, no `override` anywhere | rejected | `ArgumentBound` |
| the tenant argument as a `@lookupKey`, under a field-level `@condition(override: true)` | `ArgumentBound` | unchanged |
| a field where nothing binds the tenant column | rejected | rejected |

Two rows are worth reading twice. The fourth says the Backlog framing was half right: it is not that
an argument-level override is safe and a field-level one is not, it is that an override anywhere over
the *tenant argument's own* predicate loses the binding, and the reported field only classified
because its override sat on a sibling. The seventh says `override:` is not even necessary to trigger
this: an input field carrying an ordinary `@condition` replaces its implicit predicate too, so the
same binding disappears with no override in the schema at all. The last row is the obligation that
does not move: a field that binds nothing to the tenant column still rejects, because routing tenant
data through a default connection when nothing named the tenant is the leak this axis exists to
prevent.

## Mechanism (verified against the tree, 2026-09-22)

`TenantBindingIndex.Fold.directSlots` discovers an operation's tenant slots per member. For a
condition member it delegates to `slotsFromFilters`, which walks `GeneratedConditionFilter.bodyParams()`
and mints a `TenantBinding.BoundSlot` for every `BodyParam` whose column matches the tenant column.
The slot therefore exists only where a predicate was emitted.

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

The other two direct-slot paths do not have this problem, and the reason is the design this item
generalises: `slotsFromLookup` reads `LookupMapping.ColumnMapping`, and `slotsFromTableInput` reads
`ArgumentRef.InputTypeArg.TableInputArg`. Both are classification carriers rather than emitted
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
reader infer it: it is keyed and grained the way the relation would be, one row per coordinate and
slot, so the retirement is repointing one reader and not a remodelling. It retires when the tenant
fold re-sources onto the store, with the rest of the transitional surface.

**A walk-minted ledger, tenancy-neutral.** New `ColumnBindingLedger` in `no.sikt.graphitron.rewrite`,
rows keyed by `FieldCoordinates`, each row a list of
`ColumnBoundSlot(String slotName, List<ColumnRef> columns, CallSiteExtraction extraction)`: every
argument and input field whose classification resolved a column, whatever `projectFilters` then
decided to emit. It knows nothing about tenancy; it answers "which column does this slot name, and how is its value read at the
call site", which the classification has already answered and then been throwing away. The tuple is a
list because a composite carrier (the node-key case) binds several columns at once, and the columns
recorded are the ones the predicate would have bound (`predicateColumns(binding, columns)` on the
reference arms), so a `@reference`-reached tenant column keeps divining exactly as today's
`RemoteColumnPredicate` unwrap does. `NodeIdDecodeLedger` is the shape precedent, not the provenance
precedent: that ledger records what the walk *did*, which no relation could hold, while this one
records a function of the SDL and the catalog that a relation will hold.

**The row carries the extraction, and `readOf` stays the one resolver.** A row holds the
`CallSiteExtraction` the classification already resolved for that slot, and the fold turns it into a
`TenantBinding.SlotRead` through the existing `readOf`, which is where the tenancy projection belongs:
the ledger stays free of tenancy vocabulary, and the extraction-to-read mapping stays in one function
rather than being copied to a second site. The honest cost is that `CallSiteExtraction` is an emission
carrier (its decode arms reach `HelperRef`), so a row holding one is not yet the plain value row the
relation this prefigures would hold; that conversion belongs with the re-sourcing, not ahead of it.
Two things `readOf` owes on the way past: its `default -> TopLevelArg.INSTANCE` becomes an exhaustive
switch, so a new extraction arm is a compile error asking which read it is instead of silently reading
as a top-level argument, and it is the function R966 plans to widen (see the seam below), so it keeps
its name and its single-home property.

**Held and threaded like the decode ledger.** A `private final ColumnBindingLedger` field on
`BuildContext` beside `decodeLedger`, with a package-private accessor; `GraphitronSchemaBuilder` reads
it off the context at schema assembly, the same read it already makes for `bctx.decodeLedger()`, and
passes it into `TenantBindingIndex.compute` beside `operationMembers`. `compute` keeps its
not-computed sentinel discipline: a `ColumnBindingLedger.EMPTY` compared by reference identity, so a
hand-built schema that never ran the walk is refused rather than silently classifying everything
unbound, exactly as the `OperationMemberRelation.EMPTY` check does today.

**Two mint sites, and why they are complete.** In `projectFilters`, each `ColumnBackedArg` /
`ColumnBackedReferenceArg` arm records its slot *before* the `autoSuppressed` test, so suppression
changes the emitted predicate and nothing else. In `walkInputFieldConditions`, the `ColumnBackedField`
/ `ColumnBackedReferenceField` arms record theirs at the site that already computes `leafPath`, which
is the nested read path the slot needs, and record it outside the
`!enclosingOverride && condition().isEmpty()` guard. Completeness is structural rather than reviewed:
those two sites are exactly where the `BodyParam`s `slotsFromFilters` reads are constructed, and
`projectFilters` is the only construction site of `GeneratedConditionFilter` in the tree, so the
ledger's domain is the predicate path's domain by construction. Both sites run once per participant on
a polymorphic coordinate, so the ledger dedupes by slot name per coordinate, keeping the first mint,
which is the dedup `directSlots` does across members today.

**The fold reads it.** `Fold.directSlots` replaces
`case OperationMember.Condition c -> slotsFromFilters(c.filters())` with one read of the coordinate's
ledger row, minting one `BoundSlot` per column that `matchesTenantColumn` accepts and resolving its
read through `readOf`, which is what `collectFromBodyParam`'s `Eq` / `In` / `RowEq` / `RowIn` arms do
between them today. `slotsFromFilters` and `collectFromBodyParam` retire: with the ledger in place nothing reads a `BodyParam` to answer a
classification question, which is the one-place property this item is after.

**The seam with R966, which is in flight on the same fold.** R966 plans a second component on
`TenantBinding.BoundSlot` (a projection axis beside the location axis) and routes every minting site
through a widened `readOf`, naming `collectFromBodyParam`, `slotsFromLookup` and
`collectFromInputFields` as those sites. The two plans compose in one direction and collide in the
other, so the sequencing is stated rather than discovered at rebase: this item removes
`collectFromBodyParam` as a minting site and puts the ledger read in its place, which leaves R966 with
one fewer site and no change to its shape. Whichever item lands second rebases; if that is R966, its
"every minting site" list reads ledger read, `slotsFromLookup`, `collectFromInputFields`, write arm.
This item deliberately leaves `TenantBinding.SlotRead` where it is, although a tenancy-neutral ledger
reaching a type nested under `TenantBinding` is a naming inversion worth fixing: renaming another
item's central type mid-flight costs both items more than the inversion costs a reader, and the fix
keeps until one of them owns that type alone.

**What stays, and the descent this item does not close.** `slotsFromLookup` and `slotsFromTableInput` stay
as they are. They already read classification carriers (`LookupMapping.ColumnMapping`, the
`TableInputArg` envelope), which is why they are already override-proof, and this item leaves the fold
with two direct-slot descents where it has three today rather than adding a fourth. One of the two has
a gap worth recording here because it is that item's: `Fold.collectFromInputFields` handles
`ColumnBackedField` and `NestingField` and drops `InputField.ColumnBackedReferenceField` to its
`default` arm, while `FieldBuilder.walkInputFieldConditions` handles all three, so an FK-target
`@nodeId` input field whose lifted column is the tenant column divines on the query path and not on the
INSERT path. That is R966's third sis shape (`opprettKull`, a `@nodeId` reference field on an INSERT
input), it is reworking that descent anyway, and splitting the write path across two in-flight items
would put both of them in the same function. The ledger is shaped to receive that path when R966 takes
it.

**The emitter audit the producer relaxation owes.** Widening which coordinates classify
`ArgumentBound` is a producer relaxation, so every emit site assuming the old population was audited:
`TenantDslEmitter.dslExpression` throws on `ArgumentBound` at its expression-only site, justified by
service operations contributing no argument slots, and that claim survives because the mint stays
inside `projectFilters`, which no `@service` coordinate reaches. `TenantDslEmitter.slotReads` and
`TenantAcquisitionFragments.slotRead` render over `SlotRead` alone and assume no matching body param,
so the overridden-predicate case needs nothing new from them. `MultiTablePolymorphicEmitter`'s
`ArgumentBound` read is per-participant and unaffected: the ledger is keyed by coordinate, as the
deduped slot list already was.

**The disclosed gap.** An argument whose own predicate an author overrode now divines the tenant. That
is the intended reading, since routing chooses a database and an argument bound to the tenant column
names a tenant whatever predicate runs inside that database. The cost is that a condition method
reinterpreting the value (a prefix match, a deliberately cross-tenant `IN`) routes on a value the
author did not mean as a tenant id, and nothing at build time enforces that it did not; the generated
`divinedTenant` agreement guard is a runtime check and not an enforcer. There is no SDL way to say
"names the column, does not divine", and inventing one here would widen this item into a directive
surface. Stated as a gap, to be an item of its own if an author need appears.

## Retired vocabulary

- `TenantBindingIndex.Fold.slotsFromFilters` and `collectFromBodyParam`: the predicate-derived slot
  mint. `readOf` survives and keeps its name; prose describing the tenant fold as reading body params
  is stale after this item.

## Tests

`TenantBindingClassificationTest` (L2 unit tier, the file that already carries one fixture per
`TenantBinding` arm) takes the goal table as cases: the three rejecting rows that become
`ArgumentBound`, the four unchanged rows as regression, and the final no-binding row asserting the
rejection is still typed `Rejection.AuthorError.NoTenantBinding`. Each `ArgumentBound` assertion names
the slot and its `SlotRead`, so a nested filter-input row pins `NestedInput(filter, [filmId])` and not
merely that something bound.

No agreement pin against the predicate-derived path, and the reason is worth stating because the tree's
shadow tests (`ColumnMatchShadowTest` and siblings) are the idiom a reader would expect here: those
compare two live productions, and this item retires one of the two in the same commit. Keeping
`slotsFromFilters` alive to be an oracle would make a predecessor normative, pinning its gaps. The
unchanged rows of the goal table are the regression statement instead.

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

105 more of the 121 are R966, the sibling classifier gap for write inputs keyed by a decoded node id.
The two are independent; this one is the smaller change and unblocks the larger cascade, so it is
worth taking first.

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
