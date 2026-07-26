---
id: R541
title: "Root Query unit: one query unit shared by root and child fetchers"
status: Backlog
bucket: architecture
priority: 4
theme: classification-model
depends-on: []
created: 2026-07-26
last-updated: 2026-07-26
---

# Root Query unit: one query unit shared by root and child fetchers

Owns seam-worklist row 10 of R333's decided target topology: the **Root Query unit**, the root
`rows<X>`-equivalent. The resolve side is asymmetric today: the child path factors its query into a
named unit (child fetcher → DataLoader → `rows<X>`, the `select` / `from` / `where` / `orderBy` /
`$fields` assembly as a named method with a model-carried name), while the root path inlines that
same assembly into the fetcher body with no named unit to call. Root and child build the same query
two ways; only child names it. The decided target (2026-06-19, reaffirmed against the
generate-inline alternative 2026-07-26): both fetcher kinds become thin entry points delegating to
one shared query unit, differing only in invocation strategy (root calls it directly; child calls it
batched through a loader plus scatter). The root path gains a level of indirection not required by
runtime (a static, monomorphic, JIT-inlined call; no batching justifies it); paying it buys
uniformity, independent assertability of the query unit, and reuse where one type is reachable both
at root and as child. This is the deliberate, accepted trade recorded in R333.

R314 has since shipped the machinery this item spends: the `methodgraph` command/name registry
(`MethodCommand` + `MethodCommandRegistry`, the name authority whose commit returns the declaration
name), the level-1 closure oracle (`MethodClosureOracleTest`) and the level-2 bidirectional oracle
pattern (`ReentryCommandClosureTest`). This item extends that machinery to a new covered family
rather than inventing any of it.

## Current topology (2026-07-26 code walk)

One correction to R333's worklist first: row 10's "inlined in `SelectMethodBody` today" points at
the wrong class. `SelectMethodBody` (`generators/util/`) is the federation/node dispatcher's
already-named `select<Type>Alt<N>` unit. The actual root inlining lives in
`TypeFetcherGenerator`'s root builders. The row's substance (root inlines, child names) is correct.

The root-side SQL anchors and their current factoring, all in `TypeFetcherGenerator` unless noted:

- `buildQueryTableFetcher` (plain single/list root, `QueryField.QueryTableField`): **inlines** the
  `dsl.select(<Type>.$fields(env.getSelectionSet(), t, env)).from(t).where(condition)
  .orderBy(orderBy).fetch()` chain (single arm drops `orderBy`, ends `.fetchOne()`).
- `buildQueryConnectionFetcher` (connection root): **inlines** the seek/limit chain
  (`.select(page.selectFields()).from(t).where(condition).orderBy(page.effectiveOrderBy())
  .seek(page.seekFields()).limit(page.limit()).fetch()`).
- `buildFannedQueryTableFetcher` (multi-tenant fan-out root): **inlines** the same chain inside a
  `TenantConnections.fanOutRows(env, dsl -> ...)` lambda.
- `buildQueryRoutineFetcher` (`@routine` root): **inlines**, composing `JoinPathEmitter` pieces.
- `buildQueryTableInterfaceFieldFetcher` (single-table-interface root): half-factored; the
  projection/join/discriminator-filter half is the shared `buildTableInterfaceReprojection`
  helper, the fetcher inlines the terminal `.where(condition).orderBy(orderBy).fetch()`.
- `buildQueryLookupFetcher` + `buildQueryLookupRowsMethod` (`@lookupKey` root): the **one root
  path that already delegates** to a named unit, with the name a regime-1 model fact
  (`QueryLookupTableField.lookupMethodName()`).
- Multi-table polymorphic roots (`MultiTablePolymorphicEmitter`): two-stage; stage 1 (UNION-ALL
  of narrow projections) is inlined in the fetcher, stage 2 is already the named per-participant
  `select<Participant>For<Field>` unit.
- Root DML: the DML chain is inlined but is not a SELECT launcher; its follow-up SELECT is
  already the named `rows<Name>` companion R314 landed (minted via
  `MethodCommandRegistry.declareDmlReentryRowsMethod`).

