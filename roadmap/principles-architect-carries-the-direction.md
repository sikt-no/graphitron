---
id: R1163
title: "The principles architect carries the direction: the rulings live in the docs it reads, and every design is judged by what it lets us delete"
status: Backlog
bucket: dx
theme: tooling
depends-on: []
created: 2026-11-09
last-updated: 2026-11-09
---

# The principles architect carries the direction: the rulings live in the docs it reads, and every design is judged by what it lets us delete

## Goal

Every session working on the rewrite, on this machine, in the cloud or elsewhere, is steered by the same direction, because the principles architect (the read-only `principles-architect` subagent an author consults while drafting) carries it. Today the design rulings Alf and Filip give reach a session only when someone relays them: most live in local agent memory on one machine, so sessions elsewhere never see them, and their work drifts and is caught late, at harvest or in CI. When this lands, each ruling lives in the published architecture docs the architect reads on every invocation, stated once with why and how to apply it, in the doc it belongs to (much of the direction is already there: `code-generation-triggers.adoc` for the occurrence model, `testing.adoc` and `development-principles.adoc` for the testing discipline). The architect judges a design by where the code is going, asking what it lets us delete and what dissolves, and names the store-side shape wherever a design extends the classified model, the walk, the `intent_` family or the derivation family. Consulting it is a named step in the workflow, before a node is taken up, at a design fork and before work is handed over for harvest. The architect stays advisory and read-only; the author weighs its advice, and the verdict stays with Alf and Filip, who work with the author. A new ruling enters the docs the day it is given, through an ordinary commit to trunk.

## Decided

* **R1163 rewrites the strata account and absorbs R983** (Alf, 2026-11-09). The store is the model and a
  query uses it: gatherers build the model in a declared order, each in a capture phase and an anchor
  phase, an anchor phase reads only upstream, and the docs stop speaking of strata. What R983 sketched
  moves here whole:
  * `fact-model.adoc` § "The three strata" is replaced by that account, with views as part of the
    model and the view-stack signal; `modeling-discipline.adoc` says capture phase against anchor phase
    where it said stratum one against stratum two.
  * The "Enforced by:" paragraph closing `fact-model.adoc` § "Provenance: every source is a fact, and
    the resolved value is another" is restated: a view's output is pinned by fact documents under
    `graphitron-model/src/test/resources/facts`, run by `FactExpectationTest`, not by a store seeded
    row by row, and the seeded class names go.
  * Every other "stratum" or "strata" is reworded to what it names: a phase, a family, a set of rules,
    or a section of the DDL. `DerivationStratum` itself is R876's to dissolve; this item rewrites the
    prose, DDL comments and javadoc around it so nothing calls it a stratum while it stands.
* **The store's defect model is authoritative for validation** (Alf, 2026-11-09). `defects-as-facts.adoc`
  is stale where it says a stored defect and a walk rejection both report until the walk's goes, and
  that most defects do not fail the build: `validate` and `generate` read the store's verdict first and
  stop on it, so an author meets one error. The page is updated here.

## Open

* **Relation, anchor and rule view** (Alf, 2026-11-09, being worked through). Experience says a fact is
  captured into a relation so it has referential integrity, which brings mark and sweep with it, and a
  derivation rule is usually best stated as a rule view. `fact-model.adoc` § "Derived reads are views,
  not stored facts" and the architect's "A derivation stored where a view belongs" pull the other way;
  the balance the architect carries is settled with Alf before the page is rewritten.

## Retired vocabulary

* "stratum", "strata", in every form: "stratum one/two/three", "the three strata", "derivation
  stratum", "derived stratum", "capture stratum", "claim stratum", "diagnostics stratum"
* `DerivationStratum`, retired by R876 rather than by this item
