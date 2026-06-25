---
id: R381
title: "LSP-guided @reference path authoring"
status: Spec
bucket: architecture
priority: 5
theme: structural-refactor
depends-on: [reference-terminal-hop-target-validation]
created: 2026-06-25
last-updated: 2026-06-25
---

# LSP-guided @reference path authoring

> Authoring an `@reference` path is error-prone: the developer hand-writes a
> chain of `{key:}` / `{table:}` / `{condition:}` hops and only finds out at
> build time (or, before R379, at javac time on generated code) whether the
> chain actually walks from the enclosing type's table to the field return
> type's table. The LSP already suggests FK constants, but suggests the *same
> set at every step* regardless of where prior hops landed, so it cannot guide
> a multi-hop path and cannot steer the terminal hop onto the return table. This
> item makes path authoring incremental and guided: at each step the editor
> suggests only what is reachable from the resolved source so far, steers the
> terminal hop onto the return-type table, flags a wrong terminal hop before
> build, and (rung 4) offers a reviewable code action that writes a full path.
>
> The load-bearing constraint: this must not become a *second* implementation of
> `@reference` path-walking semantics that drifts from `BuildContext.parsePath`
> (the R110/R119 failure mode). One owner walks the path; the LSP consumes a
> projection of it; a drift test pins the consumption.

This is the prevention layer paired with R379's safety net (build-time reject of
a terminal hop that misses the return table). R379 ships first and independently;
this item's diagnostic and ranking rungs consume R379's terminal-target verdict.

## What exists today

- `ReferenceCompletions` (`graphitron-lsp/.../completions/ReferenceCompletions.java`)
  serves the `@reference(path: [{key: }])` slot via the `Behavior.CatalogFkBinding`
  overlay. It suggests the FK constants connecting the *enclosing type's* `@table`
  to other tables (inbound and outbound, rendered with `→`/`←` target detail).
  Its own Javadoc states the gap: *"Path-step refinement (narrowing later steps
  by where the previous step landed) is not yet implemented; every step suggests
  the same set."*
- `TableCompletions` serves `{table:}` (`Behavior.CatalogTableBinding`);
  `ClassNameCompletions` / `MethodCompletions` serve `{condition:}`.
- `CompletionData.Table.references()` already carries the full FK graph
  (`targetTable`, `keyName`, `inverse`, `keysClassFqn`) in the snapshot the LSP
  queries. The data to walk a multi-hop path is present; the walking is not.
- `CompletionContext` carries the coordinate + replace-range + directive name,
  but **not** the path index or the values of prior path elements. Step
  refinement needs those; the provider already receives the directive AST node
  (`Directives.Directive directive`), so it can read sibling array elements.