The child path, for contrast, is fully factored: `RowsMethodSkeleton.build` frames every
`rows<X>` / `load<X>` as `public static <Ret> <name>(keys, env)` (the `dsl` local is emitted
inside from `TenantDslEmitter`; the selection set rides `env`), the body is the sealed
`RowsMethodBody` permit (`SqlBatchedTable` / `SqlBatchedLookupTable` / `SqlBatchedPivot` /
`Service`) rendered by `SplitRowsMethodEmitter`, the declaration name flows through
`TypeFetcherEmissionContext.rowsDeclarationName` into `MethodCommandRegistry
.declareReentryRowsMethod` (reading `BatchKeyField.rowsMethodName()`), and the DataLoader call
site reads the same model fact through `RowsMethodCall.batchLoaderLambda`.

What root and child already share at emitter level: `ValuesJoinRowBuilder`, `JoinPathEmitter`,
`LookupValuesJoinEmitter`, the `<field>OrderBy` helper, `buildTableInterfaceReprojection`,
`TenantDslEmitter`, `GeneratorUtils.declareTableLocal`. What they do not share is precisely the
unit framing: `RowsMethodSkeleton` / `RowsMethodCall` are child-only, and the
`dsl.select(<Type>.$fields(...)).from(...)` head plus terminal fetch is emitted independently at
every site.

Model facts in place: `Source.Root` (`Root.Query` / `Root.Mutation`, the emit-strategy axis;
"`Root` and `OnlyChild` run their SQL directly") already classifies every root coordinate.
`SqlGeneratingField` (`returnType()` / `filters()` / `orderBy()` / `pagination()`) is the
capability that already spans root and child: implemented by `QueryTableField`,
`QueryLookupTableField`, `QueryTableInterfaceField` and all `ChildField.TableTargetField`
variants; `TypeConditionsGenerator` already dispatches on it. `BatchKeyField` (which carries
`rowsMethodName()`) is child-only by construction: its other members (`sourceKey()`,
`loaderRegistration()`) are inherently child-shaped, so the root unit needs its own naming fact,
not a forced retrofit.

## Target

Every root SQL anchor delegates across a named seam to its own query unit, exactly as the child
path does today. The root fetcher becomes a thin entry point: decode strategy-side concerns
(nothing today; the direct call *is* the strategy), call the unit, wrap the result. The unit owns
the whole query composition: table local, condition, orderBy, pagination, the
`select($fields).from(...)` head, and the terminal fetch.

