---
id: R746
title: "Order the materialization registry, so a target may be derived from another target"
status: Spec
bucket: dx
priority: 4
theme: tooling
depends-on: [determinism-ratchet-run-count]
created: 2026-08-20
last-updated: 2026-08-20
---

# Order the materialization registry, so a target may be derived from another target

R742 lands `meta_materialize (source_view_name, target_table_name, reason)`, a constrained table,
and a `graphitron-model` method that iterates it, refreshing each target from its view inside the
capture transaction and scoped to the graph being captured. The registry records no ordering, which
is correct for what R742 registers and is not correct in general.

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

## Settled at the Spec pass: the order is derived, not authored

The Backlog draft of this item leaned the other way. It proposed an authored edge relation beside
`meta_materialize`, on the argument that the registry is a statement of intent the gates close
against observed reality, with an agreement gate comparing the authored edges to the edges a parse
of the view bodies finds. A principles consultation at this pass inverted that argument, and the
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
and never from reading the DDL. The design below is the one that page sanctions: the order is a
function of the schema's own catalog, computed from it in one place.

## What it adds

**A single-sourced dependency computation in `graphitron-model` main.**
`MaterializeRegistryGateTest.closureOf` already computes each registered view's transitive
dependency closure from the engine's stored view definitions (`INFORMATION_SCHEMA.VIEWS`), reaching
through unregistered intermediate views and stopping at base tables, which is exactly the reach the
order needs: a registered target is a table, so it surfaces in the closure of any registered view
that reads it, directly or through helper views. Promote that computation out of the test and into
main, beside `Materializations`, as the one answer to "which registrations must refresh before this
one". Both the materializer and the gates read it; the test-local copy retires. An edge exists from
registration A to registration B exactly when B's source view's closure contains A's target.

One fidelity question to verify during implementation, with both outcomes acceptable: H2 stores a
normalized `VIEW_DEFINITION` whose identifiers are quoted, so extracting quoted-identifier tokens
may be an exact tokenization rather than a word-boundary regex over text. If it is, tokenize and the
heuristic-scan worry dissolves; if it is not, keep the regex the existing gate uses and state in the
computation's javadoc that a false positive needs a relation's name appearing in a view body meaning
something else.

**A topological refresh order, typed apart from the census.** `Materializations.registrations`
today returns a `List` whose alphabetical order every caller happens to be indifferent to; after
this item the order is a correctness property for the refresh and irrelevant to the census readers,
and one `List` cannot serve both without the contract living only in javadoc. Keep the census
reader as it is, and add a distinct producer for the ordered sequence (a `refreshOrder` method
returning its own type is the sketch; the implementer names it). Kahn's algorithm with an
alphabetical tie-break on `source_view_name`, so that an edge-free registry yields exactly today's
order and the refresh stays deterministic. Both `refresh` and `refreshAll` consume it.

**Gates.** The acyclicity enforcer is the build-time gate, in `MaterializeRegistryGateTest`, over
the derived edges; the materializer additionally refuses a cycle at refresh time with a message
naming it, but that throw is defense in depth, not the invariant's home, since every store boots the
same DDL the gate ran against. `theRegistryNeedsNoOrderingYet` retires: the condition it forbids
becomes the condition the order exists to handle. Its replacement pins the new claims: the derived
edge set is acyclic, and the refresh order respects every derived edge.

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
  hand-written producers running first, and the derived closure sees such a read, so the safe
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
  additive change that lifts it"; this item lifts it without one, so the sentence is rewritten to
  say the order is derived from the registered views' own bodies and where the computation lives.
* **`fact-model.adoc`'s materialized-view ruling** counts hand-maintained refresh ordering as a
  cost of materialization. The ruling's objection was to an ordering with no derivable source; the
  reduction's order is single-sourced from the catalog, and the page gains the sentence that
  reconciles the two so it stops reading as refusing what the store does.
* **No new relation lands.** The derived design needs no edge table and no edge view (a relational
  form was considered; transitive reach through unregistered intermediate views is what a plain SQL
  view cannot express cleanly, and a recursive one buys nothing over the Java computation the gate
  already trusts). So no census arm, no comment budget, no `meta_` charter edit, no
  partition-dimension case: the schema footprint is one constraint and one comment edit.

## Tests

`graphitron-model`'s seeded-store tier is the sanctioned home, and synthetic registrations are the
instrument, since zero dependent registrations exist in production DDL and this item deliberately
adds none. A scratch store can `CREATE` ordinary tables and views and `INSERT` rows into
`meta_materialize`, which is enough to pin:

* a dependent target refreshed after its prerequisite, observed through rows (the dependent's rows
  derive from the prerequisite's fresh rows, not its stale ones), in both `refresh` and
  `refreshAll`;
* an edge found through an unregistered intermediate view, which is the transitivity the closure
  exists for;
* a registered cycle failing, with the cycle named;
* an edge-free registry refreshing in exactly today's alphabetical order.

If a fixture reaches for temporary tables, say `LOCAL` explicitly; H2's bare
`CREATE TEMPORARY TABLE` defaults to `GLOBAL`, which shares rows across attached sessions.

## Deliverables

1. The dependency computation, promoted from the gate test into `graphitron-model` main as the
   single source, with the tokenization question above verified and recorded in its javadoc.
2. The typed refresh order in `Materializations`, Kahn with alphabetical tie-break, consumed by
   `refresh` and `refreshAll`, refusing a cycle defensively.
3. The gates: acyclicity and order-respects-edges replacing `theRegistryNeedsNoOrderingYet`; the
   stratum disclosure sentence on `FactCapture`'s derivation comment.
4. The `UNIQUE` constraint, the `meta_materialize` comment rewrite, and the `fact-model.adoc`
   reconciliation.
5. The synthetic-registration tests above.

## What this item is not

It is not the question of which relations to materialize; that is R742's, informed by its
multiplicity check. It is not a performance item at all: on the registry R742 lands, adding ordering
changes no timing whatsoever. It buys the ability to register a relation whose view reads another
target, which is the first thing a third or fourth registration is likely to want.

## Retired vocabulary

* `theRegistryNeedsNoOrderingYet` (a `MaterializeRegistryGateTest` case): the no-dependency claim it
  pinned stops being an invariant and becomes the empty case of the derived order.
* The test-local `closureOf` in `MaterializeRegistryGateTest`, absorbed into the promoted
  computation.
