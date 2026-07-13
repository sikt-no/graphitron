---
id: R463
title: "Consume R279's ancestor-cardinality rider: fold true arrival and populate Source.OnlyChild"
status: Ready
bucket: structural
theme: classification-model
depends-on: []
created: 2026-07-10
last-updated: 2026-07-13
---

# Consume R279's ancestor-cardinality rider: fold true arrival and populate Source.OnlyChild

Specced 2026-07-10, from the same-day Backlog stub. Every `ChildField` hard-codes its arrival as
`Source.Child` (`ChildField.source()`), the conservative many-arrival absorber; `Source.OnlyChild`
(single arrival, direct SQL instead of a one-element DataLoader batch) is producible but unreached.
This item computes the true ancestor-product arrival fold, populates `Source.OnlyChild` where the
product is One, and lifts the pins that hold the conservative strength.

## Context, verified 2026-07-10

- Two test pins hold the conservative strength and are lifted here:
  `WrapperAlgebraTest.sourceWrapperIsTheFoldOfAncestorTargetWrappers` (every nested field asserts
  `Child`) and `ClassifiedDslTest.SOURCE_KNOWN_GAPS` (the `OnlyChild` corpus-coverage exemption).
- The three in-code forward references (the `ChildField.source()` javadoc, the `Source.OnlyChild`
  javadoc, the `WrapperAlgebraTest` pin comment) still say "R279 / R308"; retargeting them to R463
  is R308's acceptance obligation. If this item lands first it deletes the deferral text outright,
  mooting that line; whichever lands second reconciles the references.
- Consumer audit (done for this spec): no generator reads `OutputField.source()`; emit
  batch-vs-direct dispatch is leaf identity plus the `BatchKeyField` capability. The only
  production consumers are `OutputField.requiresReFetch()` (switches on the arms but treats
  `OnlyChild` and `Child` identically; its verdict is shape-driven and arrival-agnostic) and a
  `GraphitronSchemaValidator` diagnostic message that prints `source()`. Populating `OnlyChild`
  therefore changes no generated code and no validation verdict: this item is model + tests only,
  behaviour-preserving on output.
- R279's "rider" is a design affordance, not code: the R279 changelog entry admits
  ancestor-cardinality accumulation on the down-the-walk context, but nothing was materialized,
  and R317 has since replaced the driver (one `SchemaReachability.walk`, enter fires once per node
  identity, the read-free visitor invariant, cross-cutting facts as typename-keyed fixed-point
  indices threaded as traverser arguments). A per-path down-the-walk accumulator cannot deliver
  the fold anyway: `arrival(T)` depends on every edge reaching T, and the walk classifies T's
  fields on the first path that reaches it. Consuming the rider means building the fold in R317's
  index idiom.

## The fold, defined

Arrival is the lattice One < Many with Many absorbing; the wrapper-algebra monoid (R316,
`dimensional-model-pivot.md` "the wrapper algebra") has `Root` as the empty product, `OnlyChild`
the One identity, `Child` the Many absorber. The independent truth is SDL-computable over the same
edge set `SchemaReachability` walks (field-output edges, union to member, interface to
implementor, object/interface `implements`), on the assembled schema the walk seeds from. A
composite type T's arrival is:

- **Many** if T carries a `@node` / `@key` seed (node and entity lookups arrive batched), or if
  more than one field edge reaches T through the structural closure: two coordinates can
  co-materialize T instances in one request. The multi-edge rule subsumes recursion (a reachable
  cycle implies a second reaching edge), so no fixed point is needed; One verdicts form an acyclic
  tree hanging off the operation roots.
- else, with exactly one reaching field edge (P, f): **arrival(P) tensored with wrapper(f)**,
  where wrapper(f) contributes Many for any list wrapper and One otherwise, and arrival of a root
  operation type is the empty product. A connection target's many-ness arrives through the
  `edges` list edge rather than the connection field itself; `@asConnection` promotion preserves
  the verdict, since the pre-promotion SDL field is a list.
- Structural edges (union to member, interface to/from implementor) propagate arrival unchanged:
  they name the same instances, not additional ones. They do, however, transmit reach for the
  multi-edge test (a field returning the interface and a field returning the implementor
  co-materialize instances of the implementor).

