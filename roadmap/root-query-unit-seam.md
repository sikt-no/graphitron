---
id: R541
title: "Root query launcher: the root SELECT family as launcher commands"
status: Spec
bucket: architecture
priority: 4
theme: classification-model
depends-on: [facts-and-commands]
created: 2026-07-26
last-updated: 2026-07-27
---

# Root query launcher: the root SELECT family as launcher commands

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

**Reopened to Spec 2026-07-27 and rewritten as a command family.** The signed-off version built this
seam in the idiom where an emitter reads model facts directly: a `QueryUnitField` naming capability
with the `rows<X>` formula extracted to one locus, a `declareRootQueryUnit` commit seam on
`MethodCommandRegistry`, and a level-2 bidirectional oracle to discover that each covered coordinate
has exactly one command. R549 makes that machinery unnecessary rather than merely early. Under a
coordinate-keyed command relation the declaration name is a field the producer computes when it mints
the row, so there is one derivation locus by construction; "exactly one command per coordinate" is the
relation's key rather than a property a test hunts for; and the fetcher's thinness is a type property
of a renderer that is a total function over its command. Building the capability and the seam first
would have been precisely the migration payment R549 refuses: real work, then real deletion, in its
slice 5. So this item becomes **R549 slice 3c, the second proof of concept** for the command
architecture, and keeps the content the reframing does not touch: the five root emit shapes below, the
derived family fact, fan-out as invocation strategy, and the SQL equivalence pin.

What survives from R314 is the level-1 closure oracle (`MethodClosureOracleTest`), which R549 keeps as
its invariant 4: every callee name resolves to a committed command. What does not survive is the
level-2 pattern (`ReentryCommandClosureTest`) as a thing this item extends, because the property it
asserts becomes structural here.

## Why this family is the right second proof

R549 slice 3's projection command proves type grain and a contribution list. Four things it cannot
reach are exercised here, and all four are load-bearing later:

- **Coordinate-grain keying.** `(coordinate, operation)` is R549's third relation and has no instance
  until this item ships one.
- **Invocation strategy as data.** Direct, fanned across tenant connections, and (later) batched
  through a loader plus scatter are strategies over one query composition. A projection command has no
  strategy axis at all, so nothing has yet tested that strategy is expressible as a command field
  rather than as emitter control flow. This item populates the first two arms; see *Design* for why it
  declines to declare the third ahead of its first row.
- **Return-shape variation.** `Result<Record>`, a single `Record`, and a connection result are three
  shapes derived from the coordinate's facts, where a projection always returns a select-term list.
- **One command referencing another.** The launcher names the projection unit it selects from. This is
  the first cross-command edge, and slice 7's central claim, that the recompile graph is a projection
  over the command relation, rests on those edges being data rather than a scan of emitted text. If
  the reference shape is wrong, slice 7 does not work, and this is the cheapest place to find out.

The family is also unusually well-bounded for a proof: its membership is derived from facts the model
already carries with no exemption list anywhere (see *Design*), which makes "did we cover it"
decidable rather than a judgment call. Most families in this codebase still need an exemption list.

**The honest note, named here rather than discovered at review: this is the one of R549's three
proofs with no capability payload.** Slice 3.1 renames a method and ends a duplicated walk, slice 3.2
stops over-projecting, and R552 ships two fixes for output that does not compile today. This item
adds no user-visible capability and fixes no filed defect; every root shape it touches works. R549's
rule is "no slice that is purely a migration payment", and what pays for this one is not a feature
but the three deletions it makes possible (the inline `select`/`from`/`fetch` chain repeated at five
sites, the level-2 oracle pattern that stops being needed, and the root-shaped absence of a unit) plus
the four findings the In Review hand-off owes. That is a real return, and it is a different kind of
return, which is exactly why it should be stated rather than left for a reviewer to notice. The
practical consequence: if this item starts eating scope, the thing to cut is scope, not the hand-off,
because the hand-off is the product.

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

