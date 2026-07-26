---
id: R541
title: "Root Query unit: one query unit shared by root and child fetchers"
status: Spec
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
uniformity, independent assertability of the query unit in the corpus and tests, and the uniform
invocation-strategy slot that later arrival work (R471) spends. This is the deliberate, accepted
trade recorded in R333.

R314 has since shipped the machinery this item spends: the `methodgraph` command/name registry
(`MethodCommand` + `MethodCommandRegistry`, the name authority whose commit returns the declaration
name), the level-1 closure oracle (`MethodClosureOracleTest`) and the level-2 bidirectional oracle
pattern (`ReentryCommandClosureTest`). This item extends that machinery to a new covered family
rather than inventing any of it.

## Current topology (2026-07-26 code walk)

Two corrections to R333's worklist row first (both applied to R333 alongside this item). The
emitter pointer: "inlined in `SelectMethodBody` today" names the wrong class;
`SelectMethodBody` (`generators/util/`) is the federation/node dispatcher's already-named
`select<Type>Alt<N>` unit, and the actual root inlining lives in `TypeFetcherGenerator`'s root
builders. The verdict letter: the row records seam rule (b), reuse across callers, but a root
coordinate and a child coordinate are distinct coordinates whose WHERE clauses genuinely differ
(argument filters vs parent-key correlation), so no unit is ever literally shared between them;
the real, named reason for the seam is (c), independent assertability, with the uniform
invocation-strategy slot as the forward payoff. The row's substance (root inlines, child names,
close the asymmetry) is correct and unchanged.

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

- **Naming fact, one derivation locus.** A small capability interface (working name
  `QueryUnitField`) carrying `queryUnitName()`, default `"rows" + capitalize(name())`. That
  formula already exists byte-identical in `BatchKeyField.rowsMethodName()` and
  `MutationField.DmlTableField.reentryRowsMethodName()`; a third sibling copy would give
  regime 1's one-derivation-locus rule three loci. So the capability *extracts* the formula
  rather than duplicating it: `BatchKeyField` extends it, and the service arms' `load<X>` and
  the lookup root's `lookup<Field>` become overrides of the one method rather than parallel
  naming facts. Root SQL-anchor leaves adopt the capability.
- **Registry seam.** A new `MethodCommandRegistry.declareRootQueryUnit(field, unitFqcn)` commit,
  shaped exactly like `declareReentryRowsMethod`: reads the model's naming fact, returns the
  declaration name, commits when the site-level fact holds, no overload accepting an
  externally-derived name. Duplicate claims throw via the existing exactly-one guard. The
  covered-family gate is **derived from facts the model already carries, never tagged**: a
  `RootField` whose `operation()` is one of `Fetch` / `Paginate` / `Lookup` and whose target
  shape is `Table` (peeling the `Connection` wrapper). Walking `QueryField`'s permits against
  that conjunction: `QueryTableField`, `QueryLookupTableField`, `QueryTableInterfaceField`,
  `QueryRoutineTableField` are in; the polymorphic roots (`QueryInterfaceField` /
  `QueryUnionField`, target shape `Interface` / `Union`), the node roots (`NodeResolve`), the
  service roots (`ServiceCall`) and the DML roots (write operations) are out *by the fact*,
  with no exemption list anywhere. Not `emitsKeyedReQuery()`, which is false for root by design.
- **Skeleton.** Extend or sibling `RowsMethodSkeleton`: the root unit is
  `public static <Ret> rows<Field>(dsl, env)`. No `keys` parameter, and `dsl` is a
  **parameter, not a local**: the call site owns connection acquisition (R333's thread K
  pre-pays exactly this parameter-threading cost). Return types are the natural fetch results
  (`Result<Record>` list, `Record` single, `ConnectionResult` connection), which do not fit
  `RowsMethodShape.outerRowsReturnType` as written; the root unit gets its own return-shape
  derivation rather than bending the keyed one.
- **Body permit.** `RowsMethodBody` (sealed) gains root arms, or a sibling sealed permit for
  root unit bodies; whichever keeps the skeleton switch exhaustive and the arms honest. The
  bodies themselves are the existing inline blocks moved, not rewritten: composition of the
  already-shared helpers stays identical so the SQL cannot drift.
- **Fan-out.** `TenantConnections.fanOutRows(env, dsl -> ...)` is invocation strategy (the same
  query invoked across N tenant connections), not query composition. The wrapper stays in the
  fanned fetcher, which passes each tenant's `dsl` into the *same* unit the plain root calls
  with its one resolved `dsl`. `TenantDslEmitter`'s `FanOut` invariant throw survives
  untouched, and the unit's `dsl` parameter means one thing at every call site. This is also
  what makes the R471 hand-off literal: an `OnlyChild` direct call is "same unit, caller
  supplies `dsl`", not a fifth body shape.
- **Closure.** Extend the level-2 bidirectional oracle to the new family
  (`ReentryCommandClosureTest` pattern, likely a sibling test): every covered root coordinate
  has exactly one committed command, every committed command's method was declared, and the
  level-1 oracle keeps proving every emitted call edge resolves. The oracle's covered set reads
  the **derived fact, never capability membership**: an `instanceof QueryUnitField`-gated set is
  a tautology over its members and cannot catch the silent-skip drift where a leaf that should
  implement the capability simply does not and vanishes from the set. The capability is the
  structural conjunct only, the migration dial, exactly as R314 widened its covered set between
  slices.