A nested field's source is then `OnlyChild(sourceShape())` iff arrival(parent) = One, else
`Child(sourceShape())`; root fields stay `Root.Query` / `Root.Mutation`, unchanged.

**Honesty clause, to be pinned in the `Source.OnlyChild` javadoc:** One is a static per-dispatch
guarantee about unaliased projections. Query aliases can materialize k parent instances even on a
One chain, so any emit strategy `OnlyChild` ever licenses must stay row-correct at every arrival
count: direct SQL once per invocation, degrading in query count, never in rows. `Child` stays the
absorbing always-correct arm.

## Mechanism (settled with principles-architect, 2026-07-10)

- **Pre-pass index, R317 idiom.** Arrival is computable from the assembled SDL alone (list-ness
  is an SDL fact, no classification needed), so compute it before field classification as a
  typename-keyed index and thread it the way `ctx.tables` / `ctx.nodes` / `ctx.errors` are
  threaded. Computing it during the walk via register-reconcile would read partial in-flight
  registry state, violating the read-free visitor invariant, and the enter-once walk cannot see
  fan-in at visit time. The index is keyed by typename with a plain arrival value; no graphql-java
  types survive into it (parse-boundary containment).
- **Parent-type grain, single home, no leaf copy.** Arrival is a function of the parent typename
  alone: every `ChildField` on one parent folds the same way. It is therefore not stored as a
  component on the ~25 leaves (a parent-grain fact copied to child grain is the derived-fact
  drift smell). `ChildField.source()` stays a derived, storage-free view taking the parent
  arrival as input: `source(Arrival)` on `OutputField` with root leaves ignoring the argument (the
  empty product needs no ancestor fact) and `GraphitronSchema` carrying the index consumers read
  from; a schema-level `sourceOf(coordinate)` accessor is an acceptable equivalent seam, a stored
  per-leaf component is not. `requiresReFetch()` is reworked to read the leaf's source shape
  directly; its own switch already treats the two nested arms identically.

## Scope

- Classification model + tests only. Emitters keep leaf-identity dispatch; an
  OnlyChild-classified `BatchKeyField` still emits its DataLoader (a one-element batch, always
  correct), so generated output is unchanged.
- **The emit split, settled here as the stub asked:** the `OnlyChild` direct-SQL emit strategy is
  out of scope and rides the R431 -> R432 -> R314 emit re-platforming chain, where batch-ness
  stops being baked into leaf identity. Forward obligation recorded for that chain: the moment an
  emit branch consumes `OnlyChild`, the derivation needs an enforcer on the emit side (a validator
  mirror, or R314 goal 3's by-construction non-drift, which is the preferred direction).
- R308 (In Progress as of 2026-07-10) models carrier arrival on the `@service` payload seat only;
  its seat-level fact and
  this fold must agree where both are defined. Both read the same SDL wrapper, so agreement is by
  construction; whichever item lands second states it.

## Acceptance

- The `WrapperAlgebraTest` pin is lifted. The source half asserts the laws that stay
  independently checkable without reimplementing the fold (a faithful test-side mirror would be a
  drifting second copy; a simplified one would be vacuous): the Root law (SDL
  root-operation-type mirror, unchanged), the grain law (every `OutputField` on one parent type
  carries the same arrival arm), and the coverage floor now requiring `OnlyChild`, `Child`, and a
  `Root` arm all observed across the corpus.
- Per-field One/Many truth is hand-asserted corpus rows: `@classified(source:)` rows flip to
  `OnlyChild` where the fold yields One (the DSL already parses the arm), `SOURCE_KNOWN_GAPS`
  loses its `OnlyChild` entry, and new fixtures pin the fold's edge cases with hand-asserted
  arrivals: deep single chain (One), list ancestor (Many), fan-in of two single edges (Many),
  recursion (Many), a `@node`-seeded type (Many), a connection ancestor (Many), and a single
  mutation payload carrier (One: the payload data field becomes `OnlyChild(Record)`).
- Generated output unchanged: the sakila pipeline `TypeSpec` tier, the Java-17
  `graphitron-sakila-example` compile, and the PostgreSQL execution tier stay green with no
  generated-code diffs.
- Full reactor green (`mvn install -Plocal-db`), graphitron-lsp included.
