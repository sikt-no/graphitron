---
id: R471
title: "Direct-SQL OnlyChild emit for the reentry family"
status: Backlog
bucket: architecture
priority: 5
theme: classification-model
depends-on: []
created: 2026-07-13
last-updated: 2026-07-13
---

# Direct-SQL OnlyChild emit for the reentry family

Owns R463's forward obligation: consume the `Source.OnlyChild` arrival arm as the emit-strategy
dispatch it is documented to be. R463 populated the arm (arrival `One`, direct SQL licensed) but kept
emitters on leaf-identity dispatch, so an `OnlyChild`-classified reentry field still emits a
one-element DataLoader batch. R314 re-platforms the reentry emit onto the model but lands
**arrival-uniform** by decision (settled 2026-07-13): a direct-SQL `OnlyChild` emit changes query
shape and SELECT counts, so it definitionally cannot ship under R314's execution-tier-equivalence
acceptance and needs its own behavioral slice with its own pins. This item is that slice; the
`Source.OnlyChild` javadoc pointer moves here from the R431 → R432 → R314 chain.

Scope, for the eventual Spec body:

- Emit the reentry re-projection as direct SQL (no DataLoader registration, no `VALUES(idx, ...)`
  batching machinery) when the coordinate's source arrival is `OnlyChild`; `Child` keeps the keyed
  re-query. Arrival selects the *strategy* only; the key-lift and correlation facts are unchanged
  (arrival bounds DataLoader invocations, not lift width).
- **The honesty-clause enforcer** (`Source.OnlyChild` javadoc): `One` is a static per-dispatch
  guarantee about unaliased projections; query aliases can materialize `k` parent instances on a
  `One` chain, so the direct emit must stay row-correct at every arrival count — degrading in query
  count, never in rows. The enforcer is an execution-tier test that queries an `OnlyChild` reentry
  field through an alias fan-out and asserts row correctness; per "every invariant has an enforcer"
  this test is a deliverable, not a nice-to-have.
- New execution pins for the changed behavior (SELECT counts on the direct path), replacing the
  DataLoader-coalesced counts the R305/R308 pins assert for these coordinates today.

Blocked on R314: the emit must first switch on the model at all before an arrival arm can select a
strategy. Not urgent — the one-element batch is correct, this is a query-shape optimization plus the
discharge of a recorded obligation.
