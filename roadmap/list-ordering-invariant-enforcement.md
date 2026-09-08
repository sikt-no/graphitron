---
id: R677
title: "Derive the never-unsorted-list verdict from facts, and pin the lowering the verdict cannot see"
status: Ready
bucket: validation
priority: 3
theme: codegen-correctness
depends-on: []
created: 2026-08-14
last-updated: 2026-09-08
---

# Derive the never-unsorted-list verdict from facts, and pin the lowering the verdict cannot see

## Goal

A schema that asks graphitron for an ordering it cannot deliver stops building, instead of generating
without a word and serving rows in whatever order the database happened to return them. Graphitron
states an invariant, a list result is never unsorted, and today's two build-time checks enforce it
over a population that misses most of the ways it breaks. Three things change when this item lands.

**An ordering the coordinate cannot honour fails the build.** Today this

```graphql
type Query {
    applikasjoner(order: [ApplikasjonOrderBy] @orderBy): [Applikasjon]
        @asConnection
        @defaultOrder(fields: [{name: "NAVN"}])
}
```

generates silently and serves every page in participant primary-key order, whichever direction the
client asks for, because a field returning a multitable interface (one whose implementations each
live in their own table) is read as one statement per participant and no ordering is *lowered* onto
those statements, lowering being the step that carries a schema-level declaration down into emitted
SQL. After phase 1 that schema fails the build with a located message naming the coordinate, the
directive, and the reason. This is the reporter's own fallback ask on
https://github.com/sikt-no/graphitron/issues/523, and it is a **breaking change** for any schema that
compiles today with that declaration; see "Compatibility". The same rule sees a second shape, the
list-returning `@routine` write, where the ordering that goes undelivered is the target table's
primary-key fallback rather than anything the author wrote; that rejection is built and held, and
R660 turns it on with the fix, so no consumer meets it in this item.

**A resolved ordering the generator cannot render stops the build too**, with a generator-bug message
rather than a schema error. `CallWrap.Multiset`, the command value behind a child list read as a
correlated subquery, carries the whole resolved ordering while the renderer has a branch for one arm
of it, so a client-supplied order on an inline child list is accepted and then dropped. Phase 2 pins
those two ends against each other, and ratchets the launcher relation (the command rows saying how
each root read is launched) so the ordering projections that shipped in August cannot regress.
Nothing changes for a consumer whose schema is clean.

**The never-unsorted rule starts being keyed on the question it asks.** Phase 3 replaces the two
capability-keyed validator checks with one fact-derived verdict per list-shaped read coordinate, so
the rule stops depending on a read resolving against exactly one table. For a consumer this shows up
as coordinates that used to slip past the check now failing it, each with a message that names which
remedy applies.

Phases 1 and 2 are independent of each other and of everything else; phase 3 waits on R682. The
`In Review` transition happens per phase, per the multi-phase convention in `roadmap/workflow.adoc`.

## Why today's checks miss most of it

Two checks enforce the invariant today, `GraphitronSchemaValidator.validateListRequiresOrdering` and
its paginated sibling `validatePaginationRequiresOrdering`, and both key on the same pair of signals:
the field's resolved `OrderBySpec` landing on `None`, and the field being a member of
`SqlGeneratingField`. Most known violations produce neither signal, so the check passes and the rows
ship unsorted.

The membership half is not a forgotten declaration that a reminder would fix.
`SqlGeneratingField.returnType()` is typed `ReturnTypeRef.TableBoundReturnType`, so a read whose
target is not one table cannot implement the capability at all. The checks are keyed on "this read
resolves against one table" while the question they ask is "does this read return a list". Nothing
about a list needs one table, which is why the gap exists and why no amount of declaring membership
closes it. Whatever replaces these checks has to be keyed on the question.

## The census, and the three kinds of defect in it

Six sites are known: three closed, two live, and one found while speccing and not yet confirmed.
They do not divide the way a single enforcement mechanism would need them to, which is the reason
this item is three tracks rather than one.

* Root `@routine` chain: **closed**. R704 (`routine-composition-surface-from-facts`, Done, see
  `roadmap/changelog.md`) removed it. The escape was a carve-out rather than a capability gap: the
  leaf is a `SqlGeneratingField`, and the rule named the `RoutineResolution.Chain` arm as an explicit
  exemption. Removing the exemption closed the site, and it was the only one of the five that ever
  produced `None`.
* `@splitQuery` child list: **closed at the command tier** while this item sat in Backlog.
  `roadmap/split-query-child-list-drops-default-order.md` landed its delivery on 2026-08-31, and
  `LauncherCommands.batchedResultOf` now projects the coordinate's ordering where it passed `null`.
* `@lookupKey` child: **closed by the same delivery**. `LauncherCommands.batchedLookupRow` carries
  the ordering too; the split was agreed on that item and the rest of
  `roadmap/lookup-unrealized-co-members.md` (the inline `LookupMultiset` arm, pagination at lookup
  grain) stays open there.
* Mutation routine write path: unordered step 2
  (`roadmap/routine-write-key-capture-unordered.md`, R660). Live, and the only live instance anyone
  has found is `Mutation.rentFilm: [Rental!]!` in `graphitron-sakila-example`'s own schema.
* Inline child list carrying an `@orderBy` argument: **a sixth site, found while speccing this
  item, unfiled and unverified.** `ProjectionUnitRenderer` renders a multiset's ordering under one
  branch, `m.orderBy() instanceof OrderBySpec.Fixed`, and `CallWrap.Multiset`'s own javadoc says so
  outright ("only the `OrderBySpec.Fixed` arm renders inline"). Nothing rejects the `Argument` arm on
  an inline `ChildField.TableField`: `validateTableField` checks the lookup-connection pair only, and
  the leaf's constructor bars `Argument` on routine-node paths alone. So a client-supplied order on an
  inline child list appears to be accepted and silently ignored. Three code reads say so and no test
  does, which is exactly the confidence level a phase-2 invariant is for; see "Phase 2" for how it is
  confirmed and what happens when it fires.
* Root query over a multitable interface or union: the arm carries no ordering component at all, so
  `@orderBy` and `@defaultOrder` are accepted and discarded and rows come back in participant
  primary-key order (`roadmap/multitable-interface-query-orderby-lowering.md`).
  `QueryInterfaceField` and `QueryUnionField` carry a `PolymorphicReturnType` and declare no
  `orderBy` or `pagination` component, which is the type-level reason both checks skip them: a
  paginated multitable root with no ordering passes `validatePaginationRequiresOrdering`, the check
  written to reject exactly that shape.

Sorted by what can decide them, the sites are three different defects. Two are live, and the three
closures do not retire the classes: each closed one coordinate and left the class it belonged to
unenforced, which is the whole reason this item exists.

**Class A, an ordering is available and the coordinate's read shape cannot honour it.** Available
means either side of the resolution: an ordering the author declared, or the target table's
primary-key fallback that `OrderByResolver.resolveDefaultOrderSpec` supplies where nothing is
declared. The multitable root is this at the declaration grain: `@orderBy` and `@defaultOrder`
classify clean, generate without a diagnostic, and produce participant-primary-key order at runtime,
while `@condition` filters on the same field work. The list-returning routine write is the same
comparison with the fallback standing in for the declaration, and it is why the class is stated as
availability rather than as declaration; without the generalisation that coordinate falls between all
three classes. Both sides of the comparison are facts: what is available is captured or one join
away, and what the read shape is comes off `intent_field_scope_table` and
`intent_mutation_routine_seat`.

**Class B, no ordering is available at all.** A list-shaped read whose coordinate carries no authored
ordering and whose target offers no primary-key fallback. This is what the current checks were
written for, and it is decidable from facts too: the authored side is captured, and the fallback is a
join. A and B partition the list-shaped read coordinates between them, which is what makes the pair
exhaustive rather than two rules with a gap between them.

**Class C, an ordering resolved and the lowering discarded it.** The `@splitQuery` child and the
`@lookupKey` child were this, and both are now closed at the command tier, which leaves the class
with no live site anyone has found. **No fact at any grain can see class C**, because at the fact
tier the ordering is present. These are generator defects, not schema defects. There is no schema
for an author to fix and no source location for a message to point at.

