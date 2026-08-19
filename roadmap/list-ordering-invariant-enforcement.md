---
id: R677
title: "Derive the never-unsorted-list verdict from facts, and pin the lowering the verdict cannot see"
status: Backlog
bucket: validation
priority: 3
theme: codegen-correctness
depends-on: []
created: 2026-08-14
last-updated: 2026-08-19
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

Five sites are known. They do not divide the way a single enforcement mechanism would need them to.

* Root `@routine` chain: **closed**. R704 (`routine-composition-surface-from-facts`, Done, see
  `roadmap/changelog.md`) removed it. The escape was a carve-out rather than a capability gap: the
  leaf is a `SqlGeneratingField`, and the rule named the `RoutineResolution.Chain` arm as an explicit
  exemption. Removing the exemption closed the site, and it was the only one of the five that ever
  produced `None`.
* `@splitQuery` child list: a resolved `OrderBySpec.Fixed` is discarded at the model-to-command
  boundary (`roadmap/split-query-child-list-drops-default-order.md`).
* `@lookupKey` child: `LauncherCommands.batchedLookupRow`'s ordering slot is left empty
  (`roadmap/lookup-unrealized-co-members.md`).
* Mutation routine write path: unordered step 2
  (`roadmap/routine-write-key-capture-unordered.md`).
* Root query over a multitable interface or union: the arm carries no ordering component at all, so
  `@orderBy` and `@defaultOrder` are accepted and discarded and rows come back in participant
  primary-key order (`roadmap/multitable-interface-query-orderby-lowering.md`).
  `QueryInterfaceField` and `QueryUnionField` carry a `PolymorphicReturnType` and declare no
  `orderBy` or `pagination` component, which is the type-level reason both checks skip them: a
  paginated multitable root with no ordering passes `validatePaginationRequiresOrdering`, the check
  written to reject exactly that shape.

Sorted by what can decide them, the four live sites are three different defects:

**Class A, the author declared an ordering the coordinate cannot honour.** The multitable root is
this: `@orderBy` and `@defaultOrder` classify clean, generate without a diagnostic, and produce
participant-primary-key order at runtime, while `@condition` filters on the same field work. So is a
routine terminus with no primary key. The comparison is between an authored fact and a coordinate's
capability, and both sides are facts.

**Class B, no ordering resolves at all.** A list-shaped read whose coordinate carries no authored
ordering and whose target offers no primary-key fallback. This is what the current checks were
written for, and it is decidable from facts too: the authored side is captured, and the fallback is a
join.

**Class C, an ordering resolved and the lowering discarded it.** The `@splitQuery` child, the
`@lookupKey` child and the routine write path are all this. **No fact at any grain can see class C**,
because at the fact tier the ordering is present. These are generator defects, not schema defects.
There is no schema for an author to fix and no source location for a message to point at.

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
rule reaches all three, because `captureFactsAndDetect` runs ahead of the verdict on every path and
the `diagnostic` view already carries `intent_authored_claim_conflict` as a view arm beside the
walk's own errors.

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

## Notes for whoever specs this

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
