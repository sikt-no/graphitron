---
id: R746
title: "Order the materialization registry, so a target may be derived from another target"
status: In Review
bucket: dx
priority: 3
theme: tooling
depends-on: []
created: 2026-08-20
last-updated: 2026-08-20
---

# Order the materialization registry, so a target may be derived from another target

R742 lands `meta_materialize (source_view_name, target_table_name, reason)`, a constrained table,
and a `graphitron-model` method that iterates it, refreshing each target from its view inside the
capture transaction and scoped to the graph being captured. The registry records no ordering, which
is correct for what R742 registers and is not correct in general.

**This item is no longer speculative, and the relation waiting on it is now named.** R733's third
measurement pass extended R742's multiplicity report from "which read is expensive" to "what does one
more registration remove from the reads a run actually performs", and the answer it selects is
`intent_resolved_type_binding`. That relation reads `intent_bound_table`, which reads
`intent_spelled_table`, a registered target: the exact case this item exists for, met on the first
attempt at a third registration. Three things follow, and the first two are corrections to sections
below rather than additions to the plan.

*The failure is not quiet.* The section below expects "a populated target holding rows derived from an
empty one" and a gate failing on the first run. What R733 actually observed, registering that relation
against `graphitron-sakila-example`, is a hard build failure carrying an author error:

```
Field 'Mutation.rentFilmPayloadProjected': @routine argMapping entry
'pCustomerId: input.customerId.customer_id' names 'customer_id', which is not a key column of
'Customer'; 'Customer' resolves no key columns on any tier, so pin them with @node(keyColumns:)
on that type
```

Nothing is wrong with that schema. An ordering bug in the materializer reaches the schema author as an
instruction to change their own SDL, which is worse than a wrong answer nobody sees: it is a confident
wrong answer aimed at somebody who cannot act on it. That is the strongest argument this item has and
it is worth stating before the mechanics.

*Time is waiting on it, even though it buys none itself.* With the ordering forced by hand (renaming
the source view so it sorts last), that one registration takes `GeneratorDeterminismTest` from 18.99s
to 11.61s and the store's read time per generator run from 2.55s to 0.40s, both green. Those are the
generator's own reads, so every consumer's build pays them, and nothing else measured in the store is
close. Read the closing section's "not a performance item at all" as "this item changes no timing
itself", which is true, rather than as "no timing depends on it", which is not. The Spec pass
revisited the priority on that basis: it now carries 3, matching the item blocked on it.

*One case for the test section below.* R742's rework gave
`FactSchemaGateTest.everyMaterializedTargetEqualsItsRule` real rows, so it would now catch a target
populated from an empty predecessor. What it cannot exercise is the ordering itself, its fixture
carrying two registrations that do not depend on each other. A fixture registration whose view reads
another registration's target is what closes that, and the failure above is the concrete case it has
to fail on before this item's sort is in place.

This item adds the order. It is strictly additive: no relation R742 registers changes, no reader
changes, and with no dependency between registered relations the materializer keeps behaving exactly
as it does today, byte for byte.

It is a planned successor rather than a defect report. R742 scopes itself deliberately to the
smallest mechanism that is correct for what it registers, and lists its simplifications in a table
naming this item as the one that lifts the first of them. Read that table for the shape of the
sequence; what follows here is only this step.

## Why R742 could leave it out, stated so this item knows what it is fixing

A materialized target is refilled by
`INSERT INTO <target_table_name> SELECT * FROM <source_view_name> WHERE graph_name = ?`. If that
source view reads *another* target, then the answer depends on whether the other target was
refreshed first, and an unordered materializer will populate one of them from stale or empty rows.
Nothing about the mechanism prevents this; R742 is safe because of a property of the two rows it
registers, not because of a property of the design.

The property, verified against the DDL and pinned by
`MaterializeRegistryGateTest.theRegistryNeedsNoOrderingYet`: neither `intent_argmapping_pair` nor
`intent_spelled_table` appears in the other's dependency closure, and both closures contain base
tables only, zero views. So the two can be materialized in any order and no ordering information
would change the result.