**The WHERE clause is already factored for most of the family** (corrected 2026-07-27 during Spec
review; the earlier draft of this item claimed the opposite and posed fork 1 on that claim). Four of
the five root shapes emit `Condition condition = <RootType>Conditions.<field>Condition(tableLocal, env)`
through the shared `TypeFetcherGenerator.buildConditionCall`: the plain root, the connection root, the
fanned root, and the single-table-interface root. `QueryConditionsGenerator` emits those methods, over
exactly `QueryTableField` and `QueryTableInterfaceField`, as the env-aware shim composing
`TypeConditionsGenerator`'s pure entity-scoped condition functions. The emitted output confirms it
(`QueryFetchers.films` reads `Condition condition = QueryConditions.filmsCondition(filmTable, env);`).
The two shapes that do not call one: the routine root, which carries no field-level filter surface at
all and emits no condition, and the lookup root, whose `buildQueryLookupRowsMethod` genuinely does
compose `DSL.noCondition()` and fold `field.filters()` into it inline. The stale
`var condition = DSL.noCondition();` in `buildQueryTableFetcher`'s javadoc example does not match what
that method emits three statements later, and is the likely source of the earlier draft's error.

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
variants; `TypeConditionsGenerator` already dispatches on it. It does **not** span the whole covered
family, though: `QueryRoutineTableField` implements `RoutineChainField` and not `SqlGeneratingField`,
carrying no `filters()` / `orderBy()` / `pagination()` at all, so the producer cannot read the family's
composition off one capability and slice 4 has to source the routine root's slots from its
`RoutineChain`. `BatchKeyField` (which carries
`rowsMethodName()`) is child-only by construction: its other members (`sourceKey()`,
`loaderRegistration()`) are inherently child-shaped, so the root unit needs its own naming fact,
not a forced retrofit.

Two things about this walk to hold in mind while reading the design. The `$fields` head it records at
every site is what R549 slice 3.1 rewrites: the call becomes `<Type>.$project(<grouped selection>, t,
env)` and the three overloads collapse to one, so this item composes launchers around the
post-keystone shape and never sees `$fields`. And the walk itself is the item's most durable content:
it is an inventory of five genuinely distinct emit shapes, and the command reframing changes who
decides them, not how many there are.

## Target

Every root SQL anchor is produced as a **launcher command** and rendered by an interpreter that is a
total function over it. The root fetcher becomes a thin entry point by construction rather than by
discipline: it renders from a command whose arms are the only things it can express. The launcher owns
the whole query composition as data: the table, the condition, the orderBy, pagination, the reference
to the projection unit it selects from, the invocation strategy, and the return shape.

