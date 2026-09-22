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

That separation holds where a slot's wire value *is* the column's value. It does not yet hold where
the value is an encoded node id, because the tenant component exists only after a decode that the
predicate path itself performs, so dropping the predicate genuinely drops the only place the value
is produced. Those carriers keep today's behaviour under this item, for the reason the decoded-key
rows of the table below give.

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
| a filter input whose field binds the column *by name*, under a field-level `@condition(override: true)` | rejected | `ArgumentBound` |
| a filter input whose field binds the column *by name* and carries *its own* `@condition`, no `override` anywhere | rejected | `ArgumentBound` |
| the tenant argument as a `@lookupKey`, under a field-level `@condition(override: true)` | `ArgumentBound` | unchanged |
| a composite `@nodeId @lookupKey` argument whose decoded key embeds the tenant column | rejected | rejected |
| a `@nodeId` argument or filter-input field whose decoded key embeds the tenant column | `ArgumentBound` | unchanged |
| the same, under a field-level `@condition(override: true)` | rejected | rejected |
| a field where nothing binds the tenant column | rejected | rejected |

Five rows are worth reading twice. The fourth says the Backlog framing was half right: it is not that
an argument-level override is safe and a field-level one is not, it is that an override anywhere over
the *tenant argument's own* predicate loses the binding, and the reported field only classified
because its override sat on a sibling. The seventh says `override:` is not even necessary to trigger
this: an input field carrying an ordinary `@condition` replaces its implicit predicate too, so the
same binding disappears with no override in the schema at all.

The ninth, tenth and eleventh are the decoded-key family, and they share one reason rather than
three. A wire id decodes to a key whose tenant component is one slot of a tuple, and the decode lives
on the predicate path: `TenantBinding.SlotRead.TopLevelArg`, the read the fold resolves for these
carriers, renders `env.getArgument(name)` and hands `divinedTenant` the encoded text rather than the
decoded component. Emission and classification really are coupled for them today, so this item's
thesis does not reach them and it says so instead of pretending otherwise. The ninth stays rejecting
because `@lookupKey` routes it to the per-row family, which is where one tenant per row belongs. The
tenth keeps the slot it has today, defective read and all, because taking it away would turn a schema
that builds into one that does not. The eleventh keeps rejecting, because minting through the
suppression there would hand the fold a slot the generated code cannot read, trading a build error
for a request-time one. R966 is the item that widens the read, and the eleventh becomes
`ArgumentBound` when it lands. The family is in the table because the mechanism below could reach it
by accident and must not.

The last row is the obligation that does not move: a field that binds nothing
to the tenant column still rejects, because routing tenant data through a default connection when
nothing named the tenant is the leak this axis exists to prevent.

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
slot. Grain is the half that carries over cleanly; the row's `CallSiteExtraction` column is the half
that does not, being an emit carrier no relation can hold, so the retirement is a repoint of one
reader *plus* a re-derivation of the extraction-to-read mapping from stored facts. That is a smaller
job than dismantling a leaf component and a larger one than the word "repoint" suggests, and the plan
would rather say so than have a later reader discover it. It retires when the tenant fold re-sources
onto the store, with the rest of the transitional surface.

**A walk-minted ledger, tenancy-neutral.** New `ColumnBindingLedger` in `no.sikt.graphitron.rewrite`,
rows keyed by `FieldCoordinates`, each row a list of
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