That property is not preserved by adding rows. The moment a registered view reads a registered
target, order becomes load-bearing, and the failure is quiet: a populated target holding rows derived
from an empty one, differing from what the view would have returned, with every gate that compares a
target against its view passing on the second run and failing on the first. Getting ahead of that is
what this item is for.

## Settled at the Spec pass: the dependency is derived, and it lives in a relation

Three shapes were on the table at this pass. The Backlog draft proposed a *hand-authored* edge
relation beside `meta_materialize`, on the argument that the registry is a statement of intent the
gates close against observed reality, with an agreement gate comparing the authored edges to the
edges a parse of the view bodies finds. A principles consultation inverted that argument, and the
inversion is decisive rather than a preference.

Trace the failure the authored copy is meant to defend against. Call the parsed edge set P and the
authored one A, with a gate asserting A equals P in both directions. If the parse misses a real edge
(the silent failure the authored copy was supposed to catch), the only way to keep the build green
is for A to miss it too; an author who correctly writes the missing edge gets a red build, and the
only green fix is to delete the correct edge. The parse's fidelity is exactly the design's fidelity
either way, so the authored relation buys no protection; it adds a hand-maintained copy of a
derivable fact, which is the drift shape the fact model's own doctrine refuses. The store already
holds the precedent for the correct shape: `meta_relation_family` is derived from
`INFORMATION_SCHEMA` precisely so that "two mechanisms of different fidelity can never answer the
question differently", in its own comment's words.

The Backlog draft also claimed doctrinal support that does not exist. It quoted "write the
population order explicitly rather than deriving it, since H2 offers no dependency catalog to derive
it from" and attributed the sentence to `fact-model.adoc`; the sentence actually sits in another
roadmap item's body (a transient document, not doctrine), and what `fact-model.adoc` itself says, in
the materialized-view ruling, is the opposite polarity: a refresh chain whose ordering is
hand-maintained is a shape the store refuses, the universe of relations coming from the booted store
and never from reading the DDL.

The first Spec draft over-corrected into the third shape: derive the edges but keep them only as a
Java computation, no relation at all. The settled design pulls back to the middle: the edges are
derived (the parse is the single source, so the inversion argument cannot apply; there is no second
spelling to disagree with) *and* they live in a relation the parse populates. Two reasons, both from
the fact model's own doctrine. A derivation gets a relational home as soon as a second reader asks,
and here three do: the materializer orders by it, the gate asserts over it, and a person debugging a
refresh can `SELECT` it instead of re-running a parser in their head. And the dependency is a
function of the DDL alone, so there is no reason to parse the view bodies on every refresh; parse
once per booted store, store the rows, and let every refresh read them. The order is a function of
the schema's own catalog, computed from it in one place, materialized into a relation whose only
writer is that computation.

## What it adds

**`meta_materialize_dependency (source_view_name, depends_on)`, a derived relation with one
mechanical writer.** Both columns reference `meta_materialize (source_view_name)`, which is that
table's primary key, so no scaffolding constraint is needed to make the references legal; the pair
is the primary key, and a `CHECK (source_view_name <> depends_on)` refuses the length-one cycle
declaratively. A row asserts: the registration named by `source_view_name` has a source view that
reads, directly or through unregistered intermediate views, the target table of the registration
named by `depends_on`, so `depends_on` must refresh first. The DDL ships the relation empty with a
comment saying who writes it; no hand ever inserts a row.

**The population routine, in `graphitron-model` main beside `Materializations`.** For each
registration, take the stored view definition from `INFORMATION_SCHEMA.VIEWS`, parse it with jOOQ's
SQL parser, and collect the table references from the query object model rather than from text; a
reference that is an unregistered view recurses into that view's definition, a reference that is a
registered target becomes a row, and base tables end the walk. This replaces the word-boundary regex
`MaterializeRegistryGateTest.closureOf` scans with today: an AST walk has no false positives to
disclaim, and a view definition the parser refuses is a loud failure at population rather than a
silently missing edge. (One thing for implementation to verify, expected to hold: H2's normalized
`VIEW_DEFINITION` output parses under jOOQ's H2 dialect settings; the gate's fixtures make the
verification mechanical.) The dependency is a function of the DDL alone, so the routine runs once
per booted store, before the first refresh, rewriting the relation idempotently in a deterministic
row order; refreshes and gates read rows and never re-parse.