- **Validator mirror.** The classifier decision ("this root coordinate launches a SELECT, so a
  named query unit must exist") implies a generator branch, so it gets the twin every classifier
  invariant gets: the derived fact true on a leaf that carries no query-unit naming fact is a
  `ValidateMojo` error, landed in the same commit that closes the migration dial (no window).
  The same guard is the membership enforcer: once migration completes, the derived fact's
  true-set equals the capability's member-set.

## Design forks for the Spec reviewer

1. **Where the naming fact lives.** Options: (a) the small capability interface above, with the
   formula extracted to one locus and existing facts becoming overrides, (b) a default method on
   `SqlGeneratingField`, (c) per-leaf methods like `lookupMethodName()` today. Recommendation:
   (a). Option (b) fails twice over: child implementers already carry `rowsMethodName()` and
   would get two naming facts, and `QueryRoutineTableField` does not implement
   `SqlGeneratingField` at all, so the routine root would never receive the default. Option (c)
   is the third-copy shape the one-locus rule forbids.
2. **The lookup root.** `QueryLookupTableField.lookupMethodName()` is already regime-1 and its
   unit already exists. Recommendation: it becomes the `queryUnitName()` *override* on that
   leaf, and its declaration commits through the new seam so the closure oracle covers it; the
   emitted `lookup<Field>` name is unchanged (renaming emitted methods buys nothing).
3. **Single-table-interface and routine roots.** Both are root SELECT launchers and belong to
   the family (the derived fact includes them); both have extra moving parts (discriminator
   reprojection, routine table expressions). Take them in the tail slices or spin them off?
   Recommendation: in, as the last slices; the item is not done while a covered root SELECT
   launcher remains inline, and both compose from already-shared helpers.
4. **Multi-table polymorphic stage 1.** Not a fork anymore, recorded for transparency: the
   UNION-ALL stage is a SELECT launcher, but the polymorphic roots fall outside the covered
   family *by the derived fact* (target shape `Interface` / `Union`), not by an exemption list.
   Folding them in later means extending the fact, which belongs with a dedicated
   polymorphic-emit item (that family also carries the documented hand-rolled loader carve-out);
   this item stays a seam extraction, not a polymorphic redesign.

## Slices

1. **Model + registry + oracle + plain root, together.** The capability with the extracted
   one-locus formula, the derived covered-family fact, `declareRootQueryUnit`, the bidirectional
   oracle, *and* the extraction of `buildQueryTableFetcher`'s single and list arms into the
   named unit. The oracle lands green with positive witnesses (the R314 slice-1 shape worked
   because the reentry methods already existed; here the first units must land with the oracle
   or it asserts an empty set, which is not an enforcer). The pre-extraction SQL baseline
   (acceptance below) is captured before this slice's cutover.
2. **Connection root.** Same extraction for `buildQueryConnectionFetcher`.
3. **Fanned root.** The fetcher keeps `fanOutRows` and calls the unit per tenant `dsl`.
4. **Routine + single-table-interface roots.**
5. **Lookup-root fold + validator mirror + membership enforcer.** The `lookup<Field>` override
   commits through the seam; the `ValidateMojo` guard and the derived-fact-equals-member-set
   enforcer land in the same commit, closing the migration dial (no window).

## Acceptance

- **SQL equivalence, not byte equivalence.** Extracting an inline block into a named method
  changes the generated Java by construction; what must not change is the SQL. The mechanism is
  a deliverable, not a hope: slice 1 captures a pre-extraction SQL baseline for the covered
  root shapes through the execution tier's `ExecuteListener`-based SQL capture in
  `graphitron-sakila-example` (R432's `diff -r` discipline, pointed at SQL instead of Java),
  and every slice diffs against it. Query strings and query counts stay identical; the
  execution tier stays green unchanged. No behavioral delta of any kind; that is R471's line,
  not ours.
- **Closure both ways.** Level-1 oracle green throughout; the new family's bidirectional oracle
  green from slice 1 on (model to command, command to emit, exactly one), with non-vacuity
  witnesses and the boundary pins (polymorphic / node / service / DML roots commit nothing).
- **Thinness is a graph property, not a string pin.** For every covered root coordinate, the
  fetcher method's emitted call edges (the `EmittedMethodClosure` walk the level-1 machinery
  already models) include a call to that coordinate's committed command's method. This pins the
  actual invariant (the fetcher is thin *because* it delegates) structurally; code-string
  assertions on generated bodies stay banned at every tier.

## Out of scope

- The Service-call unit (R333 worklist row 11): the parallel service-backed arm, its own item.
- Direct-SQL `OnlyChild` emit (R471): this item builds the unit that strategy will call
  directly, it does not change any invocation strategy itself.
- Lifting `$fields` to regime 1 (row 2) and finishing the conditions half-migration (row 5):
  adjacent naming-authority work, separately tracked.
- Multi-table polymorphic stage 1 (outside the covered family by the derived fact; note 4
  above) and the hand-rolled polymorphic loader registration.
- Root DML chains (not SELECT launchers; their reentry SELECT is already covered by R314).