"One query unit shared by root and child" (row 10's phrasing) means one unit **kind**: the same
skeleton framing, the same naming discipline (regime 1, minted on the model, committed through
the registry), the same closure oracle, per anchor coordinate. A root coordinate and a child
coordinate are distinct coordinates and keep distinct unit instances; what stops existing is the
root-shaped *absence* of a unit. The direct-vs-batched fork becomes a property of the call site
(invocation strategy), which is exactly the shape R471's direct-SQL `OnlyChild` emit needs to
slot into later: arrival picks the strategy, the unit does not care who calls it.

## Design

- **Naming fact.** A `queryMethodName()` naming fact on the root SQL-anchor leaves, default
  `"rows" + capitalize(name())` to match the child convention, minted on the model the way
  `BatchKeyField.rowsMethodName()` and `QueryLookupTableField.lookupMethodName()` are. Placement
  fork below.
- **Registry seam.** A new `MethodCommandRegistry.declareRootQueryUnit(field, unitFqcn)` commit,
  shaped exactly like `declareReentryRowsMethod`: reads the model's naming fact, returns the
  declaration name, commits when the site-level fact holds, no overload accepting an
  externally-derived name. The covered-family gate is a new site-level fact (a root coordinate
  that launches a SELECT), *not* `emitsKeyedReQuery()` (false for root by design). Duplicate
  claims throw via the existing exactly-one guard.
- **Skeleton.** Extend or sibling `RowsMethodSkeleton`: the root unit is
  `public static <Ret> rows<Field>(env)` with the `dsl` local emitted inside (same
  `TenantDslEmitter` seam the child skeleton uses); no `keys` parameter. Return types are the
  natural fetch results (`Result<Record>` list, `Record` single, `ConnectionResult` connection),
  which do not fit `RowsMethodShape.outerRowsReturnType` as written; the root unit gets its own
  return-shape derivation rather than bending the keyed one.
- **Body permit.** `RowsMethodBody` (sealed) gains root arms, or a sibling sealed permit for
  root unit bodies; whichever keeps the skeleton switch exhaustive and the arms honest. The
  bodies themselves are the existing inline blocks moved, not rewritten: composition of the
  already-shared helpers stays identical so the SQL cannot drift.
- **Fan-out.** The fanned root wraps its query in `TenantConnections.fanOutRows(env, dsl -> ...)`;
  the unit must own that wrapper (the lambda is query composition, not invocation strategy), so
  the `dsl`-acquisition seam inside the skeleton has to admit the fan-out form.
- **Closure.** Extend the level-2 bidirectional oracle to the new family
  (`ReentryCommandClosureTest` pattern, likely a sibling test): every root SQL-anchor coordinate
  has exactly one committed command, every committed command's method was declared, and the
  level-1 oracle keeps proving every emitted call edge resolves.

## Design forks for the Spec reviewer

1. **Where the naming fact lives.** Options: (a) a new small capability interface for
   query-unit-owning anchors (root leaves now; nothing stops a later unification with
   `BatchKeyField.rowsMethodName()` under it), (b) a default method on `SqlGeneratingField`
   (tempting but wrong: child implementers already carry `rowsMethodName()` and would get two
   naming facts), (c) per-leaf methods like `lookupMethodName()` today. Recommendation: (a);
   it is the R432 lesson (fresh capability, compiler-forced adoption) applied to naming.
2. **The lookup root.** `QueryLookupTableField.lookupMethodName()` is already regime-1 and its
   unit already exists; fold it into the new family (rename to the uniform convention, commit
   through the new seam) or leave it as the settled precedent it is. Recommendation: fold the
   *commit* in (so the closure oracle covers it) but keep the `lookup<Field>` name; renaming
   emitted method names buys nothing.
3. **Single-table-interface and routine roots.** Both are root SELECT launchers and belong to
   the family eventually; both have extra moving parts (discriminator reprojection, routine
   table expressions). Take them in the tail slices or spin them off? Recommendation: in, as
   the last slices; the item is not done while a root SELECT launcher remains inline, and both
   compose from already-shared helpers.
4. **Multi-table polymorphic stage 1.** The UNION-ALL stage is a root SELECT launcher too, but
   the family also carries the documented hand-rolled loader carve-out and its own two-stage
   structure. Recommendation: out of scope here, recorded as the residue this item leaves;
   folding it in belongs with a dedicated polymorphic-emit item so this one stays a seam
   extraction, not a polymorphic redesign.

## Slices

1. **Model + registry + oracle first.** The naming fact, the site-level covered-family fact,
   `declareRootQueryUnit`, and the bidirectional oracle extension with non-vacuity witnesses;
   no emit change yet (oracle asserts the covered set is empty until slice 2, or lands gated).
2. **Plain root.** Extract `buildQueryTableFetcher`'s single and list arms into the named unit;
   fetcher goes thin. SQL-equivalence acceptance (below) established here.
3. **Connection root.** Same extraction for `buildQueryConnectionFetcher`.
4. **Fanned root.** The `fanOutRows` wrapper moves into the unit.
5. **Routine + single-table-interface roots**, and the lookup-root commit fold (fork 2).

## Acceptance

- **SQL equivalence, not byte equivalence.** Extracting an inline block into a named method
  changes the generated Java by construction; what must not change is the SQL. The rendered
  query strings and query counts for every migrated root shape stay identical; the execution
  tier (Sakila suites covering root list/single/connection/fanned/routine/lookup/interface
  reads) stays green unchanged. No behavioral delta of any kind; that is R471's line, not ours.
- **Closure both ways.** Level-1 oracle green throughout; the new family's bidirectional oracle
  green from its landing slice on (model to command, command to emit, exactly one).
- **Thinness is checkable.** After the tail slice, no `dsl.select(` head is emitted into a root
  fetcher body by `TypeFetcherGenerator`'s migrated builders; the multi-table polymorphic
  stage-1 carve-out (fork 4) is the one documented exception.

## Out of scope

- The Service-call unit (R333 worklist row 11): the parallel service-backed arm, its own item.
- Direct-SQL `OnlyChild` emit (R471): this item builds the unit that strategy will call
  directly, it does not change any invocation strategy itself.
- Lifting `$fields` to regime 1 (row 2) and finishing the conditions half-migration (row 5):
  adjacent naming-authority work, separately tracked.
- Multi-table polymorphic stage 1 (fork 4 above) and the hand-rolled polymorphic loader
  registration.
- Root DML chains (not SELECT launchers; their reentry SELECT is already covered by R314).