The routine write path is **class A**, and placing it took the generalisation above. Two readings
have to be refused before the third one lands. It is not class C: `MutationField.MutationRoutineWriteField`
"carries no ordering slot, so there is nowhere for a resolved `OrderBySpec` to live even if
`@defaultOrder` were honoured", as its own item states, so nothing resolved is ever discarded and a
mechanism keyed on "an ordering resolved" cannot see the coordinate. Nor is it class B under a
narrow reading of "no ordering resolves": `Mutation.rentFilm: [Rental!]!` navigates as `Rental`,
which binds `rental`, so the coordinate takes a `NAMED_TYPE_TABLE` row in `intent_field_scope_table`
and `rental` has a primary key. An ordering is available; the write's family has nowhere to put it,
because the visible rows are step 1's captured keys re-fetched by a keyed `SELECT` that sorts by
nothing. That is class A's comparison exactly, with availability where the declaration usually
stands, and it is why phase 3's population would admit the coordinate and then verdict it `ORDERED`.

The worst case already recorded on the `@splitQuery` item is what happens when A and C meet: for a
view-backed target with no primary key, the deterministic-order validator *compels* `@defaultOrder`
and emit then discards it. The build makes a class-A demand and commits a class-C violation in the
same run. Naming the two separately is what makes that combination stateable, and refusing it is a
requirement on whatever ships.

## Why the source is facts, and not the launcher relation

An earlier framing of this item proposed re-sourcing the rule off the launcher relation's ordering
slot, on the reasoning that `ResultShape.RecordList` with an absent `Ordering` makes the whole
population visible in one place. That anchor fails three independent tests, and the third is the one
the earlier framing had already half-found on its own.

**Pipeline position.** `GraphQLRewriteGenerator.runPipeline` pronounces the verdict and throws before
it calls `EmitPlan.produce`. The launcher relation does not exist until the build has already decided
the schema is valid. Worse, the plan never runs on the other two entry points at all: `validate()`
and `buildOutput()` are both load, classify, capture, validate, with no plan anywhere. A rule sourced
off commands would be absent from `graphitron:validate` and invisible in the editor. A store-derived
rule reaches all three, because the capture window runs ahead of the verdict on every path
(`FactCapture.detect` inside `GraphQLRewriteGenerator.captureAndRead`, whose continuation runs the
validator and fuses `StoreDetections.violations()` into the error stream) and the `diagnostic` view
already carries `intent_authored_claim_conflict` as a view arm beside the walk's own errors.

**Tier direction.** `PackageImportDirectionTest` pins `facts` below `command` below `plan` below
`render`, stating on the facts leg that "facts sit below commands; the corpus will read facts without
a plan". The validator lives in `no.sikt.graphitron.rewrite`. Making it read commands adds a consumer
of commands that is neither a planner nor a renderer, which is the seam
`roadmap/planners-read-facts-emitters-read-commands.md` exists to close. That item's closer names two
import dials, `plan` and `render`; a build-time rejection sourced from commands would need a third.

**Population.** The launcher relation excludes the multitable root by the same keying the current
checks use one tier down: `LauncherCommands.verdictOf` anchors on the target-axis fact
`TargetShape.Table`, and the multitable family carries `Interface` or `Union`, so it takes no launcher
row and its UNION-ALL stage belongs to the polymorphic-emit family. Re-sourcing off the launcher
relation would leave the site exactly as invisible as it is today while the framing read as having
closed it.

At the fact grain there is no carve-out to be outside of, because there is no capability interface.
Every ingredient is already captured: `graphql_field.is_list` for list-shapedness,
`graphitron_default_order_entry` and `graphitron_default_order_field_entry` and
`graphitron_order_by_entry` for the authored ordering, `intent_bound_table` joined to
`sql_primary_key` for the fallback, `intent_field_chain_terminus` for the routine terminus that has
no primary key to fall back on, and `intent_mutation_routine_seat` for the write shapes, whose
population is every mutation-root field carrying `@routine` and whose `verdict` column already sorts
its values into the ones an author fixes and the ones the generator owes an emitter. The polymorphic
root is an ordinary row of every one of those relations.

## Absence is not the complement's claim

The store forbids the shape a "population observable in one place" framing invites.
`intent_field_separate_fetch`'s relation comment ends: "A reader may say a field with a row is
separately fetched, and may not say a field without one is inlined." The never-unsorted invariant is
an absence check, so building class B as a `NOT EXISTS` against an ordering fact table would violate
that rule directly.

Class B has to be a positive population carrying a verdict, on the `intent_resolved_field_demand`
model: rows are the list-shaped read coordinates of the classification domain, one verdict per row
from a closed vocabulary, and a coverage gate counting resolved rows against the population so the
construction stays honest. That view is the item's one piece of genuine modelling work; everything
else it needs is already derived.

## Sequencing: three tracks, two of which can start now

Deliberately no `depends-on`. The three classes sequence differently and only one of them waits.

* **Class A ships immediately and independently.** It turns a silent wrong answer into a build error
  at coordinates where nothing is going to lower the ordering soon, and it is what unblocks the
  consumer who reported it. Its home is a store-derived rule in
  `graphitron-model/src/main/java/no/sikt/graphitron/model/derive/` with `AuthoredClaimConflicts` as
  the precedent, plus a `diagnostic` view arm.