- `CodeActions` (`graphitron-lsp/.../code_action/CodeActions.java`) is the
  existing code-action entry point (rung 4's host).
- The generator-side authority is `BuildContext.parsePath` / `parsePathElement`
  (`BuildContext.java:1131`/`:1389`), operating on `GraphQLDirectiveContainer` +
  live `JooqCatalog`, tracking `currentSource` through `JoinStep.HasTargetTable`.

## The substrate: one owned path-walker, two worlds

The principle risk (confirmed in architectural review) is that rungs 1, 2, and 4
each "replay the prior hops over the FK graph to resolve the current source
table." Done naively in the LSP, that is a second path-walker that will drift
from `parsePath` exactly as the LSP's hand-maintained `DirectiveDefinitions`
drifted from `directives.graphqls` after R110, the gap R119 had to dig out by
consolidating onto a single source plus a `DriftDetectionTest`. A shared
`(path, return-table) → verdict` corpus does **not** contain this: it pins the
*terminal* verdict but not the *intermediate `currentSource` trajectory* the
walker computes at every step, where FK-direction and source-advance bugs live.

Containment, the R119 pattern applied at the walker altitude:

1. **One stepping function, parameterised over an FK-graph abstraction.** The
   per-hop logic in `parsePathElement` is small and direction-aware: for `{key}`
   it resolves the FK by name, checks it touches `currentSource` (either the
   FK-side or the key-side table), and advances to the other side
   (`BuildContext.java:1406-1441`); for `{table}` it finds the unique FK between
   `currentSource` and the named table and advances (`:1443-1471`); for
   `{condition}` the target is the field's `@table` (terminal) or the method's
   second parameter (intermediate). The two worlds' FK data are isomorphic:
   `org.jooq.ForeignKey` exposes `getTable()` / `getKey().getTable()` /
   `getName()`; `CompletionData.Reference` exposes `(targetTable, keyName,
   inverse)`. Lift a pure `step(currentSource, hopElement) → Resolved(nextSource)
   | Unresolved(reason)` over a narrow `FkEdge` abstraction (source table, target
   table, constraint name, direction), implemented once for the live catalog and
   once for the snapshot. `BuildContext.parsePathElement` is refactored to call
   the lifted stepper for the source-advance decision; the LSP calls the same
   stepper against the snapshot's edges.
2. **Drift test as the guard.** A `ReferencePathWalkerDriftTest` (the R119
   `DriftDetectionTest` analogue) runs the *real* walker and the *snapshot*
   walker over the same fixture paths and asserts identical trajectories
   (per-step resolved source, terminal target, and rejection reason). Drift is a
   build failure, not a silent wrong completion. The hand-authored verdict
   corpus, if kept, is a readability aid on top of this mechanical guard, never
   the guard itself.
3. **R233 projection-sharing for the terminal verdict.** R379 produces the
   terminal-target verdict as a typed value (the structural hook R379's spec
   commits to). The LSP consumes that same projection via the snapshot rather
   than re-deriving it, the same move R233 made when it lifted
   `FieldClassification.lspColumnDispatch()` so the runtime validator arm and the
   LSP arm read one projected value.

Whether the stepper is fully lifted (cleanest) or the snapshot carries a
precomputed replayed-trajectory primitive that the LSP reads (lighter on the LSP
side, heavier on the snapshot) is an implementation choice settled in Slice A;
either way the drift test is the non-negotiable guard.

## Slices

Substrate-first, not rung-by-rung: every rung depends on the owned walker, so the
walker (with its drift guard) is the load-bearing artifact and ships with its
first consumer.

### Slice A: owned walker + drift guard + rung 1 (step-refined completion)

- Lift the stepping function over `FkEdge`; implement for `JooqCatalog` and the
  `CompletionData` snapshot; refactor `parsePathElement` to consume it.
- `ReferencePathWalkerDriftTest` pinning identical trajectories across both
  implementations.
- Rung 1: `ReferenceCompletions` resolves the current source table by replaying
  the prior path elements (read from the directive AST it already receives)
  through the snapshot stepper, then suggests only the FK constants / tables
  reachable from *that* table. Replace the "same set at every step" behaviour;
  update the class Javadoc that documents the gap. `{table:}` completion in
  `TableCompletions` gets the same refinement.

Rung 1 is independently valuable and is the smallest honest exercise of the
substrate, so it earns the substrate's pipeline-tier coverage.

### Slice B: rungs 2-3 (terminal-aware ranking + edit-time diagnostic)

Depends on R379's terminal-target verdict hook.

- Rung 2: at the terminal step, the field return type is known, so its `@table`
  is known. Rank-first (or solo-suggest) the FK constant / table that bridges the
  resolved penultimate source to the return-type table. This is R379's invariant
  surfaced as a positive suggestion instead of a rejection.
- Rung 3: run R379's terminal-target verdict at edit time and publish an LSP
  diagnostic on a terminal hop that does not resolve to the return table, before
  build. Consumes R379's typed verdict via the snapshot (R233 pattern); does not
  re-evaluate the predicate.

### Slice C: rung 4 (whole-path code action)

Gated on Slice A's walker being the single owner (rung 4 runs an FK-graph search;
it must use the owned walker or it becomes a third implementation).

- Cursor on `@reference(` with a known start table (enclosing `@table`) and known
  return-type table: search the FK graph for a chain from start to target and
  insert the full `path: [...]` as a `TextEdit` the author reviews.
- **Two guardrails keep this on the right side of "the generator does not
  guess":** (a) it is an author-invoked code action that writes reviewable SDL
  text, never a save-time fixup, so the path ends up explicitly authored; (b) it
  **declines on ambiguity**, presenting alternatives rather than silently picking
  among equally-valid FK chains. Unique/shortest chain → offer it; multiple
  equally-valid chains → present the options or decline; no chain → no action.
  The moment it picks silently, it is guessing in the authored artifact, the
  thing the principle forbids, relocated to the editor.