"One query unit shared by root and child" (row 10's phrasing) means one command **kind**, not one
command instance. A root coordinate and a child coordinate are distinct coordinates and keep distinct
rows in the relation; what stops existing is the root-shaped *absence* of a unit. The direct-versus-batched
fork becomes a field on the command, which is what makes R471's direct-SQL `OnlyChild` emit a new
strategy arm rather than a fifth body shape or a new call site: arrival picks the strategy, and the
composition does not care who invokes it.

## Design

- **The command.** Pure records, no emit-library vocabulary, per R549's invariant 3:

  ```java
  record LauncherCommand(
      UnitRef unit,             // the method the shell declares for this launcher
      Coordinate coordinate,    // the relation's key, with operation
      TableRef table,           // the table this query runs against
      UnitRef projection,       // the projection unit whose $project supplies the select list
      UnitRef where,            // R552's glue unit for this coordinate; absent (no live filters, the
                                // routine root, the lookup root) composes the neutral condition (fork 1)
      UnitRef orderBy,          // the emitted <field>OrderBy helper; absent when the coordinate is unordered
      Invocation invocation,    // enum: DIRECT | FANNED_OVER_TENANTS
      ResultShape result,       // SingleRecord | RecordList | Connection(carrier plan, seek pagination)
      List<SelectTerm> extras)  // launcher-owned projection additions: cursor columns, __idx__, __rn__
  {}
  ```

  Every slot is build-time composition. Runtime values (argument filter values, seek cursor values,
  orderBy argument values) arrive through the rendered method's parameters and never through the command,
  which is the same static/runtime line R549 draws at the projection gate: field names are the command's
  vocabulary, result keys and argument values are the runtime's.

  **Three slots were cut against R549's vocabulary rule, and the cuts are the interesting part of this
  sketch.** `List<OrderTerm> orderBy` became a `UnitRef`, because ordering is already a named emitted
  unit: `TypeFetcherGenerator` builds a `private static <field>OrderBy(env, table)` helper and both the
  root and child paths call it. Modelling order terms here would have re-derived a unit that exists and
  pre-built R333 row 9's family, which owns ordering as its own seam. `Pagination` folded into
  `ResultShape.Connection`, executing the recommendation below rather than leaving `Seek` and
  `SingleRecord` pairable. And `Invocation` is an enum, not a sealed interface: two payload-free
  records are ceremony, and the arm that will carry data (`Batched`, when the child family folds in)
  promotes it in two lines at the point it stops being ceremony.

  `extras` is the slot that makes this command the projection command's dual. R549 establishes that
  anything a mechanism needs regardless of client selection belongs to a launcher rather than to a
  projection contribution, and this is where those terms live. Be precise about how much of that claim
  this family can actually test, because R549 names four append sites and **this item reaches exactly
  one of them**: the connection root's extra-ordering columns, which `ConnectionHelperClassGenerator`
  computes as `extraFields` ("the pure extra-ordering columns", merged into `selectFields` and driving
  cursor encoding). The other three are elsewhere: `__idx__` and `__rn__` live entirely in
  `SplitRowsMethodEmitter`, so they are the child path's extras and arrive with slice 5; `__typename` is
  appended by the polymorphic launcher, which the derived fact excludes; and multiset wrapping happens
  inside the projection, not at a launcher, so it is slice 3.1's business and not an extra at all. One
  populated mechanism is still a real test of the slot (it either decomposes into projection output plus
  launcher extras or it does not), but nobody should read slice 3c as having validated all four.
  The slot also mints no term type of its own: R549's rule that term arms are SQL shapes, never reasons,
  applies to launchers too, and extra-ness is a reason (a cursor column renders exactly as a plain column
  term does; what makes it an extra is which list it sits in). So `extras` reuses the projection command's
  `SelectTerm` algebra, and slice 5's `__rn__` extends that shared algebra with a window-function arm when
  it arrives, a new SQL shape and therefore a legitimate extension rather than a parallel type.
- **Invocation, with two values and deliberately not three.**

  ```java
  enum Invocation {
      /** The fetcher resolves one dsl and calls the launcher once. */
      DIRECT,
      /** The same composition invoked once per tenant connection, results concatenated. */
      FANNED_OVER_TENANTS
  }
  ```

  The child path's batched arm is **not** declared here. It is the obvious third value and it is what
  would make "one command kind shared by root and child" literally true, but declaring an arm before
  any row populates it adds untested surface, which R549's non-vacuity discipline is against. Slice 5
  declares `Batched` together with its first row, when the child path folds in. Two populated values are
  enough to prove the real point, that invocation strategy is expressible as a command field rather
  than as emitter control flow.

  An enum rather than a sealed interface, because neither value carries anything. `Batched` is the
  value that will (a loader registration, a scatter key), and it is also the one that promotes the type
  when it arrives; doing that promotion at the point the payload exists is the same discipline as
  declaring the arm at the point the row exists.
- **Return shape as data.** `ResultShape` is derived once, in the producer, from the coordinate's
  cardinality and whether pagination is present, so the renderer reads a return shape instead of
  deriving one. `RowsMethodShape.outerRowsReturnType` is the keyed derivation and does not fit these
  three shapes; nothing bends it. The `Pagination` slot is **folded in, not merely recommended for
  folding**: `Seek` appears exactly when the shape is a connection, so two slots made the illegal pair
  (`Seek` with `SingleRecord`) representable for no gain. The seek pagination rides the `Connection`
  arm alongside the carrier plan of fork 5, which is what that arm needs to be carrying anyway. This is
  the mirror of R549's correlated-families rule: a point in a product space beats the product exactly
  while the axes co-vary. If the producer walk turns up a covered coordinate that breaks the
  correlation, that is a finding for the hand-off, and the slot splits back out with its first row.
- **The covered family, derived and never tagged.** The producer mints a row for exactly one thing: a
  `RootField` whose `operation()` is one of `Fetch` / `Paginate` / `Lookup` and whose target
  shape is `Table` (peeling the `Connection` wrapper). Walking `QueryField`'s permits against
  that conjunction: `QueryTableField`, `QueryLookupTableField`, `QueryTableInterfaceField`,
  `QueryRoutineTableField` are in; the polymorphic roots (`QueryInterfaceField` /
  `QueryUnionField`, target shape `Interface` / `Union`), the node roots (`NodeResolve`), the
  service roots (`ServiceCall`) and the DML roots (write operations) are out *by the fact*,
  with no exemption list anywhere. Not `emitsKeyedReQuery()`, which is false for root by design.
- **The producer.** One function in `plan`, from the model to the family's rows, minting a `UnitRef`
  per covered coordinate as it goes. Name derivation lives here and nowhere else, so the three-loci
  problem the previous design solved with a capability interface does not arise: there is one producer,
  not three leaves. The whole family materialises before anything renders, per R549's produce-eagerly
  rule.
- **The renderer.** One interpreter in `render`, total over the command's arms, taking no
  `GraphitronSchema`: R549's invariant 1, and this family is its second decrement. The emitted shape is
  what the signed-off version specified and does not change: `public static <Ret> rows<Field>(dsl, env)`,
  no `keys` parameter, and `dsl` a **parameter, not a local**, so the call site owns connection
  acquisition (R333's thread K pre-pays exactly this parameter-threading cost). The bodies compose the
  already-shared helpers (`ValuesJoinRowBuilder`, `JoinPathEmitter`, `LookupValuesJoinEmitter`, the
  `<field>OrderBy` helper, `buildTableInterfaceReprojection`, `TenantDslEmitter`,
  `GeneratorUtils.declareTableLocal`) exactly as the inline blocks do today, so the SQL cannot drift.
- **`RowsMethodSkeleton` and `RowsMethodBody` stay untouched.** They are the child path's machinery and
  the root launcher does not extend either. That matters beyond tidiness: `RowsMethodBody` sits in
  `rewrite/model/` with a `CodeBlock` on every permit, which is R545's worst offender, and adding root
  arms to it would grow the surface R549's invariant 3 ratchets down while making R545's relocation a
  larger job. The launcher's arms are pure data in `command`, so the question does not arise.
- **Fan-out is the `FannedOverTenants` arm, not a body shape.** `TenantConnections.fanOutRows(env,
  dsl -> ...)` invokes the same query across N tenant connections, so it is strategy rather than
  composition. Two coordinates differing only in tenancy binding therefore produce launcher commands
  that differ in exactly one field, which is the sharpest available evidence that the strategy axis is
  real: under the previous design the same distinction was a separate fetcher builder. The wrapper is
  rendered at the call site, which passes each tenant's `dsl` into the same launcher the plain root
  calls with its one resolved `dsl`; `TenantDslEmitter`'s `FanOut` invariant throw survives untouched,
  and the `dsl` parameter means one thing everywhere. This is also what makes the R471 hand-off literal:
  an `OnlyChild` direct call becomes a third arm on `Invocation`, not a fifth body shape.
- **Closure, and the edge that carries the proof.** The launcher's reference to its projection unit is
  a typed `UnitRef` minted by the plan's naming vocabulary, so an unresolvable callee is
  unrepresentable rather than caught late: the same narrowing R549 settled on for projection callees.
  The level-1 oracle (`MethodClosureOracleTest`, R549's invariant 4) stays green throughout and now
  reads this edge off the command instead of scanning emitted text for it. The level-2 bidirectional
  oracle is **not** extended to this family, because the property it would assert is structural here:
  one row per coordinate is the relation's key, and a producer that mints two for one coordinate fails
  the plan build. What replaces it as a falsifier is a non-vacuity pin on the relation itself (every
  covered coordinate in the corpus appears exactly once; the boundary cases named above appear zero
  times) plus the edge view over the command set.

  The class-qualified emit for the fetcher's call to its launcher (`<Type>Fetchers.rows<Field>(dsl,
  env)`, legal Java, zero runtime cost) **stays**, because churning the generated surface back buys
  nothing and the qualified form reads better. But it stops being load-bearing, and the spec should say
  so plainly: it existed only because `EmittedMethodClosure`'s javadoc names unqualified same-class
  calls as one of its two blind spots, and under a command relation the edge is data, not a token a
  scanner has to find. A later reader must not mistake the qualification for a principle.
- **Validator mirror and membership.** The classifier decision ("this root coordinate launches a
  SELECT") implies an emit branch, so it keeps the twin every classifier invariant gets. A coordinate
  the derived fact is true of and the producer cannot mint a row for is a `ValidateMojo` **deferred
  rejection**, not a producer-side throw, matching what R549 settled for unmintable projection leaves.
  Once migration completes, the derived fact's true-set equals the relation's key-set, and that equality
  is the membership enforcer; it lands in the same commit that closes the migration dial, with no
  window.

## Design forks for the Spec reviewer

1. **How the launcher carries its WHERE clause. Resolved 2026-07-27, at the programme rather than
   here.** Kept because the diagnosis outlived the fork, and because two successive framings of it
   were wrong in ways a later reader of R333 row 5 would repeat.

   The correction: the root builders do **not** compose conditions inline. Four of the five shapes
   already emit `Condition condition = <RootType>Conditions.<field>Condition(tableLocal, env)` through
   the shared `buildConditionCall`, so row 5 records a **naming-regime** half-migration, not a missing
   seam. Its open issue, "finish lift (`QueryConditionsGenerator` end)", is that the `<field>Condition`
   formula is reconstructed independently at both ends: `QueryConditionsGenerator.conditionMethodName`
   mints it and `TypeFetcherGenerator.buildConditionCall` recomputes it.

   The resolution: R552 owns condition production wholesale and lands first, per the ordering decision
   in R549's Slices section, which settles what this fork and R552's fork 4 were each deferring to the
   other item's reviewer. So `where` is a `UnitRef` naming the row's glue method, this item consumes
   rather than builds, and the R2 locus is R552's to dissolve. R552's own walk reaches the same
   diagnosis, so the two items agree on the premise as well as on the owner.

   One consumption detail survives, for the implementer rather than the reviewer: **the slot is not
   total over the family.** The routine root carries no field-level filter surface at all, the lookup
   root's fold is inline today, and a covered coordinate whose live filter set is empty has no row in
   R552's relation either. Model all three as absence (an empty or optional ref) and let the renderer
   compose the neutral condition; never an opaque inline arm. And see fork 5: the faceted connection
   root needs more than one condition reference, so a single `where` slot is under-modelled regardless.
2. **The lookup root.** `QueryLookupTableField.lookupMethodName()` is already regime-1 and its unit
   already exists, so it is the one covered coordinate that starts with a name. Recommendation: the
   producer computes its `UnitRef` like every other row and keeps emitting `lookup<Field>` unchanged,
   with the leaf's naming method retired rather than promoted to an override. Renaming emitted methods
   buys nothing; keeping a leaf-level naming fact alive next to a producer that computes names would
   reintroduce the second locus this reframing removes.
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
5. **The connection root's carrier plan, which the command sketch has no slot for.** Raised
   2026-07-27 during Spec review. `buildQueryConnectionFetcher` emits more than the page SELECT. It binds
   `(tableLocal, condition)` onto the `ConnectionResult` carrier so the lazy `totalCount` resolver can
   issue `dsl.selectCount().from(cr.table()).where(cr.condition())` on selection, and for a faceted
   carrier it additionally binds `facetBase`, a `Map<String, Condition>` of per-facet conditions, and a
   `List<FacetSpec>`, which `ConnectionHelper.facets` turns into a UNION ALL of per-facet GROUP BY arms.
   The fragments come from `QueryConditionsGenerator.facetBaseConditionMethodName` and
   `facetConditionMethodName`. This is not hypothetical: `Query.filmsFaceted` in the sakila corpus is a
   faceted connection root with execution-tier coverage.

   `LauncherCommand` as sketched carries one `where` ref and a payload-free
   `ResultShape.ConnectionResult`, so it can express none of that. Three consequences the implementer
   should not have to discover mid-slice: slice 2 is scoped to seek pagination and `extras`, which is
   the easy half of that builder; the "thinness is a type property" acceptance does not hold for
   connection roots while the carrier binding is renderer knowledge rather than command data; and the
   equivalence pin's statement-count claim is only as good as whether a faceted query and a
   `totalCount` selection are in the representative set. Recommendation: put the carrier plan on the
   `ConnectionResult` arm (the base condition ref, the per-facet ref map, the facet specs), which is
   also what the Pagination/ResultShape fold above wants the arm to be carrying anyway. Deciding it here
   is cheaper than deciding it inside a slice.

   The carrier plan is now **slice 3, its own slice**, rather than the back half of the connection
   slice. The two answer different questions (slice 2 tests R549's projection/launcher boundary via
   `extras`; slice 3 tests whether a launcher can own a query it does not itself issue), and bundling
   them lets a wobble in the carrier design withhold the `extras` finding, which is the finding this
   item exists to produce. The per-facet refs resolve into R552's relation like any other condition
   reference, since that item produces the facet fragments as masked glue variants; this slice consumes
   them and mints nothing of its own.

## Slices

Slice 0 is two R549 slices, not one, since the programme moved the vocabulary skeleton off the
keystone: **slice 1** brings `EmitPlan`, the `command` / `plan` / `render` packages, `UnitRef` and the
naming vocabulary out of `compile/`, and **slices 3.1 and 3.2** bring the projection command this item
names and the final select list its pin is authored against. R552 slices 1 to 3 also precede this
item, per R549's ordering decision, so the condition relation exists before the `where` slot needs it
(see fork 1). Nothing here starts before those land, and this item builds no part of that vocabulary
itself.

The pin suite is authored against post-3.2 output and extends the programme-level equivalence harness
R549 slice 2 lands rather than standing up a second one: same module, same `SQL_LOG` idiom, this
item's representative root queries added to it. That is also what removes the awkwardness in the
previous phrasing, where "authored before the cutover" and "authored after slice 3" were the same
sentence for an item whose cutover is after the keystone.

1. **Command, producer, renderer, plain root, together.** The `LauncherCommand` record set, the derived
   covered-family fact, the producer minting rows for `buildQueryTableFetcher`'s single and list arms,
   and the renderer, with the fetcher reduced to a strategy-plus-call entry point. The relation's
   non-vacuity pin and boundary pins land with it: a first slice whose pins assert over an empty set is
   not an enforcer, which is why the command and its first rows ship in one slice rather than two.
2. **Connection root, page query only.** `buildQueryConnectionFetcher`'s seek/limit chain, which is
   where seek pagination and the `extras` slot first carry weight. If the connection helper's
   `selectFields` union does not decompose into "projection command output plus launcher extras", that
   is the R549 boundary failing, and it fails here first. That finding is the whole reason this item
   is the second proof, so it gets a slice that cannot be held up by anything else.
3. **The `ConnectionResult` carrier plan** (fork 5). The `(table, condition)` binding the lazy
   `totalCount` resolver reads, and the facet base plus per-facet condition fragments and specs a
   faceted carrier binds, all moved onto the `ConnectionResult` arm. Split out from slice 2 because it
   is the larger half of that builder and answers a different question: slice 2 tests R549's
   projection/launcher boundary, this one tests whether a launcher can own a query it does not itself
   issue. Keeping them together would let a wobble in the carrier design withhold the `extras`
   finding, which is the more valuable of the two. The equivalence pin's faceted and `totalCount`
   cases (see Acceptance) belong here, since before this slice the carrier is still renderer knowledge.
4. **Fanned root.** The `FannedOverTenants` arm and its call-site wrapper. This is the slice that
   demonstrates strategy-as-data, since the fanned and plain rows differ in one field.
5. **Routine + single-table-interface roots.** The two shapes with extra moving parts (routine table
   expressions, discriminator reprojection).
6. **Lookup-root fold + validator mirror + membership enforcer.** The lookup coordinate's row is minted
   by the producer and `lookupMethodName()` retires; the `ValidateMojo` deferred rejection and the
   derived-fact-equals-key-set enforcer land in the same commit, closing the migration dial with no
   window.

## Acceptance

- **SQL equivalence, not byte equivalence.** Moving an inline block behind a command changes the
  generated Java by construction; what must not change is the SQL. The mechanism, fully specified: a new
  execution-tier equivalence pin suite in `graphitron-sakila-example` (working name
  `RootQueryUnitSqlEquivalenceTest`), following the existing per-test-class `SQL_LOG` `ExecuteListener`
  idiom (`GraphQLQueryTest` et al.), asserting **exact rendered SQL strings and statement counts**
  (equality, not the `contains` substring form) for one representative query per covered root shape.
  "Per covered root shape" is a floor, not the whole set: the connection root launches up to three
  statement families from one coordinate (the page query, `totalCount`'s `selectCount`, and a faceted
  carrier's UNION ALL), so the representative set must include a query that selects `totalCount` and one
  against a faceted carrier (`Query.filmsFaceted` in the corpus), or the statement-count half of the pin
  is silent exactly where the cutover carries the most risk.
  Because this item lands after R549 slices 3.1 and 3.2, the select list is already in its final form
  when the suite is authored, so the pin covers the whole statement and needs no carve-out for the
  projection half: add these cases to the programme-level harness R549 slice 2 stands up, authored
  against post-3.2 output and before slice 1's cutover, and every slice keeps them green unchanged.
  Extending that harness rather than starting a second suite matters for a reason beyond tidiness:
  R549's baseline is what pins the keystone, and a root-shape case added to it inherits the same
  frozen-strings rule. Editing expected strings during this item is a defect being papered over, not
  test maintenance.
  The faceted and `totalCount` cases land with slice 3, not slice 1, since the carrier is still
  renderer knowledge until then; slice 1 authors the plain and connection-page cases. (This is a SQL-text pin, which the tier guide permits; the ban is on code-string
  assertions against generated Java method bodies.) The execution tier stays green unchanged throughout.
  No behavioral delta of any kind; that is R471's line, not ours.
- **The relation is non-vacuous and correctly bounded.** Every covered root coordinate in the corpus
  appears exactly once in the launcher relation, and the boundary cases appear zero times (polymorphic
  roots by target shape, node roots, service roots, DML roots). This replaces the level-2 oracle rather
  than extending it, and it is the assertion that catches the silent-skip drift the previous design
  worried about: a coordinate the derived fact is true of but the producer walks past shows up as a
  missing row, not as a leaf that quietly failed to implement a capability.
- **Thinness is a type property, and the edge is data.** A renderer that is a total function over
  `LauncherCommand` cannot do anything the command does not say, so "the fetcher is thin" needs no
  emitted-shape assertion at all: it holds by construction, which is strictly stronger than the graph
  pin the previous design specified. What is asserted instead is the edge view: for every launcher row,
  the projection `UnitRef` it names resolves to a minted projection command, and the level-1 closure
  oracle stays green over the emitted result. Code-string assertions on generated Java bodies stay
  banned at every tier.
- **Second-proof findings are written down, not just absorbed.** This item exists partly to test R549's
  boundaries, so its In Review hand-off states what it found: whether `extras` absorbed the connection
  root's cursor columns cleanly, whether consuming R552's glue `UnitRef` for the `where` slot worked as
  fork 1's resolution assumes, whether the `ConnectionResult` carrier arm held the whole carrier plan,
  and whether the `UnitRef` edge shape survived contact with a second command kind. A slice-3c that reports nothing has not been read carefully, because slice 5
  generalises from the three proofs together (slice 3, this item, and R552's condition command). Per
  the honest note above, this hand-off is the item's product, not a formality attached to it.

## Retired vocabulary

- `QueryLookupTableField.lookupMethodName()` (slice 6): the producer computes this coordinate's
  `UnitRef` like every other row; the emitted `lookup<Field>` method name is unchanged.
- `QueryUnitField` and `MethodCommandRegistry.declareRootQueryUnit` (never built): vocabulary from the
  signed-off version of this spec, listed here so a reader who saw it knows the names were retired at
  the design stage rather than shipped and removed.

The child path's naming facts (`BatchKeyField.rowsMethodName()`,
`MutationField.DmlTableField.reentryRowsMethodName()`) are **no longer retired by this item**. The
signed-off version retired them because its capability interface had to absorb them to keep one
derivation locus; a producer that computes names for its own family imposes nothing on the child path,
so those facts retire when slice 5 folds the child family in. Leaving them alone also keeps this item's
diff inside its own family, which is what a proof of concept wants.

## Out of scope

- The Service-call unit (R333 worklist row 11): the parallel service-backed arm, its own item.
- Direct-SQL `OnlyChild` emit (R471): this item builds the command that strategy becomes an arm of; it
  adds no new strategy itself.
- The child path. Its batched invocation arm, `RowsMethodSkeleton`, and `RowsMethodBody` are untouched,
  and folding the child family into the relation is slice 5's work.
- Lifting `$fields` to regime 1 (row 2), and **row 5's naming lift in its entirety**. Under fork 1's
  resolution this item produces no condition unit and mints no condition name: R552 owns the family
  wholesale and lands first, so what was previously scoped here as "the covered family's coordinates
  only" is now consumption of a `UnitRef`. The four `TypeFetcherGenerator` FQCN recomputation sites and
  `buildConditionCall`'s formula retire in R552's slice 2, before this item's slice 1 touches those
  builders.
- Multi-table polymorphic stage 1 (outside the covered family by the derived fact; note 4
  above) and the hand-rolled polymorphic loader registration.
- Root DML chains (not SELECT launchers; their reentry SELECT is already covered by R314).
