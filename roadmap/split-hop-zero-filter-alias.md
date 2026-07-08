---
id: R450
title: "Split-path hop-0 condition filter binds the same alias as source and target"
status: Spec
bucket: bug
theme: structural-refactor
depends-on: []
created: 2026-07-08
last-updated: 2026-07-08
---

# Split-path hop-0 condition filter binds the same alias as source and target

## Problem

`SplitRowsMethodEmitter.buildWhereCondition` emits a hop's `condition:` WHERE filter as
`method(srcAlias, tgtAlias)` with `srcAlias = i == 0 ? firstAlias : aliases.get(i - 1)`, but
`firstAlias` *is* `aliases.get(0)`: a filter on hop 0 of a `@splitQuery` path binds the hop-0
target alias to both parameters, `method(firstAlias, firstAlias)`. Latent since the file's
creation (predates R435; found during the R435 second-pass review) and unreachable today only
because no fixture authors a `{key:/table:, condition:}` element at position 0 of a split
path. All three cardinality siblings (list, single, connection) and the record-backed entry
points share `buildWhereCondition`, so every batched shape is affected.

The model already pins the correct contract, which makes this an emitter contradicting a
validated invariant rather than an open design question: `JoinStep.Hop.filter()` is documented
as a WHERE condition method called `(originTable, targetTable)`, hop 0's `originTable` is the
parent table, build-time Check 2 (`BuildContext.validateWhereFilterParamTables`) validates the
method's concretely-typed parameters against exactly that pair, and the inline emitters honour
it (`resolveSourceAlias` passes the parent alias at hop 0). The split emitter therefore
generates a guaranteed javac incompatible-types error when the author typed the source
parameter concretely (Check 2 promised them that alias), and silently wrong SQL when the
parameters are wildcard `Table<?>` or the path is self-referential.

## Why this is not a one-line alias swap

The split query's FROM anchor is `parentInput`, a `VALUES` table of batch-key columns. Under
`ParentCorrelation.OnFkSlots` the parent *table* is not in the query at all: `firstAlias`
joins directly against `parentInput` on the FK-slot columns, and the batch key
(`FieldBuilder.deriveSplitQuerySource`) is the FK source-side column tuple. Two consequences:

* There is no parent-typed alias to hand the filter's source parameter.
* The batch *grain* is wrong, independent of any alias: parents sharing FK-slot values share
  one key tuple and receive identical rows, but a hop-0 filter reads arbitrary parent columns,
  so two such parents can legitimately require different filter verdicts. A filter over the
  parent row makes the parent's identity part of the fetch's inputs; slot-tuple keying
  under-specifies it.

`ParentCorrelation.OnConditionJoin` already models the correct topology for its own case:
`parentInput` carries the parent PK, `.join(parentAlias)` on the PK pairs, then hop 0 attaches
off `parentAlias` via the two-arg condition method. A hop-0 filter needs exactly that
parent-join topology with the hop's own `On` doing the attach.

## Design

1. **Grain (classifier).** In `deriveSplitQuerySource`, a hop-0 `Hop` carrying a non-null
   `filter()` forces the parent-PK entry columns (the existing fallback branch), regardless of
   the hop's `On` arm. Filter-less FK hops keep the cheaper slot grain; the fork is carried by
   the model (which correlation arm the field lands), not re-derived at emit sites.
2. **Topology (model).** Generalize `ParentCorrelation.OnConditionJoin` into the parent-join
   arm: it already carries `(firstHop, parentTable, condition)`; the generalization is
   "`parentInput` joins the parent table on its PK; hop 0 then attaches off `parentAlias` by
   the hop's own `On`" (`ColumnPairs` renders the ordinary forward join, `Predicate` the
   existing two-arg condition call). `OnFkSlots` remains the no-parent-join fast path for
   filter-less FK hops. Follows the R435/R438 precedent of folding a special case into one arm
   of a general axis (fk becoming one case of `On.Keying`). Exact shape (widen
   `OnConditionJoin` vs a sibling arm reading `firstHop.on()`) is the implementer's call;
   either way every switch over `ParentCorrelation` stays exhaustive.
3. **Emitter.** `buildWhereCondition` receives the parent-side alias and uses it as hop-0
   source under the parent-join arm. Under `OnFkSlots` a hop-0 filter becomes
   classifier-unreachable: throw, per the established classifier-unreachable convention.
4. **Non-table-backed split parents (record / service shapes).** No parent table exists to
   join, and Check 2 silently skips when `originTable` is null, so the broken shape classifies
   unverified today. A hop-0 `condition:` filter on a split path under a non-table-backed
   parent gets a typed rejection at classify time (`AuthorError.Structural`: the filter's
   source row is not a catalog table, name the escape hatch of filtering on a later hop or the
   terminal `@condition` surface).
5. **Inline emitters unchanged.** They already pass the parent alias at hop 0; the fix's
   correctness is pinned by inline/split row equivalence at the execution tier.

## Tests

Pipeline tier primary; no code-string assertions on generated method bodies.

* **Pipeline**: a `@splitQuery` field with a hop-0 `{key:, condition:}` element on a
  table-backed parent asserts the parent-PK `sourceKey` and the parent-join correlation arm; a
  sibling fixture with the filter on hop 1 asserts the slot-tuple key and `OnFkSlots` are
  unchanged.
* **Execution (PostgreSQL)**: the grain proof. Seed two parents sharing the same FK-slot value
  where the hop-0 filter passes for one parent and fails for the other; assert the split form
  reproduces the inline form's per-parent rows exactly. A slot-keyed batch cannot pass this
  test (both parents would receive identical rows).
* **Rejection fixture**: hop-0 filter on a split path under a record-backed parent asserts the
  `Structural` arm and message.
* **Unit**: compact-constructor invariants of the widened/added `ParentCorrelation` arm.

## Out of scope

* The stale pre-flip topology comments and the root-fetcher emission duplication in the same
  file (R449 carries those).
* Hop-0 filters on *inline* paths (already correct) and non-hop-0 split filters (already
  correct; the sibling fixture pins them).
* Any new authoring surface: this item changes which query the existing surface generates,
  nothing about the SDL.