* **Class C also ships immediately.** The launcher relation and `Ordering` exist today, and
  `Ordering.Columns` already rejects an empty spec at construction ("an empty fixed order is
  unordered; model it as an absent Ordering, not an empty Columns arm"), so the vocabulary is
  already shaped for the assertion. Its home is a production fold in `LauncherCommands` with a
  pipeline-tier test beside `LauncherRelationClosureTest`, which already reads `plan().launchers()`
  off the carried plan rather than re-deriving it. Two halves that do different work: over the
  launcher relation the fold is a ratchet holding a closed population fixed, and over the projection
  relation's multiset arms it is the two-ends comparison, because that is the one command family
  where the two ends can still diverge. Not a `ValidationError`.
* **Class B lands after the launcher step of
  `roadmap/planners-read-facts-emitters-read-commands.md`**, or alongside it. Deciding "does an
  ordering resolve for this coordinate" from facts is the same derivation `LauncherCommands` performs
  today and that item moves store-side; doing it twice from two sources is how the two ends drift.
  Whether class B can go green before every per-site fix lands, or needs a temporary exemption list,
  is a Spec question. An exemption list is acceptable only if each entry names the item that removes
  it.

Class C is not blocked on class B and should not be sequenced behind it. The two read different
things: class C compares a resolved ordering against the command row that was supposed to carry it,
which is available now; class B asks whether an ordering is available at all, which is the fact-tier
question.

The three tracks are phases 1 to 3 below, in that order.

## Notes the plan holds to

Each one is a constraint the phases below are built against.


- **The three classes are three mechanisms and must not be merged.** The earlier framing's whole
  error was treating class B and class C as one re-sourcing. They read different signals, at
  different tiers, with different severities and different audiences: A and B are author-facing
  rejections with a coordinate and a location, C is an internal invariant whose failure is a bug
  report against the generator.
- **Class A is the same shape as the fan-out verdict** in `roadmap/reference-path-fanout-verdict.md`:
  a build-time verdict comparing what is available against what the pipeline can honour, decidable
  from captured facts rather than from a traversal. One difference to keep straight: the fan-out
  verdict is advisory, because a multiset may be what the author wanted, while this one is a hard
  rejection, because nothing can honour the ordering. Same derivation, different severity.
- **The polymorphic-emit family has no owner on either side of this item.** Site 5 lives in
  `MultiTablePolymorphicEmitter`, which `roadmap/planners-read-facts-emitters-read-commands.md`
  names as its large zero-dispatch leaf reader and does not give a command relation to in its
  family-by-family order. So class A covers the *declaration* at that coordinate and nothing yet
  owns the *lowering*. Whoever specs this should not assume that item will mint the relation; if the
  lowering matters on a schedule, say so on
  `roadmap/multitable-interface-query-orderby-lowering.md` rather than inheriting it here.
- `docs/manual` should say what the invariant guarantees and where it does not hold yet. Today the
  Sorting and polymorphic-query pages state no limitation, which is how the consumer arrived at a
  runtime surprise.
- One statement per grain applies to the class B view as it does to every store read: the population
  is derived by one statement over the whole schema, never a query per coordinate. The rule and its
  instrument live on `roadmap/planners-read-facts-emitters-read-commands.md`.

## Phase 1, class A: reject an available ordering the coordinate cannot lower

Copies `AuthoredClaimConflicts` outright, which is the precedent this item's notes already name: the
reduction lives in a view's SQL, the Java member decodes the view's closed vocabulary into located
`ValidationError` values, and a capture-cadence writer stores the minted rejection so the editor's
`diagnostic` view reads it as a plain column.

### The population, in facts

Two read shapes, each a fact the store already states, and one availability side joined against both.

**The multitable read.** The coordinate is one whose read is several statements rather than one, and
`intent_field_scope_table.basis` carries `PARTICIPANT_TABLE` for exactly this shape; its own column
comment says so, "PARTICIPANT_TABLE being exactly where the coordinate is several statements rather
than one". No new capture, and no restatement of the polymorphic recognition, whose own precondition
(the container binds no table, each participant binds one) lives in
`intent_field_participant_scope_table`. What such a read delivers is participant primary-key order,
so only a declared ordering is unhonoured here.

**The list-returning routine write.** `intent_mutation_routine_seat` is total over the mutation-root
fields carrying `@routine` and carries exactly one verdict each, so the arm is that relation at
`verdict = 'ADMITTED'` joined to `graphql_field.is_list`, which in practice selects the chain seat, a
carrier seat returning its payload object rather than a list. An admitted write emits, and
what it emits is step 1's key capture followed by a keyed re-fetch that sorts by nothing, so this
read shape delivers no order at all and any available ordering is unhonoured. The relation's own
`verdict` vocabulary already refuses the neighbouring shapes for the neighbouring reasons, which is
why this arm reads it rather than re-deriving the write recognition: `READ_SURFACE_ON_WRITE` refuses
a coordinate carrying `@orderBy` or `@condition` because "neither write seat has a filter or an
ordering to resolve them against", and `CONNECTION_RETURN` refuses `@asConnection`. Its predicate
reads `graphitron_order_by_entry` and not `graphitron_default_order_entry`, so a `@defaultOrder` on a routine
write is admitted today and is one of the two things this arm catches.

The availability side is three relations, all captured or one join away:

* `graphitron_default_order_entry` at the coordinate, with `graphitron_default_order_field_entry` for the entries
  and the directive's own `source_name` / `source_line` / `source_column` for the location.
* `graphitron_order_by_entry` on any argument of the coordinate, with the argument's own position.
* `intent_bound_table` joined to `sql_primary_key` for the fallback
  `OrderByResolver.resolveDefaultOrderSpec` supplies where nothing is declared. This arm is what
  makes the rule availability-keyed rather than declaration-keyed, and it is inert on the multitable
  read, where the fallback is what the emitter already delivers.

All of it is joined under `intent_type_domain` on the owning type, exactly as `AuthoredClaimConflicts`
narrows its build-error population: only a coordinate the generator intends to classify can fail a
build. The editor arm reads the view ungated, on the same reasoning that relation's comment gives.

### What the rule compares, and why it is not an unsortedness rule

Worth stating because it decides the message and the vocabulary. The comparison is between the
ordering **available** at the coordinate and the ordering the coordinate's read shape **delivers**,
and a row is minted where the available one is not the delivered one. Both sides are facts and
neither is a fact about a command family's slots.

That is what keeps the two arms honest in opposite directions. A multitable root with no declaration
is **not** a violation: the polymorphic emitter projects a synthetic `__sort__` per branch and orders
on it, so the rows come back in participant primary-key order, deterministically, and what is
available is exactly what is delivered. The invariant "a list is never unsorted" holds there, and
what fails is the author's declaration, which is accepted and then not lowered. A list-returning
routine write with no declaration **is** a violation, because the same fallback is available and
nothing delivers it.

It is also why the rule cannot be folded into phase 3's verdict view. That view answers "is an
ordering available", and at both of these coordinates one is; the question phase 1 asks is a second
one the view does not put.

### The two relations

* `intent_field_unlowerable_ordering (graph_name, type_name, field_name, verdict, available_via,
  argument_name, source_name, source_line, source_column)`. One row per coordinate and availability
  route whose ordering the coordinate's own read shape cannot honour. `verdict` is the closed pair
  `PARTICIPANT_FAN_OUT` and `KEY_CAPTURE_SCATTER`, one per read-shape arm above, and the two are
  disjoint by construction: a mutation-root routine write takes no `PARTICIPANT_TABLE` row.
  `available_via` is the closed triple `DEFAULT_ORDER` / `ORDER_BY_ARGUMENT` / `PRIMARY_KEY_FALLBACK`,
  and a coordinate with two routes is two rows: the arity is the answer, as on every rule view in the
  family, and each remedy names one route. `argument_name` carries the declaring argument on the
  `ORDER_BY_ARGUMENT` arm and is NULL on the other two, and it is in the key, because
  `graphitron_order_by_entry` is keyed at argument grain and two `@orderBy` arguments on one coordinate
  would otherwise collide on a row. Unreachable today, `OrderByResolver.resolve` taking the first
  `ArgumentRef.OrderByArg` it finds, but a view key is not the place to inherit a consumer's
  precedence. The location columns are the declaring directive's own where there is one, so the editor
  underlines the directive rather than the field; on `PRIMARY_KEY_FALLBACK` there is no directive to
  point at, so they carry the field's own position.
* `intent_field_unlowerable_ordering_rejection (graph_name, ordinal, type_name, field_name, kind,
  variant, message)`, on `intent_authored_claim_rejection`'s shape and for its stated reason: a table
  because the message is a render no view over this store can state, written by a capture-cadence
  writer that clears its graph partition and re-mints after every flush.

Both carry a `COMMENT ON` per relation and per column; `FactSchemaGateTest` fails the build otherwise,
and the `intent_` prefix houses them in the family census with no `meta_` edit needed.

### The Java side

Main sources land in `graphitron-model/src/main/java/no/sikt/graphitron/model/derive/`, beside
`AuthoredClaimConflicts`, `AuthoredClaimRejectionRows` and `ArgmappingProjectionDefects`. That is
forced rather than chosen: the same bullets below make the family a component of `StoreDetections`
and a call in `FactCapture.detect`, both of which live in `graphitron-model`, and `graphitron`
depends on `graphitron-model` one way.

* `UnlowerableOrderings.java`, structured like `AuthoredClaimConflicts`: a `Detection` record with
  `violations()`, a typed per-coordinate verdict beside it, and one `rejectionOf` that is the single
  mint of the `Rejection` value, shared with the rows writer so the report and the store cannot spell
  one violation two ways.
* `UnlowerableOrderingRejectionRows.java`, the writer, on `AuthoredClaimRejectionRows`'s cadence.
* `StoreDetections` gains the family as a component, and `FactCapture.detect` gains the call. Both are
  one-line joins by construction; that is what that record's javadoc says the shape is for.
* A `diagnostic` view arm, joining the defect view to the rejection rows on the coordinate, matching
  the claim-conflict arm column for column.

The two test paths are unaffected by the placement: the view's own test is a `graphitron-model` unit
test and the decode's test is a `graphitron` one, which is where `ArgmappingProjectionDefectTest` and
`ArgmappingProjectionDefectsTest` already sit for the same split.

### The rejection arm, the message, and the one verdict that is held

`Rejection.deferred(summary)`, not `structural` or `invalidSchema`. The `diagnostic` view derives
`actionable` as `kind <> 'DEFERRED'` and documents the `FALSE` case as "recognised but not yet
generator-supported, a workaround rather than a schema fix", which is this violation exactly: the
schema is well formed, the directive is real, and the remedy is to drop the declaration or wait for
the lowering. A deferred rejection still fails the build, so the outcome the reporter asked for is
unchanged; what the arm buys is that an editor triages it correctly and that the row leaves the
population the moment the lowering lands.

The message states the fact, the reason and both remedies, and names no roadmap item (a generated
message may not carry an `R<n>`, per `CLAUDE.md`; `RoadmapReferenceGuardTest`'s string-literal scan
fails the build on one):

> Field 'Query.applikasjoner': `@defaultOrder` declares an ordering this field cannot honour. A
> field returning the multitable interface 'Applikasjon' is read as one statement per participant
> (Applikasjon1, Applikasjon2, Applikasjon3) and the results are combined on a synthetic key, so the
> declared columns are not applied and rows arrive in participant primary-key order. Remove the
> declaration, or return a single `@table` type.

The participant list comes off the view's own join rather than a string built in SQL, on
`AuthoredClaimConflicts`'s division of labour.

**`KEY_CAPTURE_SCATTER` mints its row and holds its rejection.** The decode is a total switch over
`verdict` with no `default`; the `PARTICIPANT_FAN_OUT` arm mints the `ValidationError` above and the
`KEY_CAPTURE_SCATTER` arm mints none for now. The reason is a measurement rather than a preference:
the only live instance is `Mutation.rentFilm: [Rental!]!` in `graphitron-sakila-example`'s own
schema, so turning the rejection on reddens this reactor's verification build until R660
(`roadmap/routine-write-key-capture-unordered.md`) gives that coordinate an order to deliver. Per
this item's own rule an exemption is acceptable only where its entry names what removes it, and this
one does; the entry in code names R660's mechanism (the write's step-2 re-fetch carrying an order)
rather than its id, because `RoadmapReferenceGuardTest` bars the id from a comment.