**The row carries the extraction, and `readOf` stays the one resolver.** A row holds the
`CallSiteExtraction` the classification already resolved for that slot, and the fold turns it into a
`TenantBinding.SlotRead` through the existing `readOf`, which is where the tenancy projection belongs:
the ledger stays free of tenancy vocabulary, and the extraction-to-read mapping stays in one function
rather than being copied to a second site. The honest cost is that `CallSiteExtraction` is an emission
carrier (its decode arms reach `HelperRef`), so a row holding one is not yet the plain value row the
relation this prefigures would hold; that conversion belongs with the re-sourcing, not ahead of it.
Two things `readOf` owes on the way past. Its `default -> TopLevelArg.INSTANCE` becomes an exhaustive
switch over `CallSiteExtraction`'s ten arms, so a new extraction arm is a compile error asking which
read it is instead of silently reading as a top-level argument. Exhaustiveness changes no verdict,
because every arm that reaches `readOf` today keeps today's answer: `NestedInputField` yields
`NestedInput`, `ContextArg` yields `ContextArg`, and `Direct`, `JooqConvert`, `EnumValueOf` and
`NodeIdDecodeKeys` yield `TopLevelArg`, which is what the `default` gives them now and what
`slotsFromLookup` hardcodes for the same shapes. `NodeIdDecodeKeys` is worth writing out, and worth
writing out honestly, because its answer is carried forward rather than endorsed: `TopLevelArg`
renders `env.<Object>getArgument(name)` in `TenantAcquisitionFragments.slotRead`, so what reaches
`divinedTenant` is the encoded id, not the tenant component of the tuple the decode produces. That is
today's behaviour for every composite `RowEq` / `RowIn` slot through the `default`, and nothing here
fixes it: R966 widens `readOf` with a projection axis and the fix belongs there. So does the validator
mirror it is missing. A classifier verdict of `ArgumentBound` here implies a generated read that is
known not to work, and it surfaces at request time rather than at validate time, which is the one
place this axis otherwise does not leave to runtime. The gap is preserved rather than introduced, and
it is named here so that R966 inherits it stated rather than discovered. What this item owes
is not to spread it, which is what the decode clause on the mint predicate below does. The four
record-shaped arms
(`NodeIdDecodeRecord`, `NodeIdDecodePolymorphicRecord`, `InputBean`, `JooqRecord`) are whole-input
extractions minted by `InputBeanResolver` and the fetcher generator for arguments that bind no single
column, so no column-bound slot carries one; they throw an invariant naming the carrier rather than
answering with a read, which is what makes the exhaustiveness worth having instead of a `default`
under a longer spelling. Second, `readOf` is the function R966 plans to widen (see the seam below), so
it keeps its name and its single-home property.

**Held and threaded like the decode ledger.** A `private final ColumnBindingLedger` field on
`BuildContext` beside `decodeLedger`, with a package-private accessor; `GraphitronSchemaBuilder` reads
it off the context at schema assembly, the same read it already makes for `bctx.decodeLedger()`, and
passes it into `TenantBindingIndex.compute` beside `operationMembers`. `compute` keeps its
not-computed sentinel discipline: a `ColumnBindingLedger.EMPTY` compared by reference identity, so a
hand-built schema that never ran the walk is refused rather than silently classifying everything
unbound, exactly as the `OperationMemberRelation.EMPTY` check does today.

**Two mint sites, five arms, and the predicate at each.** The mint predicate is stated per arm rather
than as "before the suppression test", because each guard in these two functions mixes clauses of
three kinds and only one of them is a suppression the ledger may record through.

A *suppression* clause says a predicate the slot would otherwise have contributed is being dropped in
favour of authored SQL. That is emission, and recording through it is the whole of this item. The
clauses are `!autoSuppressed` on the argument arms and `!enclosingOverride &&
<carrier>.condition().isEmpty()` on the input-field arms, the second pair being the documented
replace-rather-than-stack semantics for an input field, which is a suppression under another
spelling.

A *lookup* clause says the slot is not on the predicate path at all, because `@lookupKey` routes it to
`LookupMappingResolver` and the VALUES+JOIN input-rows helper instead. That is a different mechanism,
which mints its own tenant slots through `slotsFromLookup`, and the ledger records nothing there. The
clauses are `!ca.isLookupKey()` and `!lookupBoundNames.contains(<carrier>.name())`.

