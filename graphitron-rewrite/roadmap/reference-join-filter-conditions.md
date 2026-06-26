---
id: R380
title: "Implement @reference join-subquery filter conditions on input fields and arguments"
status: In Review
bucket: feature
priority: 5
theme: structural-refactor
depends-on: []
created: 2026-06-25
last-updated: 2026-06-26
---

# Implement @reference join-subquery filter conditions on input fields and arguments

## The gap

`@reference` is grammatically accepted on both `INPUT_FIELD_DEFINITION` and `ARGUMENT_DEFINITION`,
but the generated-condition machinery never turns the join path into a join. A `@reference(path:[...])`
filter aiming at a column on a *joined* table is bound against the field's own (local) table instead.
The two surfaces are broken in *different* ways, and R380 fixes both; the shared spine (model +
emitter, below) is identical, only the classifier locus differs.

**Surface 1 — input-object filter fields (`INPUT_FIELD_DEFINITION`; the motivating bug).** Here the
path *is* parsed and carried, then dropped at projection. The chain for the
utdanningsregisteret field `harSelvakkrediteringsretts: Boolean @reference(path: {table: "LARESTED"})
@field(name: "STATUS_SELVAKKREDITERENDE")` inside a `filter:` input on a query returning
`URegOrganisasjon` (`@table(name: "ORGANISASJON")`):

1. `BuildContext` (the `@reference` arm of input-field resolution, `BuildContext.java:2075-2092`)
   calls `parsePath`, resolves `STATUS_SELVAKKREDITERENDE` against the *terminal* table `LARESTED`,
   and constructs `InputField.ColumnReferenceField` carrying `joinPath = path.elements()` and
   `liftedSourceColumns = List.of(col)` where `col` is the LARESTED column. **The path is correct
   and on the carrier.**
2. `FieldBuilder.walkInputFieldConditions`'s `ColumnReferenceField` arm (the non-`@condition`
   branch, `FieldBuilder.java:~1755-1766`) emits `implicitBodyParam(rf.liftedSourceColumns().get(0), …)`
   and **silently drops `rf.joinPath()`**, producing a bare `BodyParam.Eq` over the LARESTED column.
   (The sibling `@condition`-present branch, `:1747-1754`, *does* honor a non-empty `joinPath`: it
   wraps the developer method in `FkTargetConditionFilter` so the call site emits a correlated
   `EXISTS`. That is R330's call-site path for dev-written methods, see "Design B" below; the bug is
   that the *implicit-predicate* branch has no such treatment.)
3. `TypeConditionsGenerator.buildConditionMethod` (`TypeConditionsGenerator.java:111`) binds it as
   `table.STATUS_SELVAKKREDITERENDE` with `table` = `ORGANISASJON` (`:127`, `:137`, `:151`, `:163`);
   the column isn't on `ORGANISASJON`, so the generated `*Conditions.java` does not compile (the six
   observed errors).

