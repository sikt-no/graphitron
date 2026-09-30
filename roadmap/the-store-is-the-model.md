---
id: R983
title: "The store is the model and a query uses it: capture and anchor are the phases of every gatherer, and a view is part of the model"
status: Backlog
bucket: architecture
priority: 2
theme: model-cleanup
depends-on: []
created: 2026-09-28
last-updated: 2026-09-28
---

# The store is the model and a query uses it: capture and anchor are the phases of every gatherer, and a view is part of the model

## Goal

**The store is the model, and using the model means writing queries against the store.** There are
no strata between the two. Gatherers build the model and run in a declared order. Each captures its
own corpus in two phases: the *capture* phase transcribes the corpus, and the *anchor* phase derives
from what was captured and stores the result in tables. A gatherer's anchor phase may read both the
captured and the anchored relations of every gatherer upstream of it, never one downstream. When
this lands, a contributor meets one account of where a fact comes from: which gatherer owns it and
which of its two phases writes it, and the documentation and code no longer speak of strata.

**A view is part of the model.** A view is a derivation that several queries find useful but that
establishes no grain, so it is cheap to join against and owes no keys. A view over a view over a view
is a sign that a grain is missing, and a missing grain calls for an anchor, a table with a primary
key and foreign keys, rather than another layer.

## What changes

A Backlog sketch, not a plan; the plan is written when the item moves to Spec.

* `docs/architecture/explanation/fact-model.adoc` § "The three strata" is replaced by the account
  above: the model, the gatherers that build it and their two phases, the upstream-read rule, views
  as part of the model with the view-stack signal, and queries as the use of it. The
  numbered-versus-unnumbered rule for "stratum" goes with the word. `modeling-discipline.adoc` stops
  calling provenance "stratum one against stratum two" and says capture phase against anchor phase.
* `DerivationStratum`, which describes itself as the graphitron gatherer's derivation stratum, is
  renamed to say that it is the graphitron gatherer's anchor phase. The obvious name collides with
  `GraphitronAnchor` in `capture/document`, so the name is settled at Spec. Its javadoc, the DDL
  comments, `StageOrderGateTest`, `StageProgress` and the other readers follow.
* The "Enforced by:" paragraph that closes `fact-model.adoc` § "Provenance: every source is a fact,
  and the resolved value is another" is restated. It registers "the whole `intent_` stratum" and
  names a store seeded row by row as what pins a view's output, a shape R876's seeding branch rules
  out. A view's output given rows is pinned by fact documents under
  `graphitron-model/src/test/resources/facts`, captured against the sakila catalog and classpath and
  run by `FactExpectationTest`; seeding survives only as the shrinking `SeedingDissolutionGateTest`.
  The seeded class names in that paragraph go, since they rot as the classes convert.
* Every other use of "stratum" or "strata" is reworded to what it names: a phase, a family, an arm
  set, or a section of the DDL.

## Retired vocabulary

* "stratum", "strata", in every form: "stratum one/two/three", "the three strata", "derivation
  stratum", "derived stratum", "capture stratum", "claim stratum", "diagnostics stratum"
* `DerivationStratum`
