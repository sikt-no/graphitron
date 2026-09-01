---
id: R677
title: "Derive the never-unsorted-list verdict from facts, and pin the lowering the verdict cannot see"
status: Spec
bucket: validation
priority: 3
theme: codegen-correctness
depends-on: []
created: 2026-08-14
last-updated: 2026-09-01
---

# Derive the never-unsorted-list verdict from facts, and pin the lowering the verdict cannot see

Graphitron states an invariant: a list result is never unsorted. Two checks enforce it today,
`GraphitronSchemaValidator.validateListRequiresOrdering` and its paginated sibling
`validatePaginationRequiresOrdering`, and both key on the same pair of signals: the field's resolved
`OrderBySpec` landing on `None`, and the field being a member of `SqlGeneratingField`. Most known
violations produce neither signal, so the check passes and the rows ship in whatever order the
database happened to return.

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
  (`roadmap/routine-write-key-capture-unordered.md`). Live.
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

**Class A, the author declared an ordering the coordinate cannot honour.** The multitable root is
this: `@orderBy` and `@defaultOrder` classify clean, generate without a diagnostic, and produce
participant-primary-key order at runtime, while `@condition` filters on the same field work. So is a
routine terminus with no primary key. The comparison is between an authored fact and a coordinate's
capability, and both sides are facts.

**Class B, no ordering resolves at all.** A list-shaped read whose coordinate carries no authored
ordering and whose target offers no primary-key fallback. This is what the current checks were
written for, and it is decidable from facts too: the authored side is captured, and the fallback is a
join.

**Class C, an ordering resolved and the lowering discarded it.** The `@splitQuery` child and the
`@lookupKey` child were this, and both are now closed at the command tier, which leaves the class
with no live site anyone has found. **No fact at any grain can see class C**, because at the fact
tier the ordering is present. These are generator defects, not schema defects. There is no schema
for an author to fix and no source location for a message to point at.

The routine write path is **class B, not class C**, and the census had it in the wrong column. Its
own item states the reason: `MutationField.MutationRoutineWriteField` "carries no ordering slot, so
there is nowhere for a resolved `OrderBySpec` to live even if `@defaultOrder` were honoured". At the
fact tier nothing is present to discard, which is the class-B question, not the class-C one. The
correction matters to the plan rather than to the prose: a class-C mechanism keyed on "an ordering
resolved" would never see that coordinate, so filing it under C would have made the track look like
it covered a site it cannot reach.

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
`graphitron_default_order` and `graphitron_default_order_field` and `graphitron_order_by` for the
authored ordering, `intent_bound_table` joined to `sql_primary_key` for the fallback, and
`intent_field_chain_terminus` for the routine terminus that has no primary key to fall back on. The
polymorphic root is an ordinary row of every one of those relations.

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
  consumer who reported it. Its home is a store-derived rule in `rewrite/derive/` with
  `AuthoredClaimConflicts` as the precedent, plus a `diagnostic` view arm.