A *decode* clause is the one this item adds, and it has no counterpart in the tree because today the
predicate is the mint. `decodesAKey(CallSiteExtraction)` is true for `NodeIdDecodeKeys` and for a
`NestedInputField` whose `leaf` is one, false for everything else; the four record-shaped arms cannot
reach a column-bound slot, the same invariant `readOf` throws on. Where it is true the suppression
clauses stand and the ledger mints exactly where a predicate is emitted, for the reason the goal
table's decoded-key rows give. The discriminator is the extraction rather than the carrier or its
arity, and that is load-bearing in both directions: a same-table `@nodeId` `ColumnBackedArg` decodes
at arity 1 as well as composite (`FieldBuilder.java:2341-2348`), so an `isComposite` test would admit
the arity-1 shape by accident, and `InputField.ColumnBackedReferenceField` declares a plain
`CallSiteExtraction` (`InputField.java:163`), so a `@reference` resolving locally on that carrier is
wire-valued and should widen while a `@nodeId` on the same carrier should not. Only
`ArgumentRef.ScalarArg.ColumnBackedReferenceArg` narrows its extraction to `NodeIdDecodeKeys` by
declaration (`ArgumentRef.java:183`), which is what makes its two arms pure preservation rather than
a case to reason about.

Taken together, the predicate at each arm is that arm's own guard with its suppression clauses
dropped when `!decodesAKey(extraction)`, and nothing else moved.

In `projectFilters`:

- `ColumnBackedArg`, whose guard is `!autoSuppressed && !ca.isLookupKey()`: mint when
  `!ca.isLookupKey() && (!autoSuppressed || !decodesAKey(ca.extraction()))`.
- both `ColumnBackedReferenceArg` arms, whose guard is `!autoSuppressed` alone: mint on that guard
  unchanged, `decodesAKey` being true on this carrier by declaration. It has no `isLookupKey` slot by
  construction, an FK-target being a filter and not a lookup, so there is no lookup clause to honour
  either. These two arms widen nothing; they are in the ledger so the fold reads one production
  rather than two.

In `walkInputFieldConditions`, at the site that already computes `leafPath`, which is the nested read
path the slot needs:

- `ColumnBackedField` and `ColumnBackedReferenceField`, whose guard is
  `!enclosingOverride && <carrier>.condition().isEmpty() && !lookupBoundNames.contains(<carrier>.name())`:
  mint when
  `!lookupBoundNames.contains(...) && (!decodesAKey(<carrier>.extraction()) || (!enclosingOverride && <carrier>.condition().isEmpty()))`,
  which is the lookup clause always and the two suppression clauses only for a decoding carrier. The
  extraction the row carries here is the wrapped
  `NestedInputField(outerArgName, leafPath, leaf)` the body param would have carried and not the bare
  leaf, because that is what `readOf` needs to resolve a `NestedInput`; `decodesAKey` recurses into
  the leaf for exactly that reason.

`decodesAKey` is one function with five callers rather than five spellings of a predicate, because
two consumers evaluating the same test over the same carrier is a branch that belongs in one place;
R966 adds a sixth caller and then deletes all of them. It also carries the comment that names what it
is for, because "honour the arm's guard verbatim" is written at each site as *no code at all* and so
reads as nothing: a decoding carrier's row is minted only where the predicate survived, since the
tenant component of the decode has no carrier in the model yet. Without that sentence the one
surviving coupling between classification and emission, which is the most load-bearing fact about
this design's interim status, is invisible at every site where it lives.

Completeness is structural rather than reviewed, and the lookup and decode clauses are what make the
claim true in both directions. Those five arms are exactly where the `BodyParam`s `slotsFromFilters`
reads are constructed, and `projectFilters` is the only construction site of
`GeneratedConditionFilter` in the tree, so nothing on the predicate path escapes the ledger. The two
clauses are the other half: without them the ledger's domain would be strictly *larger* than the
predicate path's, and larger in the two directions where a larger domain is wrong rather than merely
redundant. Stated as a set, the ledger's domain is the predicate path's domain plus the suppressed
wire-valued column bindings, which is this item restated without prose.