**A topological refresh order, typed apart from the census.** `Materializations.registrations`
today returns a `List` whose alphabetical order every caller happens to be indifferent to; after
this item the order is a correctness property for the refresh and irrelevant to the census readers,
and one `List` cannot serve both without the contract living only in javadoc. Keep the census
reader as it is, and add a distinct producer for the ordered sequence (a `refreshOrder` method
returning its own type is the sketch; the implementer names it). Kahn's algorithm over the
relation's rows with an alphabetical tie-break on `source_view_name`, so that a row-free relation
yields exactly today's order and the refresh stays deterministic. Both `refresh` and `refreshAll`
consume it.

**Gates.** The acyclicity enforcer is the build-time gate, in `MaterializeRegistryGateTest`, over
the populated relation; the materializer additionally refuses a cycle at refresh time with a message
naming it, but that throw is defense in depth, not the invariant's home, since every store boots the
same DDL the gate ran against. `theRegistryNeedsNoOrderingYet` retires: the condition it forbids
becomes the condition the order exists to handle. Its replacement pins the new claims: the populated
relation is acyclic, and the refresh order respects every row.

## A cycle is expressible, and it is a registration error

The Backlog draft asserted a cycle is impossible because H2 rejects a recursive view definition.
That is true of views and false of registrations, and materialization is exactly what opens the
gap: once `intent_x` and `intent_y` are tables, `intent_x_live` reading `intent_y` while
`intent_y_live` reads `intent_x` is legal DDL, no view recursion anywhere. It is still an error,
now for a semantic reason rather than a syntactic one: no refresh order makes both targets equal
their views on a settled store, each needing the other current first, so the equality the whole
mechanism rests on is unsatisfiable. The gate fails the build on it and says so in these terms.

## The stratum is wider than the registry, and this item says which part it closes

The capture-cadence derivation stratum has five producers, ordered today by Java statement order in
`FactCapture.capture`: the four hand-written derivations (`intent_type_domain`,
`intent_type_backing_class`, `intent_input_occurrence_path` and its step sibling), then
`Materializations.refresh`. This item orders the registry's interior. Of the two cross-boundary
directions:

* **A registered view reading a hand-written table** is safe by the existing statement order, the
  hand-written producers running first, and the population walk sees such a read, so the safe
  direction is checkable: the gate asserts no *ordering* need crosses the boundary the wrong way,
  which today means nothing more than that the hand-written tables are not registered targets.
* **A hand-written derivation reading a registered target** would read the previous run's rows, and
  is not derivable from the catalog: those reads are jOOQ code, not view bodies. This direction
  stays uncovered, disclosed here and in one sentence on the stratum comment in `FactCapture`,
  because the alternative is a hand-maintained enumeration of Java read sites, which is the same
  copy-of-derivable-truth shape rejected above, now with no parse to close it against.

## Granularity needs no machinery

R742 refreshes a graph's partition for a graph-keyed target and the whole relation for one with no
graph in its key. Ordering composes with both shapes without new mechanism, and the reasoning
belongs in the materializer's javadoc rather than only here: within one refresh pass, a
prerequisite is always fully refreshed in the scope being refreshed before a dependent reads it,
and rows outside the current scope are current already, each capture having refreshed its own
partition inside its own transaction. That covers all four pairings, including the two mixed ones:
a partitioned dependent of a whole prerequisite sees the prerequisite refreshed whole first, and a
whole dependent of a partitioned prerequisite sees the current partition fresh and the sibling
partitions current from their own captures. `refreshAll` keeps its shape, all graphs per
registration, with only the registration sequence reordered.

## DDL and documentation changes

* **`UNIQUE` on `meta_materialize.target_table_name`**, on its own merits rather than as
  scaffolding: two registrations filling one target is nonsense and nothing currently rejects it.
* **The `meta_materialize` table comment's closing sentence** promises "an ordering column is the
  additive change that lifts it"; this item lifts it with a sibling relation instead, so the
  sentence is rewritten to point at `meta_materialize_dependency` and say who populates it.
