---
id: R746
title: "Order the materialization registry, so a target may be derived from another target"
status: Backlog
bucket: dx
priority: 4
theme: tooling
depends-on: [determinism-ratchet-run-count]
created: 2026-08-20
last-updated: 2026-08-20
---

# Order the materialization registry, so a target may be derived from another target

R742 lands `meta_materialize (view_name, table_name)` and a `graphitron-model` method that iterates
it, emptying each target and refilling it from its view. The registry has two columns and the
materializer has no notion of order, which is correct for what R742 registers and is not correct in
general.

This item adds the order. It is strictly additive: no relation R742 registers changes, no reader
changes, and a registry with no ordering information keeps behaving exactly as it does today.

## Why R742 could leave it out, stated so this item knows what it is fixing

A materialized target is refilled by `INSERT INTO <table_name> SELECT * FROM <view_name>`. If that
view reads *another* target, then the answer depends on whether the other target was refreshed first,
and an unordered materializer will populate one of them from stale or empty rows. Nothing about the
mechanism prevents this; R742 is safe because of a property of the two rows it registers, not because
of a property of the design.

The property, verified against the DDL: neither `intent_argmapping_pair` nor `intent_spelled_table`
appears in the other's dependency closure, and both closures contain base tables only, zero views. So
the two can be materialized in any order and no ordering information would change the result.

That property is not preserved by adding rows. The moment a registered view reads a registered
target, order becomes load-bearing, and the failure is quiet: a populated target holding rows derived
from an empty one, differing from what the view would have returned, with every gate that compares a
target against its view passing on the second run and failing on the first. Getting ahead of that is
what this item is for.

## What it adds

**Registered edges rather than derived ones.** A view's dependencies are parseable from the DDL, and
R742's multiplicity check already parses them for a different purpose, so deriving the order is
possible. Authored edges are still better here, for the reason `meta_family` and
`meta_prefixless_relation` are authored: the registry is a statement of intent that the schema gates
close against observed reality, and an authored edge that disagrees with the parsed graph is a
finding rather than a silent correction. It also gives `fact-model.adoc`'s instruction to "write the
population order explicitly rather than deriving it, since H2 offers no dependency catalog to derive
it from" a literal home: H2 has no dependency catalog, so the schema carries one.

**A topological sort in the materializer**, replacing the current iteration order, which is whatever
the registry view yields.

**Gates**, each closing authored intent against the observed schema in the shape `meta_relation_family`
already uses:

* the registered edges are acyclic;
* they agree with the edges a parse of the DDL finds, so a registration that omits an edge fails
  rather than producing a target populated from stale inputs;
* every registered edge names two registered relations.

## Worth deciding here rather than assuming

* **Whether the edge set is a separate relation or a column.** A third column on `meta_materialize`
  holding one predecessor is enough only if the dependency graph stays a forest; a sibling relation
  keyed on the pair is the general shape and costs one more `VALUES` view.
* **What a cycle means.** Between *views* a cycle is impossible, H2 rejecting a recursive view
  definition, so a cycle in the registry is a registration error rather than a schema property. That
  makes failing on it straightforward, and it is worth stating why.
* **Whether ordering interacts with the refresh's granularity.** R742 refreshes whole relations, so
  order is the only sequencing concern. If a later item makes any refresh graph-partitioned, a
  partitioned target derived from a whole one, or the reverse, needs its own reasoning.

## What this item is not

It is not the question of which relations to materialize; that is R742's, informed by its
multiplicity check. It is not a performance item at all: on the registry R742 lands, adding ordering
changes no timing whatsoever. It buys the ability to register a relation whose view reads another
target, which is the first thing a third or fourth registration is likely to want.