The lookup direction. `LookupMappingResolver` routes a composite `@lookupKey` `ColumnBackedArg`, and
an input argument's `InputColumnBindingGroup.DecodedRecordGroup`, to
`LookupMapping.ColumnMapping.LookupArg.DecodedRecord`, which `slotsFromLookup` skips on purpose: a
decoded node id carries its own tenant per row, so a list of them has no single tenant to route the
statement on. A ledger minted ahead of the lookup clause would hand the fold one anyway, and a
cross-tenant `ids:` batch would read whichever database the first decoded key pointed at. That is the
ninth row of the goal table.

The decode direction is the eleventh row, and it is the same defect reached through the other door.
The carriers there are not lookup-routed, so the lookup clause does not cover them: a same-table
`@nodeId` `ColumnBackedArg` without `@lookupKey`, an FK-target `@nodeId` on either reference carrier.
Minted ahead of the suppression, each hands the fold an `ArgumentBound` slot whose read is the
encoded id, so a field that rejects at build time today would build and fail at request time instead.
It fails closed rather than leaking, `divinedTenant` throwing on the parse for a numeric tenant
column and finding no such tenant for a textual one, but trading a build error for a request-time one
is not an improvement this item is entitled to make. Stating the clause here is also what gives the
two exclusions one reason: `@lookupKey` is not what makes a decoded key unroutable on this axis, the
decode is, and the lookup routing is one of the two places that decode shows up.

The scalar half of the lookup exclusion is inert rather than load-bearing, and saying so keeps that
clause from looking arbitrary: a scalar `@lookupKey` argument and a `MapGroup` input field are
already minted by `slotsFromLookup` under the same slot names with the same reads, so a duplicate
ledger mint would change no verdict. The clause is uniform because the rule is about which mechanism
owns a slot, not about which duplicates happen to be harmless.

Both sites run once per participant on a polymorphic coordinate, so the ledger dedupes by slot name
per coordinate, keeping the first mint, which is the dedup `directSlots` does across members today.

**The fold reads it.** `Fold.directSlots` replaces
`case OperationMember.Condition c -> slotsFromFilters(c.filters())` with one read of the coordinate's
ledger row, minting one `BoundSlot` per column that `matchesTenantColumn` accepts and resolving its
read through `readOf`, which is what `collectFromBodyParam`'s `Eq` / `In` / `RowEq` / `RowIn` arms do
between them today. `slotsFromFilters` and `collectFromBodyParam` retire: with the ledger in place
nothing in production reads a `BodyParam` to answer a classification question, which is the one-place
property this item is after. The containment pin below still reads `bodyParams()`, and deliberately:
it reads them to enforce that the ledger covers them, which is the opposite of deriving a
classification from them.

**The seam with R966, which is in flight on the same fold.** R966 plans a second component on
`TenantBinding.BoundSlot` (a projection axis beside the location axis) and routes every minting site
through a widened `readOf`, naming `collectFromBodyParam`, `slotsFromLookup` and
`collectFromInputFields` as those sites. The two plans compose in one direction and collide in the
other, so the sequencing is stated rather than discovered at rebase: this item removes
`collectFromBodyParam` as a minting site and puts the ledger read in its place, which leaves R966 with
one fewer site and no change to its shape. Whichever item lands second rebases; if that is R966, its
"every minting site" list reads ledger read, `slotsFromLookup`, `collectFromInputFields`, write arm.

The `decodesAKey` clause is the other half of the seam, and it is R966's to retire. Once `readOf`
returns the projection axis, a decoded-key slot reads its tenant component correctly, the clause has
nothing left to protect, and dropping it makes the mint predicate uniform and turns the goal table's
eleventh row into `ArgumentBound`. The forcing function for that lift is the eleventh row's test, and
naming it correctly matters because the obvious answer is wrong: widening `readOf`'s return type makes
every *call site* a compile error, but `decodesAKey` and the five guards live in `FieldBuilder` and
are not call sites, so R966 could satisfy every compile error and leave the clause standing, silently
narrowing the ledger forever for the family it just made correct. That failure would read as "R966
did not cover that shape". The eleventh row's fixture asserts the interim verdict, a rejection, on a
shape R966 makes route, so R966 cannot leave it green: the lift arrives as a red test in the file R966
is already editing. If R966 lands first instead, this item rebases onto the widened `readOf`, the
clause is never written, and the eleventh row and its test move with it. That is the reason this item
takes no `depends-on` on R966 although it could: the two compose in either order, and stating where
the boundary of this item's thesis falls is worth keeping in the plan that defines the ledger even
when the clause enforcing it is short-lived.