* **Class C also ships immediately**, and this is the change from the earlier framing, which filed it
  as a closing nice-to-have. The launcher relation and `Ordering` exist today, and
  `Ordering.Columns` already rejects an empty spec at construction ("an empty fixed order is
  unordered; model it as an absent Ordering, not an empty Columns arm"), so the vocabulary is
  already shaped for the assertion. Its home is a pipeline-tier invariant beside
  `LauncherRelationClosureTest`, which already reads `plan().launchers()` off the carried plan rather
  than re-deriving it: assert that every list-shaped row whose facts resolved an ordering carries a
  non-absent `Ordering`, and that no arm of the relation is list-shaped and ordering-less without a
  named exemption. Not a `ValidationError`. Landing this before the per-site fixes is what makes
  those fixes non-regressable and closes the sites nobody has found yet.
* **Class B lands after the launcher step of
  `roadmap/planners-read-facts-emitters-read-commands.md`**, or alongside it. Deciding "does an
  ordering resolve for this coordinate" from facts is the same derivation `LauncherCommands` performs
  today and that item moves store-side; doing it twice from two sources is how the two ends drift.
  Whether class B can go green before every per-site fix lands, or needs a temporary exemption list,
  is a Spec question. An exemption list is acceptable only if each entry names the item that removes
  it.

Class C is not blocked on class B and should not be sequenced behind it. The two read different
things: class C compares a resolved ordering against the command row that was supposed to carry it,
which is available now; class B asks whether an ordering resolves at all, which is the fact-tier
question.

The three tracks are phases 1 to 3 below, in that order. Two details of this section are superseded
there: class C lands as a production fold rather than as a test alone, and the two sites it was filed
to keep fixed are already fixed, which changes what the track is worth rather than whether it ships.

## Notes the plan holds to

Written when the item was filed, kept because each one is a constraint the phases above are built
against rather than a note that expired with the pickup. Where the plan departs from one it says so at
the departure: phase 2 moves class C's home from a test to a production fold, and the census section
moves the routine write path from class C to class B.


- **The three classes are three mechanisms and must not be merged.** The earlier framing's whole
  error was treating class B and class C as one re-sourcing. They read different signals, at
  different tiers, with different severities and different audiences: A and B are author-facing
  rejections with a coordinate and a location, C is an internal invariant whose failure is a bug
  report against the generator.
- **Class A is the same shape as the fan-out verdict** in `roadmap/reference-path-fanout-verdict.md`:
  a build-time verdict comparing what the author declared against what the pipeline can honour,
  decidable from captured facts rather than from a traversal. One difference to keep straight: the
  fan-out verdict is advisory, because a multiset may be what the author wanted, while this one is a
  hard rejection, because nothing can honour the declaration. Same derivation, different severity.
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

## What changes for a consumer

Three things, in the order they ship.

**A schema that declares an ordering graphitron cannot lower stops building.** Today
`Query.applikasjoner: [Applikasjon] @asConnection @defaultOrder(fields: [{name: "NAVN"}])` with an
`@orderBy` argument generates without a word and serves every page in participant primary-key order,
whichever direction the client asks for. After phase 1 that schema fails the build with a located
message naming the coordinate, the directive, and the reason (a multitable read is one statement per
participant and the ordering surface is not lowered onto them yet). This is the reporter's own
fallback ask on https://github.com/sikt-no/graphitron/issues/523, and it is a **breaking change** for
any schema that compiles today with that declaration; see "Compatibility".

**A generator that drops a resolved ordering stops building too**, with a generator-bug message rather
than a schema error. Phase 2 is what makes the ordering fixes that already shipped non-regressable and
what finds the coordinates nobody has reported. Nothing changes for a consumer whose schema is clean.

**The never-unsorted rule starts being keyed on the question it asks.** Phase 3 replaces the two
capability-keyed validator checks with one fact-derived verdict per list-shaped read coordinate, so
the rule stops depending on a read resolving against exactly one table. For a consumer this shows up
as coordinates that used to slip past the check now failing it, each with a message that names which
of the three remedies applies.

Phases 1 and 2 are independent of each other and of everything else; phase 3 waits on R682. The
`In Review` transition happens per phase, per the multi-phase convention in `roadmap/workflow.adoc`.

## Phase 1, class A: reject a declared ordering the coordinate cannot lower

Copies `AuthoredClaimConflicts` outright, which is the precedent this item's notes already name: the
reduction lives in a view's SQL, the Java member decodes the view's closed vocabulary into located
`ValidationError` values, and a capture-cadence writer stores the minted rejection so the editor's
`diagnostic` view reads it as a plain column.

### The population, in facts

The coordinate is one whose read is several statements rather than one, and the store already says
which those are. `intent_field_scope_table.basis` carries `PARTICIPANT_TABLE` for exactly this shape,
and its own column comment says so: "PARTICIPANT_TABLE being exactly where the coordinate is several
statements rather than one". No new capture, and no restatement of the polymorphic recognition, whose
own precondition (the container binds no table, each participant binds one) lives in
`intent_field_participant_scope_table`.

The declaration side is two relations, both captured today:

* `graphitron_default_order` at the coordinate, with `graphitron_default_order_field` for the entries
  and the directive's own `source_name` / `source_line` / `source_column` for the location.
* `graphitron_order_by` on any argument of the coordinate, with the argument's own position.

Both sides are joined under `intent_type_domain` on the owning type, exactly as
`AuthoredClaimConflicts` narrows its build-error population: only a coordinate the generator intends
to classify can fail a build. The editor arm reads the view ungated, on the same reasoning that
relation's comment gives.

### Why this is a declaration rule and not an unsortedness rule

Worth stating because it decides the message and the vocabulary. A multitable root with no
declaration at all is **not** a violation: the polymorphic emitter projects a synthetic `__sort__` per
branch and orders on it, so the rows come back in participant primary-key order, deterministically.
The invariant "a list is never unsorted" holds there. What fails is the author's declaration, which is
accepted and then not lowered. So the rule compares an authored fact against a coordinate's capability
and says nothing about coordinates that declared nothing, which is also why it cannot be folded into
phase 3's verdict view: that view answers "does an ordering resolve", and here one does, just not the
declared one.

### The two relations

* `intent_field_unlowerable_ordering (graph_name, type_name, field_name, verdict, declared_via,
  source_name, source_line, source_column)`. One row per coordinate whose declared ordering the
  coordinate's own read shape cannot honour. `verdict` is a closed vocabulary; today it holds one
  value, `PARTICIPANT_FAN_OUT`, and the column exists from the start because the census already names
  the next candidate (a routine terminus with no primary key, which is R382's sibling question and not
  this phase's). `declared_via` is the closed pair `DEFAULT_ORDER` / `ORDER_BY_ARGUMENT`, and a
  coordinate declaring both is two rows: the arity is the answer, as on every rule view in the family,
  and each remedy names one directive. The location columns are the declaring directive's own, so the
  editor underlines the directive rather than the field.
* `intent_field_unlowerable_ordering_rejection (graph_name, ordinal, type_name, field_name, kind,
  variant, message)`, on `intent_authored_claim_rejection`'s shape and for its stated reason: a table
  because the message is a render no view over this store can state, written by a capture-cadence
  writer that clears its graph partition and re-mints after every flush.

Both carry a `COMMENT ON` per relation and per column; `FactSchemaGateTest` fails the build otherwise,
and the `intent_` prefix houses them in the family census with no `meta_` edit needed.

### The Java side

* `graphitron/src/main/java/no/sikt/graphitron/rewrite/derive/UnlowerableOrderings.java`, structured
  like `AuthoredClaimConflicts`: a `Detection` record with `violations()`, a typed per-coordinate
  verdict beside it, and one `rejectionOf` that is the single mint of the `Rejection` value, shared
  with the rows writer so the report and the store cannot spell one violation two ways.
* `UnlowerableOrderingRejectionRows.java`, the writer, on `AuthoredClaimRejectionRows`'s cadence.
* `StoreDetections` gains the family as a component, and `FactCapture.detect` gains the call. Both are
  one-line joins by construction; that is what that record's javadoc says the shape is for.
* A `diagnostic` view arm, joining the defect view to the rejection rows on the coordinate, matching
  the claim-conflict arm column for column.

### The rejection arm and the message

`Rejection.deferred(summary)`, not `structural` or `invalidSchema`. The `diagnostic` view derives
`actionable` as `kind <> 'DEFERRED'` and documents the `FALSE` case as "recognised but not yet
generator-supported, a workaround rather than a schema fix", which is this violation exactly: the
schema is well formed, the directive is real, and the remedy is to drop the declaration or wait for
`roadmap/multitable-interface-query-orderby-lowering.md`. A deferred rejection still fails the build,
so the outcome the reporter asked for is unchanged; what the arm buys is that an editor triages it
correctly and that the row leaves the population the moment the lowering lands.

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

## Phase 2, class C: pin the two ends of every lowered ordering

The comparison all three predecessor items were reaching for: what the model resolved against what the
command row carries. It needs no new source and no new tier, and it ships now.

**Where it lives, and a departure from this item's own notes.** The notes proposed a pipeline-tier test
beside `LauncherRelationClosureTest`. This plan puts the check in production instead, as a fold over the
finished relation inside `LauncherCommands`, throwing `IllegalStateException` with the
"Graphitron generator bug (...)" prose the tree already uses for a shape the emitter cannot honour (see
the batched-lookup throw in the same class, whose comment says "Failing at production keeps the gap loud
until a single-shaped lookup emission or a validator rejection lands"). The reason is the track's own
stated purpose: a test over our fixture corpus finds the sites our fixtures exercise, and the class's
value is the sites nobody has found. A production invariant runs on every consumer's schema. The
pipeline-tier test still ships, pinning the invariant in both directions, but it is the second artifact
rather than the mechanism.

**What it asserts.** For every row of the launcher relation whose `ResultShape` is `RecordList`:

* if the coordinate's classified leaf resolved an ordering (a `SqlGeneratingField` whose `orderBy()` is
  a non-empty `Fixed` or an `Argument`), the row's `Ordering` slot must be present;
* if the slot is absent, the row's `LaunchSource` arm must be an exempt one.

The exemption set is a total switch over `LaunchSource`, no `default`, so a new source arm is a
compile-time decision by whoever adds it rather than a silent admission. Today's exempt arms and their
reasons, each of which is already written down on the shape it belongs to:

* `KeyedLookup`: input order is carried by the scatter onto the keys' slots, so there is no order to
  sort by. `ResultShape.RecordList`'s own javadoc states this.
* `ProjectedReentry` and the discriminated reentry source: the `ORDER BY idx` scatter re-keys the
  re-projected rows to the upstream source order. Sound where that upstream order is itself defined,
  which for a DML write's returned keys it is. It is **not** sound for a routine write, and that is
  precisely the premise failure `roadmap/routine-write-key-capture-unordered.md` records against
  `requiresReFetch()`; the exemption entry says so in its comment, and phase 3 is what closes it.
* The schema-free unit-tier assemblies, which carry no coordinate to read a leaf for.

`ResultShape.Connection` needs no arm: its constructor already requires a non-null `Ordering`.

**The multiset population too.** The launcher relation is not the only list-shaped command family.
`CallWrap.Multiset` carries the whole `OrderBySpec` and the renderer honours the `Fixed` arm only, so
the two ends can diverge there in a way the launcher relation cannot: nothing drops the slot, the
renderer just has no branch for it. The same fold therefore covers the projection relation's multiset
arms with the arm-shaped assertion that fits them: a list-cardinality multiset may not carry an
`OrderBySpec.Argument`, because no site lowers one.

This is what confirms or refutes the sixth census site. The confirmation recipe, to be run **before**
the invariant is written, so the invariant is known to be able to fail: add
`filmsOrderedInline(order: [FilmOrderBy] @orderBy): [Film!]!` as an inline child list on an existing
sakila example type (no `@splitQuery`, no `@asConnection`), generate, and read the emitted projection.
If the multiset carries no `orderBy`, the site is real and the phase owes it two things: a filed item
for the lowering (or the rejection, if the render side is not worth building), and an exemption entry
naming that item. Per this item's own rule, an exemption is acceptable only if its entry names the item
that removes it. If the emission turns out to reject or to lower it, the census bullet gets struck and
the fold's multiset half is a ratchet with no live site, which is the same shape as its launcher half.

**Why not a `ValidationError`.** A dropped ordering is not a schema defect. There is no coordinate for
the author to fix, and a message pointing at their `@defaultOrder` would be pointing at the one thing
they did right. The audience is whoever is changing the generator, and the register is the throw.

## Phase 3, class B: the ordering-resolution view

The one piece of genuine modelling work, and the only track that waits.

**Shape.** A positive population carrying a verdict, on `intent_resolved_field_demand`'s model, because
absence is not the complement's claim (see the section of that name above):

* `intent_field_ordering_rule (graph_name, type_name, field_name, rule)`, one literal per arm, arms
  unmasked against each other: `DEFAULT_ORDER` where the coordinate carries `graphitron_default_order`,
  `ORDER_BY_ARGUMENT` where an argument carries `graphitron_order_by`, `PRIMARY_KEY_FALLBACK` where the
  read's target table has a `sql_primary_key`, `PARTICIPANT_KEY` where the read fans out per
  participant and the synthetic key orders it, `INPUT_SCATTER` where the visible order is the input
  order (the keyed shapes phase 2 exempts, stated here as a positive rule rather than an absence).
* `intent_resolved_field_ordering (graph_name, type_name, field_name, verdict, rule)`, the reduction:
  `ORDERED` with the winning rule in declared precedence order, or `UNORDERED` over the list-shaped read
  coordinates no rule covers. That second population is the rejection.
* A coverage gate counting resolved rows against the population, on the shadow agreement's terms, so
  the construction stays honest rather than quietly shrinking to what the rules happen to answer.

The population is "list-shaped read coordinate": `graphql_field.is_list`, in `intent_type_domain`, with
a row in `intent_field_scope_table` (any basis) or a routine terminus in
`intent_field_chain_terminus`. Note what that population does **not** need: membership in a capability
interface. That is the whole point of the track, and it is what admits the routine write path, which
today's checks cannot see at all.

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
  one case per `declared_via` arm, the both-directives coordinate yielding two rows, and the
  boundaries, each of which is an absence some other surface owns. A single-`@table` field with
  `@defaultOrder` is quiet. A multitable field with no declaration is quiet, which is the
  "declaration rule, not unsortedness rule" section asserted as a property. A single-table
  discriminated interface (`@discriminate` on the container) is quiet, because it lowers ordering
  today and the two shapes are one join apart.
* `graphitron/src/test/java/no/sikt/graphitron/rewrite/derive/UnlowerableOrderingsTest.java`, the
  decode: the located `ValidationError` for the reported shape, message text and location asserted
  against the declaring directive's own position and not the field's, plus the domain narrowing (a
  violating coordinate on a type outside `intent_type_domain` mints no build error while the view
  keeps its row).
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
  leaf resolved an ordering carries a slot, and every absent slot's source arm is an exempt one. The
  exemption set is read off the production switch, never restated in the test, on
  `LauncherRelationClosureTest`'s own rule about reading the producer's declared membership data.
* A negative pin per exempt arm: the arm is exempt *and* its leaf resolves no ordering, so a future
  change that starts resolving one on a keyed lookup fails here rather than passing quietly.
* The multiset half: an assertion that no list-cardinality `CallWrap.Multiset` carries an
  `OrderBySpec.Argument`, plus whatever the confirmation recipe above turns up.

**Phase 3.**

* The view's unit-tier test, one case per rule arm plus the `UNORDERED` reduction, and the coverage
  gate as its own assertion.
* The three coordinates today's checks cannot see, each as a case that fails before the phase and
  passes after: the routine write path, and two the census does not name yet, to be taken from the
  count that decides whether the phase can go green.
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
phase 3 and correct any bullet the new population makes wrong. Phase 2 has no user surface.

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

## Cost

One query per build for phase 1, over relations that are already registered or cheap
(`intent_field_scope_table` is a table with a coordinate index, the two directive relations are
captured tables). Take the number before wiring it in, per `DerivedReadCostTest`'s discipline, and do
not register the view: one reader, so a registration would pay a refresh to save an evaluation. Phase
2 is one pass over rows already in memory. Phase 3's cost is unknown until the population exists and
is the phase's own measurement to take.

## Retired vocabulary

Phase 3 only; phases 1 and 2 retire nothing.

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
  question (mutation field or payload data field) and the `requiresReFetch()` census. Phase 3 makes the
  coordinate visible to the rule; it does not decide where the order surface goes.
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
three ways is what gives each half a home, and two of the three homes already exist.

## Relationship to other items

* `roadmap/planners-read-facts-emitters-read-commands.md` owns the launcher relation's move onto the
  store, which is what class B reads. It is also where the rule that a build-time rejection is not
  sourced from commands belongs, so the next item in this one's position does not re-run the
  argument.
* `roadmap/reference-path-fanout-verdict.md` is the shape class A copies, at a different severity.
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
  `roadmap/split-query-child-list-drops-default-order.md`'s delivery. Class C therefore has no live
  site anyone has found, which the plan takes as a reason to ship the track (a ratchet over a fixed
  population is what keeps it fixed) rather than as a reason to drop it. A reviewer who disagrees
  should say so: dropping phase 2 is a coherent position and it is the one thing in this plan whose
  case rests on preventing regressions rather than on fixing something.
* **The routine write path is class B, not class C.** Its leaf carries no ordering slot, so there is
  no resolved ordering for a two-ends comparison to compare. Filing it under C would have made phase 2
  read as covering a coordinate it cannot reach.
* **Class C lands in production, not in a test.** The track's stated purpose is finding the sites
  nobody has reported, and a test over our own fixtures cannot do that. The throw follows the
  precedent in the same class it lands in.
* **A sixth candidate site**, an inline child list with an `@orderBy` argument, found by reading the
  multiset renderer's single ordering branch. Unverified: three code reads say the ordering is silently
  ignored, and phase 2 carries the recipe that confirms or refutes it before the invariant is written.