## User documentation (first-client check)

The user surface is editor behaviour, documented in the LSP / dev-loop docs
(`graphitron-rewrite/docs/getting-started.adoc` "Dev loop", and the `@reference`
how-to / reference pages). Draft, to be moved into its real home when the
feature ships:

> *Authoring a `@reference` path.* As you fill in each hop, completion suggests
> only the foreign keys and tables reachable from where the previous hop landed,
> so a multi-hop path narrows step by step instead of offering every FK on the
> starting table at every position. On the final hop, the key or table that
> connects to the field's return type is suggested first. If a path's last hop
> lands on the wrong table, the editor flags it inline before you build. With the
> cursor inside an empty `@reference(...)`, the "complete reference path" action
> fills in a full path to the return type's table when a single route exists; when
> several routes exist it lists them for you to choose, and it never picks one for
> you.

If that paragraph does not read simply, the design is wrong and changes before
implementation. Note the absence of process vocabulary (`R<n>`, "rung", "slice")
per the user-facing-doc check.

## Tests

Per `testing.adoc`, pipeline-tier is primary; the LSP module's structural tests
carry `@UnitTier`.

- `ReferencePathWalkerDriftTest` (the guard): identical trajectories from the
  live walker and the snapshot walker over a fixture corpus covering each hop
  kind, multi-hop chains, self-referential FKs (the `category.parent` /
  `category.children` direction case `parsePath`'s `isList` arg disambiguates),
  inbound vs outbound FKs, and unresolvable hops.
- Rung 1 completion tests (extend `ReferenceCompletionsTest` /
  `TableCompletionsTest`): after one resolved hop, the next step's suggestions
  are the FKs/tables of the *landed* table, not the starting table. Pin the
  `NUSFAGGRUPPE`-shaped multi-hop case (mapped onto a sakila fixture) so the
  R379 reproduction is positively guided here.
- Rung 2 tests: terminal step ranks the bridging key/table first for a known
  return type.
- Rung 3 tests (extend the LSP diagnostics suite): a wrong terminal hop publishes
  a diagnostic whose message matches R379's verdict; a correct one publishes
  none. Assert the LSP diagnostic and the R379 build rejection agree on the same
  fixtures (the projection-sharing claim made live).
- Rung 4 tests (extend `CodeActionsTest`): unique chain → one `TextEdit` with the
  full path; multiple chains → multiple offered actions (or a decline); no chain
  → no action. Assert the inserted text round-trips through `parsePath` to a
  valid classification (the action's output is authored SDL that the generator
  accepts).

## Open questions for the reviewer

1. **Stepper lift vs. snapshot-carried trajectory.** Fully lifting the pure
   stepper over `FkEdge` (both worlds call it) versus having `CatalogBuilder`
   precompute a replayed-trajectory primitive into the snapshot that the LSP
   reads. The first centralizes the algorithm; the second keeps the LSP thinner
   but widens the snapshot. Recommend the lift (the algorithm is the thing that
   drifts), but the snapshot already imports `org.jooq` legitimately, so either
   respects the classification boundary. Reviewer call.
2. **Rung 4 ambiguity UX.** Decline-on-ambiguity is the principled floor. Whether
   to additionally *offer each alternative chain* as a separate code action (nicer
   UX, more code) or simply decline with a message ("multiple routes exist; name
   the path explicitly") is a UX call that does not affect the principle.
3. **Does rung 4 belong in this item or its own?** It is a convenience layer
   gated on Slice A; it could ship as a follow-up item once A/B land. Kept here
   per the design conversation (the user asked to keep it in scope), but it is the
   natural fracture point if this item grows too large to land in one review.

## Relationship to other items

- **R379** (terminal-hop reject): the safety net this item prevents; Slice B
  consumes its typed verdict.
- **`intellij-lsp-plugin`**: the delivery vehicle (transport + IntelliJ plugin);
  no overlap with authoring intelligence. This item's behaviour reaches IntelliJ
  through that plugin once both land.
- **R236 / R282**: adjacent `@reference` diagnostic-message scoping; no overlap
  (those scope existing hints; this adds completion/ranking/diagnostic/code-action
  surfaces).
- **R233 / R224** (LSP column-dispatch projection) and **R119** (directive-surface
  drift containment): the two precedents this item's substrate follows, projection
  sharing and a drift test, respectively.