What the held arm still delivers is not nothing, and it is why the arm ships here rather than waiting
for R660 to invent it. The view row is the population count and the pinned taxonomy: the coordinate
sits in a cell that can reject it, so the next reviewer who meets a list-returning routine write does
not have to re-run the class-B-or-class-C argument. `FieldUnlowerableOrderingTest` asserts the row
exists at that coordinate, so a lowering that lands upstream shows up as a failing test rather than
as a quietly empty population. R660's delivery is then one arm flip plus its own message, on the
shape this phase leaves it:

> Field 'Mutation.rentFilm': this write returns a list and delivers it in no defined order. The
> routine's returned keys are captured and the rows re-read by key, and neither step sorts, so
> 'rental''s primary key orders nothing here even though it is available. Return a single object
> rather than a list.

## Phase 2, class C: pin the multiset's two ends, and ratchet the launcher relation

The comparison all three predecessor items were reaching for, sited where the two ends can still
diverge. It needs no new source and no new tier, and it ships now.

**Where it lives.** A fold over the finished relations inside `LauncherCommands`, throwing
`IllegalStateException` with the "Graphitron generator bug (...)" prose the tree already uses for a
shape the emitter cannot honour (see the batched-lookup throw in the same class, whose comment says
"Failing at production keeps the gap loud until a single-shaped lookup emission or a validator
rejection lands"). Production and not a test alone, because the track's value is the sites nobody has
found and a test over our fixture corpus finds the sites our fixtures exercise, while a production
invariant runs on every consumer's schema. The pipeline-tier test beside `LauncherRelationClosureTest`
still ships, pinning the invariant in both directions, but it is the second artifact rather than the
mechanism.

The fold has two halves and they are not the same kind of check. Saying which is which is what keeps
the phase honest about what it buys.

**The multiset half is the two-ends comparison, and it is why the phase ships.** `CallWrap.Multiset`
carries the whole `OrderBySpec` and `ProjectionUnitRenderer` renders one arm of it, the `Fixed` arm,
which `CallWrap.Multiset`'s own javadoc states outright. Nothing derives the command from the
renderer's capability, so the model can resolve an ordering into a multiset that will never render
it, and neither end knows. The assertion that fits that shape is arm-shaped rather than
slot-presence-shaped: a list-cardinality multiset may not carry an `OrderBySpec.Argument`, because no
site lowers one.

This is also what confirms or refutes the sixth census site. The confirmation recipe, to be run
**before** the invariant is written, so the invariant is known to be able to fail: add
`filmsOrderedInline(order: [FilmOrderBy] @orderBy): [Film!]!` as an inline child list on an existing
sakila example type (no `@splitQuery`, no `@asConnection`), generate, and read the emitted projection.
If the multiset carries no `orderBy`, the site is real and the phase owes it two things: a filed item
for the lowering (or the rejection, if the render side is not worth building), and an exemption entry
pointing at it. Per this item's own rule an exemption is acceptable only where its entry names what
removes it, and in code that is the mechanism rather than the id, `RoadmapReferenceGuardTest` barring
the id from a comment; this body records the pairing. If the emission turns out to reject or to lower
it, the census bullet gets struck and this half becomes a ratchet like the other one.

**The launcher half is a ratchet over a closed population.** One assertion, not two: every row of the
launcher relation whose `ResultShape` is `RecordList` and whose `LaunchSource` is not an exempt arm
carries a present `Ordering`.

It is stated as one assertion because the second one has nothing to compare. On every non-exempt arm
the slot is computed by `LauncherCommands.orderingOf` directly off the leaf's own `OrderBySpec`, and a
leaf resolving `OrderBySpec.None` never reaches the plan at all, `validateListRequiresOrdering`
rejecting it first, so an assertion that read the leaf and demanded a slot would be asserting a total
function against its own output. On the exempt arms it would be worse than redundant: a root
`@lookupKey` list resolves a non-empty `OrderBySpec.Fixed`, `OrderByResolver.resolveDefaultOrderSpec`
falling back to the target table's primary key, while `LauncherCommands.lookupRow` builds
`new ResultShape.RecordList(null)` unconditionally, so `filmById(film_id: [ID] @lookupKey): [Film]!`
in the sakila example would throw on the verification build. The exemption gates the whole assertion,
and the four absent-slot sites in the relation today are exactly the four arms the exemption list
names, so there is no launcher-family divergence left to find. What the ratchet buys is that the
population stays closed: a new source arm, or an existing arm that stops projecting the ordering it
projects today, fails here.

The exemption set is a total switch over `LaunchSource`, no `default`, so a new source arm is a
compile-time decision by whoever adds it rather than a silent admission. Today's exempt arms and their
reasons, each of which is already written down on the shape it belongs to:

* `KeyedLookup`: input order is carried by the scatter onto the keys' slots, so there is no order to
  sort by. `ResultShape.RecordList`'s own javadoc states this.
* `ProjectedReentry` and the discriminated reentry source: the `ORDER BY idx` scatter re-keys the
  re-projected rows to the upstream source order. Sound where that upstream order is itself defined,
  which for a DML write's returned keys it is. It is **not** sound for a routine write, and that is
  precisely the premise failure `roadmap/routine-write-key-capture-unordered.md` records against
  `requiresReFetch()`; the exemption entry says so in its comment. The chain seat of that shape is
  phase 1's `KEY_CAPTURE_SCATTER` verdict; the carrier's data field is R660's own, by narrowing
  `requiresReFetch()` to re-fetches whose upstream order is defined.
* The schema-free unit-tier assemblies, which carry no coordinate to read a leaf for.

`ResultShape.Connection` needs no arm: its constructor already requires a non-null `Ordering`.

**Why not a `ValidationError`.** A dropped ordering is not a schema defect. There is no coordinate for
the author to fix, and a message pointing at their `@defaultOrder` would be pointing at the one thing
they did right. The audience is whoever is changing the generator, and the register is the throw.

## Phase 3, class B: the ordering-availability view

The one piece of genuine modelling work, and the only track that waits. It answers one question, "is
an ordering available at this list-shaped read coordinate", over a population that needs no capability
membership; whether an available ordering is then honoured is phase 1's question and not this one's.

**Shape.** A positive population carrying a verdict, on `intent_resolved_field_demand`'s model, because
absence is not the complement's claim (see the section of that name above):

* `intent_field_ordering_rule (graph_name, type_name, field_name, rule)`, one literal per arm, arms
  unmasked against each other: `DEFAULT_ORDER` where the coordinate carries `graphitron_default_order_entry`,
  `ORDER_BY_ARGUMENT` where an argument carries `graphitron_order_by_entry`, `PRIMARY_KEY_FALLBACK` where the
  read's target table has a `sql_primary_key`, `PARTICIPANT_KEY` where the read fans out per
  participant and the synthetic key orders it, `INPUT_SCATTER` where the visible order is the input
  order (the keyed shapes phase 2 exempts, stated here as a positive rule rather than an absence).
  `INPUT_SCATTER` carries the qualification `requiresReFetch()` lacks and that
  `roadmap/routine-write-key-capture-unordered.md` records against it: the rule holds only where the
  upstream order it scatters onto is itself defined, so a scatter onto a routine's unordered key
  capture is not an arm of it.
* `intent_resolved_field_ordering (graph_name, type_name, field_name, verdict, rule)`, the reduction:
  `ORDERED` with the winning rule in declared precedence order, or `UNORDERED` over the list-shaped read
  coordinates no rule covers. That second population is the rejection.
* A coverage gate counting resolved rows against the population, on the shadow agreement's terms, so
  the construction stays honest rather than quietly shrinking to what the rules happen to answer.

The population is "list-shaped read coordinate": `graphql_field.is_list`, in `intent_type_domain`, with
a row in `intent_field_scope_table` (any basis) or a routine terminus in
`intent_field_chain_terminus`. Note what that population does **not** need: membership in a capability
interface. That is the whole point of the track: `SqlGeneratingField` stops being the gate, so a
list-shaped read that resolves against no single table is inside the rule rather than invisible to it.

What the wider population does not buy is the routine write path, and the plan says so here rather
than letting the phase's test list imply otherwise. `Mutation.rentFilm` takes a `NAMED_TYPE_TABLE` row
on `rental`, `rental` has a primary key, so `PRIMARY_KEY_FALLBACK` fires and the reduction reads
`ORDERED`. That is the correct answer to the question this view asks; the coordinate's defect is that
the available ordering is not delivered, which is phase 1's `KEY_CAPTURE_SCATTER` verdict.

**The dependency, and the reopen trigger.** R682 (`planners-read-facts-emitters-read-commands`, In
Progress) moves the launcher relation's derivation store-side. Deciding "does an ordering resolve for
this coordinate" is the same derivation `LauncherCommands` performs today, and doing it twice from two
sources is how the two ends drift. So phase 3 does not start until R682's launcher step has landed, and
if that step lands the ordering derivation under a different name or grain than this section assumes,
**this item returns to `Spec`** before phase 3 is implemented rather than being reconciled in flight.
Phases 1 and 2 are unaffected by that trigger: neither reads a launcher fact.

**What phase 3 retires.** `validateListRequiresOrdering`, `validatePaginationRequiresOrdering` and
`listOrderingDiagnostic` are replaced, not kept beside the view: two rules with two populations is the
drift this item was filed against. The `requiresReFetch()` exemption goes with them, replaced by the
`INPUT_SCATTER` rule, which is the same carve-out stated positively and per coordinate rather than per
capability.

**Whether it can go green.** Open, and honestly so: it depends on how many coordinates the wider
population turns out to catch, which is measurable only once the view exists. An exemption list is
acceptable if each entry names the item that removes it, and unacceptable otherwise. Take the count
before writing the rejection, on the sakila example schema and on the fixture corpus.

## Tests

Per phase, and each named test is what answers "how do we know the item is complete".

**Phase 1.**

* `graphitron-model/src/test/java/no/sikt/graphitron/model/intent/FieldUnlowerableOrderingTest.java`,
  the view's own unit-tier test over a seeded store, on `ArgmappingProjectionDefectTest`'s pattern:
  one case per `available_via` arm, the both-directives coordinate yielding two rows, one case per
  `verdict`, and the boundaries, each of which is an absence some other surface owns. A
  single-`@table` field with `@defaultOrder` is quiet. A multitable field with no declaration is
  quiet, which is the "what the rule compares" section asserted as a property: what is available
  there is what is delivered. A single-table discriminated interface (`@discriminate` on the
  container) is quiet, because it lowers ordering today and the two shapes are one join apart. A
  non-list routine write is quiet, and a mutation-root `@routine` the seat relation refuses is quiet,
  since a coordinate that does not emit cannot deliver a wrong order.
* `graphitron/src/test/java/no/sikt/graphitron/rewrite/derive/UnlowerableOrderingsTest.java`, the
  decode: the located `ValidationError` for the reported shape, message text and location asserted
  against the declaring directive's own position and not the field's; the `PRIMARY_KEY_FALLBACK` arm
  locating on the field, there being no directive to point at; the `KEY_CAPTURE_SCATTER` arm minting
  a view row and no `ValidationError` while the rejection is held, which is the assertion R660 flips;
  plus the domain narrowing (a violating coordinate on a type outside `intent_type_domain` mints no
  build error while the view keeps its row).
* One pipeline-tier case asserting the build actually fails on the reported schema, and one asserting
  the store row reaches the `diagnostic` view with `actionable = FALSE`. The second is what the editor
  reads; without it the phase ships a build error and an editor that stays silent.
* The reported schema is the fixture: a three-implementation multitable interface, `@asConnection`, a
  field-level `@defaultOrder(fields:)`, and an `@orderBy` argument, since that combination is what
  arrived from the field and each half fails on its own.

**Phase 2.**

* The invariant must be shown able to fail before it is trusted: revert one of the two ordering
  projections that shipped on 2026-08-31 in the working tree, confirm the fold throws at the right
  coordinate, restore it. Record that in the delivery commit rather than as a checked-in test.
* `LauncherOrderingClosureTest` (pipeline tier, beside `LauncherRelationClosureTest`, reading
  `plan().launchers()` off the carried plan and never a re-derivation): every list-shaped row whose
  `LaunchSource` is not an exempt arm carries a present `Ordering`. The exemption set is read off the
  production switch, never restated in the test, on `LauncherRelationClosureTest`'s own rule about
  reading the producer's declared membership data.
* A membership pin over the exempt arms: the arms with an absent slot in the corpus are exactly the
  arms the switch exempts, so an arm that quietly stops projecting its ordering fails here rather
  than being absorbed by an exemption written for a different shape. It pins arm membership and not
  the leaf's spec, a keyed lookup's leaf resolving a primary-key `Fixed` that the launcher row is
  right to drop.
* The multiset half: an assertion that no list-cardinality `CallWrap.Multiset` carries an
  `OrderBySpec.Argument`, and, once the confirmation recipe has run, a case at the shape it turned
  up, which is the one place in this phase where the two ends can actually disagree.

**Phase 3.**

* The view's unit-tier test, one case per rule arm plus the `UNORDERED` reduction, and the coverage
  gate as its own assertion.
* The coordinates today's checks cannot see, each as a case that fails before the phase and passes
  after. Which shapes those are comes out of the count below rather than being named here: the
  population the phase adds is the list-shaped reads outside `SqlGeneratingField`, and which of them
  have no ordering available at all is exactly what nobody has measured. The list-returning routine
  write is not among them, phase 1 owning it, and neither is the undeclared multitable root, which
  `PARTICIPANT_KEY` verdicts `ORDERED` and correctly so.
* Every existing case in `ListRequiresOrderingValidationTest` re-pointed at the new rule, with the
  message changes recorded. That file is the regression surface for the rule being replaced, and it
  is where a reviewer checks that the replacement covers what it replaced.

## User documentation (first-client check)

Phase 1 has a user-visible surface (a new build rejection), so the docs draft is part of the design.
Two pages, and one of them is a correction rather than an addition.

**`docs/manual/how-to/sort-results.adoc`, "Sort across polymorphism".** The section as written
describes ordering across a multitable union as working and warns about a hazard that cannot arise:
"every participating table must agree on the order's shape", and "mixing a `(LAST_NAME, FIRST_NAME)`
ordering for `Customer` with a `(FIRST_NAME, LAST_NAME)` ordering for `Staff` does not compose". No
declared ordering is lowered onto a multitable read at all, so a reader following this section writes a
schema that silently ignores their order. The paragraph is rewritten to say what is true: the emitter
sorts on a synthetic key built from each participant's primary key, a declared ordering is not lowered,
and as of phase 1 declaring one fails the build. The single-table paragraph beneath it is accurate and
stays.

**`docs/manual/how-to/polymorphic-types.adoc`, "Constraints".** One bullet, in the register the
existing bullets use ("... is rejected at build time as a deferred capability" is already the house
phrasing there).

Draft, for the sort page:

> === Sort across polymorphism
>
> A field returning a multi-table polymorphic interface or union is read as one statement per
> participant, combined with `UNION ALL`. The emitter orders the combined result on a synthetic
> `__sort__` column projected per branch from that participant's primary key (typed as JSONB for
> composite keys, so PostgreSQL's lexicographic ordering reproduces the multi-column order). That
> ordering is not configurable: `@defaultOrder` and `@orderBy` are not lowered onto the participant
> branches, and declaring either on such a field fails the build rather than being ignored. Sort a
> single-`@table` field instead, or narrow the field's return type to one participant.
>
> For single-table polymorphism, ordering is unchanged from the non-polymorphic case: the
> discriminator column is just another projected column, and the sort spec applies to the shared
> backing table.

Phase 3 adds no page: it changes which coordinates the existing deterministic-order rule catches, and
`sort-results.adoc`'s "Constraints and pitfalls" list already states the rule. Re-read that list at
phase 3 and correct any bullet the new population makes wrong. Phase 2 has no user surface, and
neither does phase 1's held `KEY_CAPTURE_SCATTER` verdict; the page that shape needs (the routine
write's list return and what it does or does not guarantee about order) belongs with R660's delivery,
which is what decides the sentence.

## Compatibility

Phase 1 breaks builds that pass today. That is the intent, and it is what the reporting consumer asked
for, but it is a real upgrade cost for anyone who has `@defaultOrder` on a multitable field and has not
noticed it does nothing. Three consequences for the delivery:

* The `changelog.md` entry says so explicitly, in the "what a consumer has to do" register rather than
  as a feature note.
* The message must carry the remedy, not just the refusal. An author who hits this needs to know that
  removing the declaration loses them nothing they currently have.
* When `roadmap/multitable-interface-query-orderby-lowering.md` lands the lowering, the rejection's
  population empties on its own: the rule keys on the coordinate's read shape, so nothing has to be
  un-written. Say that on that item, so its implementer knows the rejection is theirs to retire.
* The `KEY_CAPTURE_SCATTER` verdict adds no upgrade cost while its rejection is held, and the whole
  of it when R660 flips the arm: any schema with a list-returning `@routine` write then stops
  building unless R660's fix gives that shape an order to deliver. Say that on R660, so its
  implementer picks between the two deliberately rather than meeting it in a failing build.

## Cost

One query per build for phase 1, over relations that are already registered or cheap
(`intent_field_scope_table` is a table with a coordinate index, the directive relations are captured
tables). `intent_mutation_routine_seat` is the one unknown in it, being a reduction over half a dozen
sibling relations rather than a table; take its contribution separately, so a cost that turns out to
sit in the write arm can be answered by keying that arm off `graphitron_routine_entry` and the seat
relation's verdict alone. Take the number before wiring it in, per `DerivedReadCostTest`'s discipline, and do
not register the view: one reader, so a registration would pay a refresh to save an evaluation. Phase
2 is one pass over rows already in memory. Phase 3's cost is unknown until the population exists and
is the phase's own measurement to take.

## Retired vocabulary

Phase 3 only; phases 1 and 2 retire nothing. Phase 1's own `KEY_CAPTURE_SCATTER` verdict retires with
its population if R660 lands the lowering rather than the rejection, and that retirement is R660's to
declare, not this item's.

* `validateListRequiresOrdering`
* `validatePaginationRequiresOrdering`
* `listOrderingDiagnostic`
* `routineResultTerminusOf`
* "paginated fields must have ordering" (the message fragment)
* "list fields must have a deterministic order" (the message fragment, both arms)

## Out of scope

* **Lowering an ordering onto a multitable read.** That is
  `roadmap/multitable-interface-query-orderby-lowering.md`, and phase 1 is the fallback its own field
  report asks for while it waits. Phase 1 must not grow into a partial lowering.
* **The routine write path's fix.** `roadmap/routine-write-key-capture-unordered.md` owns the seat
  question (mutation field or payload data field) and the `requiresReFetch()` census. Phase 1 gives
  the coordinate a cell that can reject it and holds the rejection; it does not decide where the
  order surface goes, and it does not choose between fixing the shape and refusing it.
* **The rest of the lookup census.** `roadmap/lookup-unrealized-co-members.md` keeps the inline
  `LookupMultiset` arm, pagination at lookup grain, and the one-record-per-key production throw.
* **The polymorphic-emit family's command relation.** Nothing owns the lowering at that coordinate on
  either side of this item, and this item does not mint the relation. If the lowering matters on a
  schedule, that belongs on R382's file.
* **A lint-severity variant.** Class A is a hard rejection because nothing can honour the declaration.
  The advisory register belongs to `roadmap/reference-path-fanout-verdict.md`, whose finding is
  advisory because a multiset may be what the author wanted.

## Why this is its own item

Three items independently reached the class and none carried it. The routine chain item stated it as
"an ordering the model resolved does not reach the emitted SQL, and no check compares the two ends",
noted that its own fix cannot make the invariant true, and said the shared enforcement question
should be "its own item rather than as a rider on either". The `@splitQuery` item repeated it. A
third (`roadmap/root-family-validator-mirror-gaps.md`) proposed a re-sourcing scoped to the
routine-chain membership gap rather than to the invariant, and that bullet has since been closed by
removing the carve-out, which left the invariant exactly where it was.

Note what all three were reaching for: a check that compares two ends. That is class C, and it is the
half none of them could site because the validator is the wrong place to stand. Splitting the class
three ways is what gives each half a home, and two of the three homes already exist. Siting class C
took one more step than splitting it: the comparison only earns its name where the two ends are
derived independently, which in today's command tier is the multiset alone.

## Relationship to other items

* `roadmap/planners-read-facts-emitters-read-commands.md` owns the launcher relation's move onto the
  store, which is what class B reads. It is also where the rule that a build-time rejection is not
  sourced from commands belongs, so the next item in this one's position does not re-run the
  argument.
* `roadmap/reference-path-fanout-verdict.md` is the shape class A copies, at a different severity.
* `roadmap/routine-write-key-capture-unordered.md` (R660) owns the list-returning routine write. This
  item classifies that coordinate and builds the cell that rejects it; R660 decides between lowering
  an order onto the write and turning the held rejection on, and flips the decode arm either way.
  Nothing here waits on R660: phase 1 ships its view row, its test and its `PARTICIPANT_FAN_OUT`
  rejection whatever R660 does.
* `roadmap/consumers-share-relations-not-queries.md` binds the class B view: it lands in the store at
  its own grain, and the launcher producer reads the same relation rather than a query shared with
  the rule.
* The four per-site items (`roadmap/split-query-child-list-drops-default-order.md`,
  `roadmap/lookup-unrealized-co-members.md`, `roadmap/routine-write-key-capture-unordered.md`,
  `roadmap/multitable-interface-query-orderby-lowering.md`) fix the sites. Class C is what keeps them
  fixed; class A and class B are what find the next one.
* `roadmap/split-query-child-list-drops-default-order.md` is in review as of 2026-09-01 with its
  ordering projections landed on trunk, which is what closed two of the census's five sites and what
  phase 2 has to keep closed. Phase 2 does not wait for that item's Done gate: it reads the tree, and
  the tree carries the projections. If that review sends the item back and the projections change
  shape, phase 2's fold is where the change shows up.

## Provenance

Filed 2026-08-14 as a re-sourcing of the enforcement off the launcher relation's ordering slot,
after three items named the shared enforcement question and none took it. Rewritten 2026-08-19 at the
owner's direction, in light of the fact-oriented pivot and
`roadmap/planners-read-facts-emitters-read-commands.md` in particular: the launcher anchor put a
build-time rejection one tier above the verdict it had to fail, on a population that excluded the
site the item had already flagged against itself, and the same pass found the census splits three
ways rather than two. The honesty half survives unchanged as class A. The re-sourcing half becomes
class B at the fact tier, sequenced behind the launcher step. The comparison of two ends that all
three predecessor items were reaching for becomes class C, which needs no new source and no new
tier and can ship now.

The class A half is the reported half: https://github.com/sikt-no/graphitron/issues/523.

Specced 2026-09-01. Four things the spec pass changed rather than elaborated, each worth a reviewer's
attention because each is a departure from the body it was written against:

* **Two of the five census sites closed while the item sat in Backlog**, both by
  `roadmap/split-query-child-list-drops-default-order.md`'s delivery. The launcher family therefore
  has no live class-C site anyone has found, which is why phase 2 states that half as a ratchet and
  puts its case on the multiset half, where the sixth candidate site sits and where the two ends are
  derived independently.
* **The routine write path is class A, once class A is keyed on availability rather than on
  declaration.** Its leaf carries no ordering slot, so nothing resolved is ever discarded and class C
  cannot see it; its target table has a primary key, so class B as first drafted verdicts it
  `ORDERED`. The generalisation is what gives it a cell, and the cell's rejection is held while the
  only live instance is in our own example schema.
* **Class C lands in production, not in a test.** The track's stated purpose is finding the sites
  nobody has reported, and a test over our own fixtures cannot do that. The throw follows the
  precedent in the same class it lands in.
* **A sixth candidate site**, an inline child list with an `@orderBy` argument, found by reading the
  multiset renderer's single ordering branch. Unverified: three code reads say the ordering is silently
  ignored, and phase 2 carries the recipe that confirms or refutes it before the invariant is written.

## Reviewer findings

### Round 1, Spec -> Ready, 2026-09-08

Verdict: revisions requested. Phase 1 checks out end to end and I would hand it to an
implementer as written. Phases 2 and 3 each carry a mechanism-level problem that an
implementer would have to redesign in flight, so the item stays in `Spec`.

**1. Phase 2's assertion pair contradicts its own exemption set, and the contradiction is live
in this reactor (question 2, and question 1's viability half).** The two bullets under "What it
asserts" are stated independently: the first requires a present `Ordering` slot whenever the
leaf resolved a non-empty `Fixed` or an `Argument`, the second exempts an absent slot by
`LaunchSource` arm. A root `@lookupKey` list coordinate satisfies the first bullet's antecedent
and the second bullet's exemption at the same time. `OrderByResolver.resolveDefaultOrderSpec`
falls back to the target table's primary key, so `QueryField.QueryTableField` for
`filmById(film_id: [ID] @lookupKey): [Film]!` in `graphitron-sakila-example`'s schema resolves
`OrderBySpec.Fixed(FILM_ID)`, while `LauncherCommands.lookupRow` builds
`new ResultShape.RecordList(null)` unconditionally. A production fold reading the first bullet
literally throws on the verification build.

Resolving it the charitable way, letting the exemption gate both bullets, is where the finding
bites rather than where it ends. For every non-exempt arm the slot is computed by
`LauncherCommands.orderingOf` directly off the leaf's `OrderBySpec`, and a leaf resolving
`OrderBySpec.None` never reaches the plan because `validateListRequiresOrdering` rejects it
first. So the leaf-reading half asserts a total function against its own output, and the track
reduces to "every non-exempt list-shaped row carries an `Ordering`". That is a defensible
ratchet, but it is not the comparison of two ends that this item's own "Why this is its own
item" section says all three predecessor items were reaching for, and phase 2's name promises.
The four absent-slot sites are exactly the four the exemption list names (`lookupRow`,
`serviceReentryRow`, and `dmlRowOf`'s two projected-list arms), so there is no launcher-family
divergence left for the check to find.

What would satisfy this: state the launcher half as one assertion over non-exempt arms and say
plainly that it is a ratchet over a closed population rather than a two-ends comparison, or
site the two-ends comparison where the ends can actually diverge. The plan already identifies
that place, and it is the multiset half: `CallWrap.Multiset` carries the whole `OrderBySpec`
while `ProjectionUnitRenderer` renders one arm of it, so nothing there derives the command from
the render's capability. If phase 2 is worth shipping, that half is the reason, and the
confirmation recipe is already written for it.

**Response.** Taken as read, both halves. Phase 2 keeps shipping and is re-titled and re-ordered
around the split. The multiset half leads and carries the phase's case, stated as the two-ends
comparison and as the reason the phase exists; the launcher half follows, stated as one assertion
over non-exempt arms and called a ratchet over a closed population, with the four exempt arms named
as the whole of today's absent-slot population. The leaf-reading bullet is gone, and the two facts
that killed it are premises of the new text rather than history: the `@lookupKey` root list resolving
a primary-key `Fixed` against `lookupRow`'s unconditional `RecordList(null)`, and `orderingOf`
computing the slot off the leaf on every non-exempt arm. The phase-2 test list moves with it, the
per-arm negative pin becoming a membership pin over the exempt arms, since a keyed lookup's leaf does
resolve an ordering and the row is right to drop it.

**2. Phase 3's rule set verdicts the routine write path `ORDERED`, so the one live class-B site
the plan names is admitted to the population and then passed (question 1's viability half).**
`PRIMARY_KEY_FALLBACK` is stated as an unqualified fact join, "where the read's target table has
a `sql_primary_key`". `Mutation.rentFilm: [Rental!]!` navigates as `Rental`, which binds
`rental`, so it takes a `NAMED_TYPE_TABLE` row in `intent_field_scope_table` and `rental` has a
primary key. The reduction therefore reads `ORDERED` with `PRIMARY_KEY_FALLBACK` as the winning
rule, no `UNORDERED` row is minted, and the phase's own test list ("the routine write path ...
as a case that fails before the phase and passes after") cannot be satisfied.

The census's C-to-B reclassification is what opens the gap, and the reclassification's stated
reason is what closes the escape. `MutationField.MutationRoutineWriteField` carries no ordering
slot, so nothing resolved is discarded (correct, class C cannot see it), but an ordering is
available at the fact tier (a primary key exists), so class B as defined cannot see it either.
The defect is neither "no ordering resolves" nor "an ordering resolved and the lowering dropped
it" but a third thing: an ordering is available and the coordinate's family has nowhere to lower
it into. Class A is that comparison at the declaration grain; this is the same comparison with
the fallback standing in for the declaration.

What would satisfy this: either place the routine write path in a cell that can reject it (class
A generalised from "declared" to "available", which the plan is closer to than it reads, since
`intent_field_unlowerable_ordering`'s `verdict` column is already built for a second value), or
drop the claim that phase 3 catches it and say which mechanism does. What the revision should
not do is answer it with a fact about whether a family lowers an ordering, because that is a
command-tier property and the "Why the source is facts" section rules the sourcing out; the
class taxonomy is what has to move, not the tier.

**Response.** Class A is generalised, and the taxonomy moved with it. The rule now compares the
ordering **available** at a coordinate against the ordering its read shape **delivers**, so the
multitable root and the list-returning routine write are two arms of one comparison rather than two
classes; class B becomes "no ordering is available at all", and A and B partition the list-shaped
read coordinates between them. Both sides stay at the fact tier: the write arm reads
`intent_mutation_routine_seat` at `verdict = 'ADMITTED'` joined to `graphql_field.is_list`, which is
that relation's own read-shape fact and not a command family's slot inventory, exactly as the
multitable arm reads `intent_field_scope_table.basis`. `verdict` gets its second value,
`KEY_CAPTURE_SCATTER`, and `declared_via` becomes `available_via` with a `PRIMARY_KEY_FALLBACK` arm.
One thing the revision found while placing it, and the plan now carries it: the only live instance is
`Mutation.rentFilm` in our own example schema, so turning that rejection on reddens the verification
build until R660 gives the shape an order to deliver. The arm therefore mints its view row and holds
its `ValidationError`, under this item's own rule that an exemption names what removes it, and R660
flips it. Phase 3 drops the claim, in its own section and in its test list, and says where the
coordinate went.

**3. The phase-1 main-source path names a module that cannot hold the class the same section
describes (question 2).** `graphitron/src/main/java/no/sikt/graphitron/rewrite/derive/` holds
shadow-agreement scaffolding (`ClaimDomain`, `DemandResidue`), not the store-derived detection
families. `AuthoredClaimConflicts`, `AuthoredClaimRejectionRows`, `ArgmappingProjectionDefects`,
`StoreDetections` and `FactCapture` all live in `graphitron-model`, and `graphitron` depends on
`graphitron-model` one way, so a class under `no.sikt.graphitron.rewrite.derive` cannot be a
component of `StoreDetections` nor be called from `FactCapture.detect`, which the same bullet
list requires of it. Both test paths the plan names are already right by that precedent
(`ArgmappingProjectionDefectTest` in `graphitron-model`'s `model/intent`, its decode sibling in
`graphitron`'s `rewrite/derive`), which is what makes the main-source path read as the odd one
out. Left as a finding rather than corrected in place because there are two coherent answers:
move the class to `graphitron-model/src/main/java/no/sikt/graphitron/model/derive/`, or keep it
in `graphitron` and reach the error stream by some route other than `StoreDetections`. The first
is what the precedent implies; the choice is the author's.

**Response.** Taken, the first way. Main sources move to
`graphitron-model/src/main/java/no/sikt/graphitron/model/derive/`, and the Java-side section now says
why the placement is forced rather than chosen, so the next reader does not re-open it. Both test
paths are unchanged and the section says so, naming `ArgmappingProjectionDefectTest` and
`ArgmappingProjectionDefectsTest` as the split they follow. The sequencing section's `rewrite/derive/`
mention is corrected to the same path.

**4. No `## Goal` section (question 1, communication half).** `roadmap/workflow.adoc` puts the
goal first and says the `Spec -> Ready` reviewer answers the first gate question by reading its
opening paragraph. This body opens with an unlabelled problem statement and carries the goal's
substance under `## What changes for a consumer`, after four analysis sections and the
sequencing. The content is good, and I could state what changes for a consumer from it without
reconstructing anything from the phase list, so this is a placement finding rather than a
missing-goal one. Promoting that section to `## Goal` at the top would settle it.

**Response.** Promoted. `## Goal` is now the first section, opening with a paragraph that stands
alone, and the SDL example is lifted into a fenced block rather than run into the prose. Lowering is
glossed on first use, as is the multitable interface. The problem statement it displaced becomes
`## Why today's checks miss most of it`, the first plan section. The three consumer-facing paragraphs
are updated for this round's other two answers: phase 2's is now about the multiset's two ends with
the launcher ratchet beside it, and phase 3's drops the remedy count.

**Non-blocking.**

* The `verdict` column's justification names "a routine terminus with no primary key" as the
  next candidate value. After R704 that coordinate is not a class-A defect: a declared ordering
  over a routine terminus is lowered, and the undeclared case is the class-B rejection
  `validateListRequiresOrdering`'s routine arm already mints. Finding 2 above suggests a
  different second value; if that lands, this sentence is the one to replace rather than keep.
* `intent_field_unlowerable_ordering`'s stated column list carries no `argument_name`, while
  `graphitron_order_by_entry` is keyed at argument grain. Two `@orderBy` arguments on one coordinate
  would collide on the view's key. Probably unreachable (`OrderByResolver.resolve` takes the
  first `ArgumentRef.OrderByArg` it finds), but the view's key is worth one sentence either way.

**Response to both.** Both taken. The "next candidate" sentence is replaced by the verdict finding 2
put there, `KEY_CAPTURE_SCATTER`, so the column's justification now names a value the plan actually
delivers. `argument_name` joins the column list, NULL on the two non-argument arms and in the key,
with the reason stated: a view key is not the place to inherit a consumer's first-match precedence.

**What I verified against the tree.** Every symbol, relation, column, test class and document
path the plan names exists as named, including the four quoted comment fragments
(`intent_field_scope_table.basis`'s "several statements rather than one", `Ordering.Columns`'s
empty-spec throw, `CallWrap.Multiset`'s single-arm note, `diagnostic.actionable`'s `DEFERRED`
documentation). The pipeline-position argument holds: `Projection.VALIDATE` and
`Projection.BUILD_OUTPUT` both carry `emit = false`, so no plan is produced on either, and
`runPipeline` returns before `EmitPlan.produce` when the fused error stream is non-empty. The
population argument holds: `QueryField.QueryInterfaceField` and `QueryField.QueryUnionField`
declare no `orderBy` or `pagination` component and implement neither `SqlGeneratingField`, so
both current checks skip them, and nothing else rejects an `@orderBy` argument at that
coordinate. `@asConnection` over the multitable shape is admitted with a lint advisory, so the
reported schema does build today. The sort-results correction is warranted: the section as
written describes an ordering that is not lowered. The two closures the census records are real
in `LauncherCommands.batchedResultOf` and `batchedLookupRow`, and the sixth site's premise holds
as far as the validator goes, `validateTableField` checking only the reference path, the
lookup-connection pair and cardinality.

### Round 2, Spec -> Ready, 2026-09-08

Reviewer session: `https://claude.ai/code/session_01A782mxCy43Yi1jecwh5YGr`. Verdict: sign off.

Both round-1 findings that bore on the mechanisms are closed in the body as the responses say.
The write arm reads `intent_mutation_routine_seat` exactly as that relation is built (population is
every mutation-root `@routine`, `ADMITTED` is its one emitting verdict, `READ_SURFACE_ON_WRITE` keys
on `graphitron_order_by` and the conditions and never on `graphitron_default_order`), the multiset
half is sited where `ProjectionCommands` hands `ttf.orderBy()` to `CallWrap.Multiset` unfiltered and
`ProjectionUnitRenderer` lowers the `Fixed` arm alone, and the launcher ratchet's exempt arms are the
four `RecordList(null)` sites in `LauncherCommands` and no others. Phase 1 extends
`AuthoredClaimConflicts` / `StoreDetections` / `FactCapture.detect` as they stand, phase 2 follows
the production `IllegalStateException` precedent already in `LauncherCommands`, phase 3 follows
`intent_resolved_field_demand` and its coverage gate. Nothing here stands a parallel mechanism beside
an existing one. What was verified is in the commit message.

**Non-blocking.**

* Phase 2's exemption list names "the schema-free unit-tier assemblies" as a third exempt arm, but
  the switch is over `LaunchSource`, which has no such arm. The schema-free path's only absent-slot
  rows come from `serviceReentryRow`, whose source is `ProjectedReentry` and already exempt under the
  second bullet, so the entry is redundant rather than wrong; fold it into that bullet or drop it
  when the phase lands.
* `LauncherCommands.orderingOf` returns null on an empty `OrderBySpec.Fixed` as well as on `None`,
  while `validateListRequiresOrdering` rejects `None` alone. An empty `Fixed` reaching a non-exempt
  list row would trip the ratchet, which is what the ratchet is for, but the implementer should
  expect the corpus run to say whether that shape resolves today rather than assume the four sites
  are the whole absent-slot population.
* The `PARTICIPANT_TABLE` arm also covers child coordinates returning a multitable container:
  `ChildField.InterfaceField`, `UnionField`, `BatchedInterfaceField` and `BatchedUnionField` carry
  no ordering component, the same as the root leaves, so a declaration there is accepted and
  discarded today too. Correct behaviour for the rule; worth one case in
  `FieldUnlowerableOrderingTest` so the population's width is pinned.