This item deliberately leaves `TenantBinding.SlotRead` where it is, although a tenancy-neutral ledger
reaching a type nested under `TenantBinding` is a naming inversion worth fixing: renaming another
item's central type mid-flight costs both items more than the inversion costs a reader, and the fix
keeps until one of them owns that type alone.

**What stays, and the descent this item does not close.** `slotsFromLookup` and `slotsFromTableInput` stay
as they are. They already read classification carriers (`LookupMapping.ColumnMapping`, the
`TableInputArg` envelope), which is why they are already override-proof, and this item leaves the fold
with two direct-slot descents where it has three today rather than adding a fourth. `slotsFromLookup`
also stays the *sole* owner of lookup-bound slots, which the lookup clauses on the mint predicate are
what enforce; its `DecodedRecord` skip therefore keeps meaning what it says. Giving a decoded key a
tenant verdict of its own is the per-row family's question, not this axis's, and it belongs with the
node-dispatch facts that already partition per decoded tenant.

One of the two surviving descents has a gap worth recording here, because it is R966's rather than
this item's: `Fold.collectFromInputFields` handles
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

**The disclosed gap.** A wire-valued argument whose own predicate an author overrode now divines the
tenant. That is the intended reading, since routing chooses a database and an argument bound to the tenant column
names a tenant whatever predicate runs inside that database. The cost is that a condition method
reinterpreting the value (a prefix match, a deliberately cross-tenant `IN`) routes on a value the
author did not mean as a tenant id, and nothing at build time enforces that it did not; the generated
`divinedTenant` agreement guard is a runtime check and not an enforcer. There is no SDL way to say
"names the column, does not divine", and inventing one here would widen this item into a directive
surface. Stated as a gap, to be an item of its own if an author need appears.

## Retired vocabulary

- `TenantBindingIndex.Fold.slotsFromFilters` and `collectFromBodyParam`: the predicate-derived slot
  mint. `readOf` survives and keeps its name; prose describing the tenant fold as reading body params
  is stale after this item, with the one exception the containment pin makes explicit, which reads
  them to check coverage rather than to classify.

## Tests

`TenantBindingClassificationTest` (L2 unit tier, the file that already carries one fixture per
`TenantBinding` arm) takes the goal table as cases: the four rejecting rows that become
`ArgumentBound`, the five unchanged rows as regression, and the three rows that stay rejected
asserting the rejection is still typed `Rejection.AuthorError.NoTenantBinding`. Each `ArgumentBound`
assertion names the slot and its `SlotRead`, so a nested filter-input row pins
`NestedInput(filter, [filmId])` and not merely that something bound.

Three of those fixtures carry more weight than the rest, because each is a case where a mint
predicate missing one of its clauses still produces a green build. They are the decoded-key rows, and
every one of them sits on a field that also carries a `@condition`, so the coordinate mints a
condition member and the ledger read actually fires:

- The ninth: a composite `@nodeId @lookupKey` argument whose decoded key embeds the tenant column,
  with a plain `@condition` and no `override` anywhere. It fails if the mint predicate drops its
  lookup clauses, and the absence of an override is what makes that true rather than incidental: the
  lookup clause is the sole enforcer of this row. The decode clause does not cover it, because with
  no suppression active `decodesAKey` gates nothing, so a reader who assumes the two clauses overlap
  here and simplifies one away gets a green build and row nine classifying `ArgumentBound`. The
  clauses share a *reason*, not a population.
- The tenth: the same key shape without `@lookupKey` and with no override, asserting `ArgumentBound`
  with the slot reading `TopLevelArg`. This is the preservation pin. It fails if the decode clause is
  written as a flat exclusion rather than as "honour the suppression", which would take away a slot
  the tree mints today and turn a building schema into a rejecting one. Its assertion pins the
  defective read deliberately, and the comment on it says so, so that R966's change to the read shows
  up here as a test to update rather than as a silent difference.