* **`fact-model.adoc`'s materialized-view ruling** counts hand-maintained refresh ordering as a
  cost of materialization. The ruling's objection was to an ordering with no derivable source; the
  reduction's order is single-sourced from the catalog, and the page gains the sentence that
  reconciles the two so it stops reading as refusing what the store does.
* **`meta_materialize_dependency` pays the usual residency costs.** The census arm, the comment
  budget, and the `meta_` charter sentence, which should name it as the family's machine-written
  resident: unlike its authored siblings its rows come from one routine and a hand edit is a bug,
  the same standing `meta_relation_family`'s comment claims for its derivation, here as a table
  because transitive reach through unregistered intermediate views is not a plain SQL view's to
  express. Rows carry no `graph_name` and are whole-relation by nature, DDL-derived rather than
  capture-derived, so the partition question does not arise; the determinism ratchet sees identical
  rows run to run because the population order is fixed.

## Tests

`graphitron-model`'s seeded-store tier is the sanctioned home, and synthetic registrations are the
instrument, since zero dependent registrations exist in production DDL and this item deliberately
adds none. A scratch store can `CREATE` ordinary tables and views and `INSERT` rows into
`meta_materialize`, which is enough to pin:

* population itself: the parse finds the direct edge, finds the edge that runs through an
  unregistered intermediate view (the transitivity the walk exists for), writes no row for a view
  reading only base tables, and rewrites identical rows when run twice on the same store;
* a dependent target refreshed after its prerequisite, observed through rows (the dependent's rows
  derive from the prerequisite's fresh rows, not its stale ones), in both `refresh` and
  `refreshAll`;
* a registered cycle failing, with the cycle named;
* a row-free relation refreshing in exactly today's alphabetical order.

One case sits outside the synthetic tier, recorded at the head of this item: the
`FactSchemaGateTest.everyMaterializedTargetEqualsItsRule` fixture gains a registration whose view
reads another registration's target, the shape `intent_resolved_type_binding` will land with, so the
pipeline gate exercises the sort on real store machinery and fails on the concrete error above
before the sort exists.

If a fixture reaches for temporary tables, say `LOCAL` explicitly; H2's bare
`CREATE TEMPORARY TABLE` defaults to `GLOBAL`, which shares rows across attached sessions.

## Deliverables

1. The DDL: `meta_materialize_dependency` with its two references onto the registry's key, the
   composite primary key, the self-edge `CHECK`, its comment, plus the `UNIQUE` on
   `meta_materialize.target_table_name` and the registry comment rewrite.
2. The population routine in `graphitron-model` main, jOOQ-parser based, transitive through
   unregistered views, idempotent and deterministic, run once per booted store before the first
   refresh; the gate test's regex `closureOf` retires into it.
3. The typed refresh order in `Materializations`, Kahn over the relation's rows with alphabetical
   tie-break, consumed by `refresh` and `refreshAll`, refusing a cycle defensively.
4. The gates: acyclicity and order-respects-rows replacing `theRegistryNeedsNoOrderingYet`, the new
   relation's census and charter arms, and the stratum disclosure sentence on `FactCapture`'s
   derivation comment.
5. The `fact-model.adoc` reconciliation.
6. The synthetic-registration tests above.

## What this item is not

It is not the question of which relations to materialize; that is R742's, informed by its
multiplicity check. It is not a performance item at all: on the registry R742 lands, adding ordering
changes no timing whatsoever. It buys the ability to register a relation whose view reads another
target, which is the first thing a third or fourth registration is likely to want.

*It was the first thing, on the first attempt, and it is worth 7.4 seconds on one test class and 84%
of the store's per-run read cost. The section at the top of this item carries the measurement; read
the second sentence here as "changes no timing itself" rather than as "no timing depends on it".*

## Retired vocabulary

* `theRegistryNeedsNoOrderingYet` (a `MaterializeRegistryGateTest` case): the no-dependency claim it
  pinned stops being an invariant and becomes the empty case of the derived order.
* The test-local `closureOf` in `MaterializeRegistryGateTest`, and with it the word-boundary regex
  scan of `VIEW_DEFINITION` text, both absorbed into the jOOQ-parser population routine.