**Surface 2 — direct scalar arguments (`ARGUMENT_DEFINITION`).** Here the path is never even read.
`FieldBuilder.classifyArgument` reads orderBy / pagination / `@condition` / `@lookupKey` / `@nodeId` /
`@field`, but **no `@reference`**; a scalar arg carrying `@reference(path:[...])` falls through to the
scalar-binding tail (`FieldBuilder.java:1334-1359`), which reads only `@field(name:)` and resolves the
column against `rt.tableName()` (the field's own return-type table), producing
`ArgumentRef.ScalarArg.ColumnArg` / `CompositeColumnArg` → `projectFilters` (`:1574-1601`) →
`BodyParam.Eq/In/RowEq/RowIn`. Because the path is dropped at the *start*, the column resolves against
the local table from the outset: an `UnboundArg` rejection ("column '…' could not be resolved in
table '…'") when the name is absent locally, or a silently mis-bound predicate against the wrong
column when a same-named column collides on the local table.

Neither surface produces the join-subquery the author asked for.

Observed in the wild (utdanningsregisteret): `filter:`-input fields referencing
`STATUS_SELVAKKREDITERENDE`, `NKRKODE`, `UTDANNINGSSPESIFIKASJONSTYPE_KODE`,
`STATUS_GYLDIG_AKKREDITERING` resolved against `Organisasjon` / `Utdanningsmulighet` /
`Utdanningsspesifikasjon`, none of which carry those columns; the author expected the path to
produce a join-subquery condition. These are all Surface 1 (input-object filter fields).

### Relationship to the existing `@nodeId` FK-target arm

`ArgumentRef.ScalarArg.ColumnReferenceArg` / `CompositeColumnReferenceArg`
(`ArgumentRef.java:144-195`) is the structural *sibling* of this work, not a partial
implementation of it. That arm fires only for `@nodeId(typeName: T)` scalar args; its
`joinPath` is FK-resolved by `NodeIdLeafResolver`, not parsed from `@reference(path:)`. And it
always *lifts to a local column*: `projectFilters` (`:1605-1646`) binds the decoded keys
against the FK source columns on the field's own table when those columns positionally match
the target NodeType keys (the direct-FK case), so no join is emitted at all; the non-aligned
("translated FK") case is rejected/deferred (`nodeid-fk-target-arg-join-translation.md`).

R380 is the general `@reference(path:)` analog whose terminal column is *not* a liftable local
FK key, so it genuinely needs the correlated subquery. The Spec keeps the two arms distinct: do
not route R380 through `ColumnReferenceArg` (it carries `@nodeId` decode semantics R380 does not
have); introduce a dedicated remote-column carrier and mirror the projection shape.

## Decision: correlated EXISTS, emitted inside the generated condition method

Decision (with the user): implement the join now rather than reject-loudly. A filter argument
with `@reference` emits a correlated `EXISTS` subquery that joins through the path and applies
the predicate against the *terminal* alias, not the local table.

**Design A (chosen).** Emit the `EXISTS` *inside* the generated `<ReturnType>Conditions.<field>Condition`
method body. The method keeps its signature `(Table table, args…) -> Condition`; for a remote
argument it ANDs in `DSL.exists(DSL.selectOne().from(terminalAlias).join(…).where(<correlation
back to table>.and(<predicate on terminalAlias>)))`, correlated against its own `table`
parameter. The method stays a pure function of `(table, args)` with no graphql-java dependency.
Crucially, **the call sites do not change**: both `QueryConditionsGenerator` (root-query env shim)
and `InlineTableFieldEmitter.buildInnerSelect` keep calling `<Class>.<method>(alias, args)` via
`FkTargetConditionEmitter.emitTerm`, and the `EXISTS` correlates back to whatever alias they pass.

**Design B (rejected).** Lift reference-filtered args to a separate `FkTargetConditionFilter`-style
`WhereFilter` and emit the `EXISTS` at the call sites. Rejected as a forced unification: the
FK-target case lives at the call site *only because* its delegate is a developer-written method
whose first parameter is a foreign target table we cannot change (`FkTargetConditionFilter.java:11-18`).
R380 has no such constraint, `we` write the body, so the `EXISTS` belongs in the body. `B` would
also force generalizing `FkTargetConditionEmitter`, which is structurally FkJoin-only and
dev-method-only (`FkTargetConditionEmitter.java:127-141`), to also emit generated predicates and
`ConditionJoin` hops, the two axes the shared-vs-forked principle uses to keep them separate.

The machinery to reuse:

* `BuildContext.parsePath` (`BuildContext.java:1178`) already turns `@reference(path:[...])` into
  `List<JoinStep>` (`FkJoin` / `ConditionJoin`), with single-FK auto-discovery, multi-hop, and
  terminal-table resolution. The classifier calls it exactly as the output-side reference fields do.
* `InlineTableFieldEmitter.buildInnerSelect` (`:130`) is the shape to mirror: FROM terminal alias,
  JOIN chain walking back toward step 0, WHERE step-0 parent-correlation `.and(...)` the predicate.
* `JoinPathEmitter.generateAliases` / `emitCorrelationWhere` (FkJoin) / `emitTwoArgMethodCall`
  (ConditionJoin) are the per-hop emitters.

## Model changes

Add a wrapping remote-predicate variant rather than widening the four `ColumnPredicate` arms.
Adding `List<JoinStep> joinPath` to each of `Eq/In/RowEq/RowIn` would put a second, orthogonal
axis (local-vs-remote) onto a sealed hierarchy whose axis is operator/value-arity, reintroducing
the `if (joinPath.isEmpty())` ladder the `BodyParam.ColumnPredicate` doc explicitly celebrates the
absence of (`BodyParam.java:70-75`). Instead:

```
sealed interface BodyParam permits ColumnPredicate, RemoteColumnPredicate { … }

record RemoteColumnPredicate(
    List<JoinStep> joinPath,      // resolved path from the field's own table to the terminal table
    ColumnPredicate inner         // the existing Eq/In/RowEq/RowIn, its columns bound to the terminal table
) implements BodyParam {
    // name()/list()/nonNull()/extraction() delegate to inner
}
```

This mirrors how `FkTargetConditionFilter` wraps a `ConditionFilter` delegate instead of bolting
a `joinPath` field onto `ConditionFilter` (`FkTargetConditionFilter.java:60-66`). The inner
predicate's `ColumnRef`(s) carry the terminal-table columns; the wrapper carries how to reach them.

`GeneratedConditionFilter` itself is unchanged (no path field): one method groups several args that
may target different remote tables via different paths, so the path is necessarily per-`BodyParam`.

## Classifier changes (`FieldBuilder`)

Both surfaces converge on the same projected output: a `BodyParam.RemoteColumnPredicate(joinPath, inner)`
in the field's `GeneratedConditionFilter`. They differ only in where the path is read and where the
wrap happens.

### Surface 1 — input-object filter fields (`walkInputFieldConditions`)

The path is already parsed and carried on `InputField.ColumnReferenceField.joinPath()`
(`BuildContext.java:2075-2092`); **no `BuildContext`/`parsePath` change is needed here**. The fix is to
stop dropping it. In the `ColumnReferenceField` arm of `walkInputFieldConditions`
(`FieldBuilder.java:~1755-1766`), the non-`@condition` branch currently builds
`implicitBodyParam(rf.liftedSourceColumns().get(0), …)`. When `rf.joinPath()` is non-empty **and** this
is a plain `@reference` (not the `@nodeId` lift; see the discrimination note below), wrap the resulting
`ColumnPredicate` in `RemoteColumnPredicate(rf.joinPath(), inner)`. The same treatment applies to the
`CompositeColumnReferenceField` arm (`RowEq`/`RowIn` inner over the terminal columns).

**The nodeId-vs-plain-`@reference` discrimination is load-bearing.** `ColumnReferenceField` is produced
for two cases whose `liftedSourceColumns` mean *different tables*:

* `@nodeId` FK-target (DirectFk): `liftedSourceColumns` are FK child columns *on the parent's own
  table*, positionally aligned with the decoded NodeType keys. The predicate is correctly bound to the
  local `table` today (the lift means no join is needed). This case must **not** be wrapped.
* plain `@reference` (the bug): `liftedSourceColumns` is the *terminal* column on the joined table. This
  case **must** go through the `EXISTS`.

The carrier does not currently distinguish these by type, only by provenance (the `@nodeId` arm sets a
`NodeIdDecodeKeys` extraction and lifts source columns; the plain-`@reference` arm sets `Direct` and
stores the terminal column, `BuildContext.java:2086-2089`). The implementer should pick the cleanest
available discriminator (the `Direct`-vs-`NodeIdDecodeKeys` extraction split is the most direct signal;
a sealed sub-variant on `ColumnReferenceField` is the more generation-thinking-aligned option if the
provenance fork proves to recur) and **record the choice in the implementation commit**. Conflating the
two `liftedSourceColumns` meanings under one slot is what dropped the join in the first place; the fix
must not re-conflate them.

### Surface 2 — direct scalar arguments (`classifyArgument` scalar tail)

Here the path is never read. In the scalar-binding tail of `classifyArgument` (`:1334`), before the
local `findColumn`:

* If the arg carries `@reference`, call `ctx.parsePath(arg, name, rt.tableName(), <terminal-sql-name>)`.
  Resolve the filtered column against the *terminal* table (the path's last `HasTargetTable.targetTable()`),
  honoring `@field(name:)` for the terminal column name as today.
* Produce a remote `ColumnArg`/`CompositeColumnArg` carrying the parsed `joinPath`. Easiest seam:
  give `ColumnArg`/`CompositeColumnArg` an optional `joinPath` (empty = local, today's behavior) and
  have `projectFilters` wrap the resulting `ColumnPredicate` in a `RemoteColumnPredicate` when the
  path is non-empty. (Decide in implementation whether the path rides on the existing arg records or a
  new sibling arg record; the `BodyParam` shape above is the load-bearing decision, the `ArgumentRef`
  carrier is mechanical.)
* `projectFilters` (`:1574-1601`): when the column arg carries a path, emit
  `new RemoteColumnPredicate(path, <Eq/In/RowEq/RowIn over terminal column>)` instead of the bare arm.
  The `callParams` projection (`:1659-1660`) is unchanged: a remote predicate's call-site argument
  extraction is identical to a local one (the value still comes from `env.getArgument`); only the SQL
  shape differs.

## Emitter changes (`TypeConditionsGenerator`)

`buildConditionMethod` (`:111`) gains one new top-level switch arm for `RemoteColumnPredicate`; the four
existing arms stay byte-for-byte:

* Refactor the per-arm predicate emission (`:126-184`) into a helper
  `emitColumnPredicateTerm(ColumnPredicate, String aliasLocal)` returning the `condition.and(...)` /
  guarded `.and(...)` `CodeBlock`, parameterized on the alias the columns bind against (today hardcoded
  `table`; `buildTypedCols` at `:211` likewise gains an alias parameter). Local predicates call it with
  `"table"`; nothing else changes for them.
* The `RemoteColumnPredicate` arm:
  1. declares one aliased jOOQ table local per hop (reuse `JoinPathEmitter.generateAliases` + the
     alias-declaration loop from `InlineTableFieldEmitter` `:91-97`; aliases are method-local statics,
     this method does not recurse, so no runtime-prefixing is needed, cf. `QueryConditionsGenerator`'s
     `declareAliases(..., false)` at `:185`);
  2. builds the inner `DSL.selectOne().from(terminalAlias)` + JOIN chain (mirror `buildInnerSelect`
     `:142-154`);
  3. composes WHERE = step-0 correlation back to `table` (`emitCorrelationWhere` for an `FkJoin`
     first hop) `.and(emitColumnPredicateTerm(inner, terminalAlias))`;
  4. wraps the whole thing in `DSL.exists(...)` and ANDs it into the method's `condition`.
* **Null / empty-list semantics carry through unchanged.** The existing `if (arg != null)` /
  `if (!list.isEmpty())` guards wrap the entire `.and(DSL.exists(...))` term, so an absent scalar or
  empty list still contributes no predicate, identical to the local-column guards (`:132`, `:142-150`,
  `:175-183`). This is the `In`/`RowIn` empty-guard parity the Backlog note asked for.
* **Sealed-permit fallout.** Adding `RemoteColumnPredicate` to the sealed `BodyParam` makes two other
  switches non-exhaustive until they gain an arm: `paramType(bp)` (`TypeConditionsGenerator.java:190`,
  which builds the method parameter per body param) and `bodyParamCallTypeName(bp)` (`FieldBuilder`, read
  at `:1944`). Both arms are trivial delegations to `inner` (`name()/list()/nonNull()/extraction()`
  delegate, so the parameter type and call-type are the inner predicate's). The compiler forces both;
  this note exists only so "the `callParams` projection is unchanged" is not read as "the sealed addition
  is free".

## Validator (`GraphitronSchemaValidator`)

Mirror the new classifier branches (validator-mirrors-classifier invariant) at **both** mirror sites —
the input-field walk and the scalar-argument projection. The moment the classifier reads `@reference`
and produces a remote predicate, an unresolvable path or an unsupported terminal/hop kind (a
`ConditionJoin` hop, deferred below) must surface as a typed `Rejection` at validate time, never an
`IllegalStateException` at emit. Both sites reject the same two conditions, so this is one rejection
shape applied at two mirrors, not new logic per site. For Surface 1 the natural home is alongside
`validateInputColumnReferenceField` (which today guards only the `@condition`-present FK-target case);
for Surface 2 it is the `ArgumentRef`/`projectFilters` mirror. Follow the `Rejection.structural`
validator path the surrounding deferred FK-target cases already use (`:1100-1106`, `:1128-1132` —
`validateInputCompositeColumnReferenceField` / `validateInputUnboundField`; these build a
`ValidationError` carrying `Rejection.structural(...)`, they do not use `UnclassifiedArg`).
`parsePath`'s own error string is the diagnostic for an unresolvable path.

## Scope and deferrals (reviewer: confirm the v1 boundary)

* **Hop kinds.** v1 supports `FkJoin` paths (the `{key:}` and `{table:}` reference forms that resolve to
  a foreign key, including single-FK auto-discovery). `ConditionJoin` (`{condition:}`) hops are *deferred*:
  the `condition:` reference form is still a runtime-throwing stub on the output side
  (`join-with-references.adoc:195-199`), so a reference *filter* path that traverses a `ConditionJoin`
  is rejected with a typed diagnostic pointing at that limitation. `FkTargetConditionEmitter` takes the
  same posture today (`:131-141`). Folding `ConditionJoin` in later is additive (the inner-select join
  chain in `InlineTableFieldEmitter` `:153` already shows the `.on(method(...))` shape).
* **`@splitQuery` interaction.** A split reference field relocates the join into a separate query
  (`SplitRowsMethodEmitter`, `JoinStep.LiftedHop`). Investigate during implementation whether a remote
  *filter* predicate on a split field is reachable, and if so whether the generated condition method is
  still invoked with the correct alias. If the combination is not cleanly supported, reject it with a
  typed diagnostic rather than emit a degenerate query; do not silently drop the filter. Capture the
  finding in the implementation commit.
* **Multi-hop and composite keys** are in scope: the JOIN chain handles multi-hop, and `RowEq`/`RowIn`
  inner predicates emit `DSL.row(terminalAlias.c1, …).eq/in(...)` against the terminal alias.

## Tests

* **Pipeline tier** (primary): assert the generated `<Type>Conditions` `TypeSpec` contains the
  EXISTS-bearing condition method (assert on the model/`TypeSpec` structure, not code-string bodies, per
  design principles). **Cover both surfaces** with the same matrix, since they project to the same
  `RemoteColumnPredicate`: (1) `@reference(path:[...])` on an **input-object filter field** whose terminal
  column lives on a joined table (the motivating `harSelvakkrediteringsretts` / `LARESTED` shape — this is
  the regression guard for the actual bug); (2) `@reference(path:[...])` on a **direct scalar argument**.
  Across the two, exercise: single-hop `{key:}`; single-hop `{table:}` with auto-discovered FK; multi-hop;
  composite-key terminal (`RowIn`); a nullable scalar (guard present) and a non-null scalar (unguarded);
  an empty-list `In` (guard present). Add a discrimination guard: an `@nodeId` FK-target input field on
  the *same* type still binds to the local table (no EXISTS) — proof the nodeId-vs-plain-`@reference`
  fork did not regress. Add the inverse: a `ConditionJoin`-bearing reference filter path produces a clean
  rejection (at both mirror sites).
* **Classification / corpus.** If the remote-column filter introduces a new classification verdict,
  thread it through the R281 corpus + `code-generation-triggers.adoc` (see the `classified-corpus` skill).
* **Execution tier** (backstop): add a sakila-example schema field exercising a remote-column reference
  filter (e.g. filter `Film` by a column on `language` reached through `film.language_id`, or a two-hop
  path) and assert the query returns the right rows against the seeded DB. This is the proof the EXISTS
  is semantically correct, not merely well-formed. Reuse `ReferencePathConditionFixtures` /
  `GraphQLQueryTest` as the host.

## Docs (first-client check)

Reconcile `docs/manual/how-to/join-with-references.adoc`. The "Input-field references" section
(`:172-193`) currently presents the input-side `@reference` only through the `ColumnReferenceField`
projection-vs-predicate framing and a single-`{table:}` example. Extend it to state plainly that a
scalar filter argument (or input field) carrying `@reference(path:)` whose terminal column lives on a
joined table emits a correlated `EXISTS` subquery, joining through the path and filtering on the terminal
column; show a multi-hop example and the null/empty-list "contributes no predicate" semantics. Note the
`{condition:}`-path limitation inline if it still holds when this ships. If the prose does not read simply,
the design is wrong, revise the design first. Keep roadmap-internal vocabulary (`R<n>`, "deferred", slugs)
out of the chapter.

## Dependencies and siblings

Depends conceptually on **R379** (terminal-hop resolution): both hinge on the path's terminal table being
correctly resolved, and R380's classifier resolves the filtered column against that terminal table. If
R379 lands first, R380 inherits its hardened terminal-hop validation; if not, R380 must not assume the
terminal hop is well-formed (the validator branch above is its own guard). Siblings in the `@reference`
diagnostics family: R236 (candidate-hint terminal table), R282 (FK-key hint scope), and the deferred
`nodeid-fk-target-arg-join-translation` item (the `@nodeId` FK-target translated-key case).

## Revision history

**Spec → Spec revise (2026-06-25, independent reviewer session).** A Spec → Ready review traced the
motivating bug into the code and reframed the plan; findings folded into the body above rather than left
as a note. Substance:

* The motivating utdanningsregisteret bug is on **input-object filter fields**
  (`INPUT_FIELD_DEFINITION`), not direct scalar arguments. Confirmed it surfaces in
  `TypeConditionsGenerator.buildConditionMethod` (the six `*Conditions.java` errors), via
  `BuildContext` parsing the path correctly onto `InputField.ColumnReferenceField` but
  `walkInputFieldConditions` dropping `joinPath` at projection. The original draft pointed its
  "Classifier changes" only at `classifyArgument`'s scalar tail (`:1157`), which is a *different,
  also-broken* surface — so the original plan would not have fixed the reported bug.
* Decision (with the user): **fix both surfaces in R380** (they share the model + emitter spine; only
  the classifier locus differs). "The gap" now documents both; "Classifier changes" is split into
  Surface 1 (`walkInputFieldConditions`, path already parsed — stop dropping it; discriminate
  nodeId-lift from plain-`@reference`) and Surface 2 (`classifyArgument` scalar tail, original prose).
* Folded in: the `paramType`/`bodyParamCallTypeName` sealed-permit fallout (Emitter section), the
  `UnclassifiedArg` → `Rejection.structural` wording fix and the two-mirror-site note (Validator
  section), and both-surface + nodeId-discrimination test coverage (Tests section).
* Citations against the model, emitter, and doc surfaces (the `BodyParam permits ColumnPredicate` decl,
  `BuildContext.java:2075-2092`, `InputField.ColumnReferenceField`, `JoinPathEmitter`,
  `InlineTableFieldEmitter.buildInnerSelect`, `FkTargetConditionFilter`) were judged sound. The
  Design A-vs-B fork and the `RemoteColumnPredicate` sealed-axis shape were judged sound and are
  unchanged.

**Spec → Spec revise (2026-06-26, second independent reviewer session).** A Spec → Ready review
verified every cited symbol and line number against current trunk. All symbols resolve. The
`BuildContext`, `ArgumentRef`, `BodyParam`, `FkTargetConditionFilter`, `InlineTableFieldEmitter`,
`JoinPathEmitter`, `QueryConditionsGenerator`, `FkTargetConditionEmitter`, `GraphitronSchemaValidator`,
and `join-with-references.adoc` citations resolve at their stated locations. But **every
`FieldBuilder.java` and `TypeConditionsGenerator.java` line number had drifted** (trunk advanced
above those methods after the prior anchoring), so the previous revision's "Citations verified
accurate" claim — which named exactly `FieldBuilder.java:1157/1397/1428/1481/1533` and
`TypeConditionsGenerator.java:85/185`, all stale — was itself a false invariant of the kind
*Documentation names only live tests/code* warns against. Re-anchored to current trunk:
`classifyArgument` scalar tail `:1157`→`:1334`; `walkInputFieldConditions` `ColumnReferenceField` arm
`~1383-1395`→`~1755-1766`; bare `projectFilters` arms `:1397-1427`→`:1574-1601`; `ColumnReferenceArg`
arm `:1428-1472`→`:1605-1646`; `callParams` `:1481-1483`→`:1659-1660`; `bodyParamCallTypeName`
`:1483`→`:1944`; `buildConditionMethod` `:85`→`:111`; per-arm emission `:101-151`→`:126-184`; the four
binding arms `:104/:118/:130/:145`→`:127/:137/:151/:163`; the empty-list guards
`:107/:117-122/:144-149`→`:132/:142-150/:175-183`; `paramType` `:95`→`:190`; `buildTypedCols`
`:185`→`:211`; `FkTargetConditionFilter` record decl `:41-47`→`:60-66`; `declareAliases` `:184`→`:185`.
Also added (clarity, not a plan change): a note in "The gap" step 2 that R330's `@condition`-present
`ColumnReferenceField` branch (`:1747-1754`) already honors `joinPath` via `FkTargetConditionFilter`
(the call-site "Design B" mechanism for dev-written methods), so the implementer reads Design A as the
generated-method sibling for the *implicit-predicate* branch, not a from-scratch decision. The
Design A-vs-B verdict and the `RemoteColumnPredicate` shape are unchanged.

This reviewer session became the last committer of this revision, so the next **Spec → Ready** sign-off
must come from a session distinct from both prior reviewer sessions and this one.