- The eleventh: the tenth's shape under a field-level `@condition(override: true)`, asserting the
  rejection. It fails if the decode clause is missing altogether.

All three fail in the direction a green build would not otherwise show, since a wrong answer there is
a verdict rather than an error. Without them the two exclusions are paragraphs in this plan and
nothing in the tree. The tenth and eleventh are cheapest to write over the FK-target carrier, whose
extraction is `NodeIdDecodeKeys` by declaration, but at least one of the pair uses a same-table
`@nodeId` at arity 1, which is the shape an `isComposite` discriminator would get wrong.

**The containment pin, which is the enforcer the census owes.** Today's `slotsFromFilters` is total by
accident of where it reads: it walks `gcf.bodyParams()`, so a sixth column-bound arm added to
`projectFilters` tomorrow contributes a tenant slot with no further work. The ledger inverts that.
Totality becomes a correspondence between the arms that construct a `BodyParam` and the arms that
mint a row, and an arm that emits a predicate and forgets a mint reproduces this item's own bug, a
field that names the tenant column rejected for not naming it, with nothing in the tree failing. The
five-arm census above is true as written (verified: `bodyParams` is appended at
`FieldBuilder.java:2825`, `2832`, `2852`, `2868` and, through `implicitParams`, `2954` and `3011`, and
nowhere else), but a census true when written is the "unguarded census" the enforcer principle names
as a drift smell.

So `ColumnBindingLedgerContainmentTest`, a meta-test in `graphitron`'s test tier, driven over every
fixture schema the tier already builds rather than over one fixture of its own: for each coordinate,
every surviving `BodyParam` naming a column (unwrapping `RemoteColumnPredicate`) has a ledger row
under the same slot name carrying that column. A new arm that emits without minting turns it red on
whatever existing fixture first reaches the arm.

This is not the oracle the shadow tests (`ColumnMatchShadowTest` and siblings) are, and the difference
is the one `fact-model.adoc` draws under "Name the row, not the question": what is forbidden is the
*total-agreement* test, asserting the replacement equals the predecessor, which would install
`slotsFromFilters` as normative and pin its gaps. What is asked for instead is "agreement asserted
where the two are meant to agree, and each deliberate departure asserted in the direction it was meant
to go". Containment in one direction is exactly that. It keeps no predecessor alive, since
`bodyParams()` is a live production that stays; it asserts nothing about the ledger-only direction,
which is where all three deliberate departures live (the suppressed wire-valued slots this item adds,
the lookup slots, the suppressed decoding slots); and the unchanged rows of the goal table remain the
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

**Mint the decoded-key carriers too, and table the request-time failure.** One uniform mint predicate
with no decode clause, and two goal-table rows saying those shapes move from a build error to a
request-time one until R966 widens the read. Rejected: a build error an author can see is worth more
than a request error their consumers see, the trade buys this item nothing (the reported sis field's
tenant argument is a plain `@field` scalar, so the fix lands without it), and a plan that knowingly
ships a wrong read because a sibling item will fix it is a plan that stops being reviewable on its
own terms. The decode clause costs one predicate and one sentence.

**Depend on R966 and take its widened `readOf`.** The cleanest end state: the projection axis resolves
a decoded key's tenant component, every carrier reads correctly, and the mint predicate is uniform
with no clause to retire. Rejected as a *dependency* rather than as a design, and the distinction is
the point. The two items compose in either order, so a `depends-on` would buy sequencing this item
does not need while putting eleven sis roots behind a hundred and five. What the alternative is right
about is kept: the seam says R966 retires the clause, and says what this item looks like if R966
lands first.

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
The two are independent and neither unblocks the other; the cascade this one clears is its own, the 11
roots plus every `PersonProfil` child that `Query.personProfiler` keeps red. So the ordering argument
is only that this is the smaller change, which is a reason to take it first and not a reason R966
should wait. If R966 goes first, the seam below says what this item looks like on the other side.

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
