---
id: R380
title: "Implement @reference join-subquery filter conditions on input fields"
status: Spec
bucket: feature
priority: 5
theme: structural-refactor
depends-on: []
created: 2026-06-25
last-updated: 2026-06-25
---

# Implement @reference join-subquery filter conditions on input fields

## The gap

`@reference` is grammatically accepted on `ARGUMENT_DEFINITION` / `INPUT_FIELD_DEFINITION`,
but the generated-condition machinery ignores the join path for the *scalar-filter-argument*
surface. A scalar filter argument carrying `@reference(path:[...])` aiming at a column on a
*joined* table never gets its path read:

* `FieldBuilder.classifyArgument`'s scalar-binding tail (`FieldBuilder.java:1157-1183`)
  resolves the column against `rt.tableName()` (the field's own return-type table) and reads
  only `@field(name:)` for renaming. It never reads `@reference`. It produces
  `ArgumentRef.ScalarArg.ColumnArg` / `CompositeColumnArg`.
* `FieldBuilder.projectFilters` (`:1397-1427`) projects those into `BodyParam.Eq/In/RowEq/RowIn`
  and groups all of a field's filter args into one `GeneratedConditionFilter` (carries
  `tableRef` + `bodyParams`, no path).
* `TypeConditionsGenerator.buildConditionMethod` (`TypeConditionsGenerator.java:85`) emits the
  entity-scoped pure-function method `<ReturnType>Conditions.<field>Condition(Table table, args…)`
  whose body emits every predicate as `table.<COLUMN>` (`:104`, `:118`, `:130`, `:145`). The
  local-table binding is baked in here.

Because the column does not exist on the local table, the outcome today is one of: an
`UnboundArg` rejection ("column '…' could not be resolved in table '…'") when the name is
absent locally; or a silently mis-bound predicate against the wrong column when a column of
that name happens to collide on the local table; or, where a stale binding survives into emit,
non-compiling generated source. None of these is the join-subquery the author asked for.

Observed in the wild (utdanningsregisteret): filter fields referencing
`STATUS_SELVAKKREDITERENDE`, `NKRKODE`, `UTDANNINGSSPESIFIKASJONSTYPE_KODE`,
`STATUS_GYLDIG_AKKREDITERING` resolved against `Organisasjon` / `Utdanningsmulighet` /
`Utdanningsspesifikasjon`, none of which carry those columns; the author expected the path to
produce a join-subquery condition.

### Relationship to the existing `@nodeId` FK-target arm

`ArgumentRef.ScalarArg.ColumnReferenceArg` / `CompositeColumnReferenceArg`
(`ArgumentRef.java:144-195`) is the structural *sibling* of this work, not a partial
implementation of it. That arm fires only for `@nodeId(typeName: T)` scalar args; its
`joinPath` is FK-resolved by `NodeIdLeafResolver`, not parsed from `@reference(path:)`. And it
always *lifts to a local column*: `projectFilters` (`:1428-1472`) binds the decoded keys
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

* `BuildContext.parsePath` (`BuildContext.java:1131`) already turns `@reference(path:[...])` into
  `List<JoinStep>` (`FkJoin` / `ConditionJoin`), with single-FK auto-discovery, multi-hop, and
  terminal-table resolution. The classifier calls it exactly as the output-side reference fields do.
* `InlineTableFieldEmitter.buildInnerSelect` (`:125`) is the shape to mirror: FROM terminal alias,
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
a `joinPath` field onto `ConditionFilter` (`FkTargetConditionFilter.java:41-47`). The inner
predicate's `ColumnRef`(s) carry the terminal-table columns; the wrapper carries how to reach them.

`GeneratedConditionFilter` itself is unchanged (no path field): one method groups several args that
may target different remote tables via different paths, so the path is necessarily per-`BodyParam`.

## Classifier changes (`FieldBuilder`)

In the scalar-binding tail of `classifyArgument` (`:1157`), before the local `findColumn`:

* If the arg carries `@reference`, call `ctx.parsePath(arg, name, rt.tableName(), <terminal-sql-name>)`.
  Resolve the filtered column against the *terminal* table (the path's last `HasTargetTable.targetTable()`),
  honoring `@field(name:)` for the terminal column name as today.
* Produce a remote `ColumnArg`/`CompositeColumnArg` carrying the parsed `joinPath`. Easiest seam:
  give `ColumnArg`/`CompositeColumnArg` an optional `joinPath` (empty = local, today's behavior) and
  have `projectFilters` wrap the resulting `ColumnPredicate` in a `RemoteColumnPredicate` when the
  path is non-empty. (Decide in implementation whether the path rides on the existing arg records or a
  new sibling arg record; the `BodyParam` shape above is the load-bearing decision, the `ArgumentRef`
  carrier is mechanical.)
* `projectFilters` (`:1397-1427`): when the column arg carries a path, emit
  `new RemoteColumnPredicate(path, <Eq/In/RowEq/RowIn over terminal column>)` instead of the bare arm.
  The `callParams` projection (`:1481-1483`) is unchanged: a remote predicate's call-site argument
  extraction is identical to a local one (the value still comes from `env.getArgument`); only the SQL
  shape differs.

## Emitter changes (`TypeConditionsGenerator`)

`buildConditionMethod` (`:85`) gains one new top-level switch arm for `RemoteColumnPredicate`; the four
existing arms stay byte-for-byte:

* Refactor the per-arm predicate emission (`:101-151`) into a helper
  `emitColumnPredicateTerm(ColumnPredicate, String aliasLocal)` returning the `condition.and(...)` /
  guarded `.and(...)` `CodeBlock`, parameterized on the alias the columns bind against (today hardcoded
  `table`; `buildTypedCols` at `:185` likewise gains an alias parameter). Local predicates call it with
  `"table"`; nothing else changes for them.
* The `RemoteColumnPredicate` arm:
  1. declares one aliased jOOQ table local per hop (reuse `JoinPathEmitter.generateAliases` + the
     alias-declaration loop from `InlineTableFieldEmitter` `:91-97`; aliases are method-local statics,
     this method does not recurse, so no runtime-prefixing is needed, cf. `QueryConditionsGenerator`'s
     `declareAliases(..., false)` at `:184`);
  2. builds the inner `DSL.selectOne().from(terminalAlias)` + JOIN chain (mirror `buildInnerSelect`
     `:142-154`);
  3. composes WHERE = step-0 correlation back to `table` (`emitCorrelationWhere` for an `FkJoin`
     first hop) `.and(emitColumnPredicateTerm(inner, terminalAlias))`;
  4. wraps the whole thing in `DSL.exists(...)` and ANDs it into the method's `condition`.
* **Null / empty-list semantics carry through unchanged.** The existing `if (arg != null)` /
  `if (!list.isEmpty())` guards wrap the entire `.and(DSL.exists(...))` term, so an absent scalar or
  empty list still contributes no predicate, identical to the local-column guards (`:107`, `:117-122`,
  `:144-149`). This is the `In`/`RowIn` empty-guard parity the Backlog note asked for.

## Validator (`GraphitronSchemaValidator`)

Mirror the new classifier branch (validator-mirrors-classifier invariant). The moment the classifier
reads `@reference` and produces a remote predicate, an unresolvable path or an unsupported terminal/hop
kind must surface as a typed `Rejection` at validate time, never an `IllegalStateException` at emit.
Reuse the `UnclassifiedArg` + `Rejection.structural` path the surrounding deferred cases already use
(`:1100-1106`, `:1128-1132`). `parsePath`'s own error string is the diagnostic for an unresolvable path.

## Scope and deferrals (reviewer: confirm the v1 boundary)

* **Hop kinds.** v1 supports `FkJoin` paths (the `{key:}` and `{table:}` reference forms that resolve to
  a foreign key, including single-FK auto-discovery). `ConditionJoin` (`{condition:}`) hops are *deferred*:
  the `condition:` reference form is still a runtime-throwing stub on the output side
  (`join-with-references.adoc:195-199`), so a reference *filter* path that traverses a `ConditionJoin`
  is rejected with a typed diagnostic pointing at that limitation. `FkTargetConditionEmitter` takes the
  same posture today (`:131-141`). Folding `ConditionJoin` in later is additive (the inner-select join
  chain in `InlineTableFieldEmitter` `:148` already shows the `.on(method(...))` shape).
* **`@splitQuery` interaction.** A split reference field relocates the join into a separate query
  (`SplitRowsMethodEmitter`, `JoinStep.LiftedHop`). Investigate during implementation whether a remote
  *filter* predicate on a split field is reachable, and if so whether the generated condition method is
  still invoked with the correct alias. If the combination is not cleanly supported, reject it with a
  typed diagnostic rather than emit a degenerate query; do not silently drop the filter. Capture the
  finding in the implementation commit.
* **Multi-hop and composite keys** are in scope: the JOIN chain handles multi-hop, and `RowEq`/`RowIn`
  inner predicates emit `DSL.row(terminalAlias.c1, …).eq/in(...)` against the terminal alias.

## Tests

* **Pipeline tier** (primary): SDL with `@reference(path:[...])` on a scalar filter arg whose terminal
  column lives on a joined table → assert the generated `<Type>Conditions` `TypeSpec` contains the
  EXISTS-bearing condition method (assert on the model/`TypeSpec` structure, not code-string bodies, per
  design principles). Cover: single-hop `{key:}`; single-hop `{table:}` with auto-discovered FK; multi-hop;
  composite-key terminal (`RowIn`); a nullable scalar (guard present) and a non-null scalar (unguarded);
  an empty-list `In` (guard present). Add the inverse: a `ConditionJoin`-bearing reference filter path
  produces a clean rejection.
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

## Reviewer note (Spec → Spec, 2026-06-25, independent session)

Spec → Ready review by an independent session; **not signed off**. One material finding, two minor.
Code citations spot-checked and accurate (`FieldBuilder.java:1157/1397/1428/1481`, `TypeConditionsGenerator.java:85/185`,
the `BodyParam permits ColumnPredicate` decl, `BuildContext.parsePath`, `JoinPathEmitter.generateAliases/emitCorrelationWhere/emitTwoArgMethodCall`,
`InlineTableFieldEmitter.buildInnerSelect`, `FkTargetConditionFilter`). The Design A vs B fork is well-reasoned
against the shared-vs-forked principle; the `RemoteColumnPredicate` wrapper respects the sealed-axis principle
(no `joinPath` field bolted onto the four `ColumnPredicate` arms); the test plan asserts on `TypeSpec` structure
(not code-strings) and covers single/multi-hop, composite `RowIn`, null/empty guards, `ConditionJoin` rejection,
and an execution-tier backstop, which is adequate **for the scalar-argument carrier**.

1. **(Material) Carrier ambiguity: scalar argument vs input-object field.** The title ("…on input fields"), the
   "Observed in the wild" framing ("filter fields"), and the Docs section's "(or input field)" parenthetical
   reach the `INPUT_FIELD_DEFINITION` surface. That surface resolves to a *different* carrier,
   `InputField.ColumnReferenceField` (constructed in `BuildContext.java:1879/2131/2188`; its javadoc already states
   "the generator must JOIN through `joinPath` before applying the column predicate", `InputField.java:90-91`), with
   its own resolution path and its own emit path (the input-condition path, **not** `TypeConditionsGenerator`). But
   every implementation section here — Model changes, Classifier changes, Emitter changes — targets exclusively the
   `ARGUMENT_DEFINITION` surface (`classifyArgument`'s scalar tail → `ArgumentRef.ScalarArg.ColumnArg` → `BodyParam`
   → `TypeConditionsGenerator`). As written, an implementer cannot tell which carrier R380 fixes, and if the
   motivating utdanningsregisteret bug lives on input-object filter fields, the plan as drafted would not fix it.
   Resolve by: (a) stating whether the observed bug is on scalar `ARGUMENT_DEFINITION` args or `INPUT_FIELD_DEFINITION`
   input-object fields; (b) aligning the title and the Docs section with the carrier(s) actually implemented; and
   (c) if input-object fields are in scope, giving `InputField.ColumnReferenceField` its own classifier/emitter/test
   plan (its emitter is not `TypeConditionsGenerator`), or, if out of scope, dropping "(or input field)" from Docs
   and listing `InputField.ColumnReferenceField` remote predicates in "Out of scope" as the sibling gap.

2. **(Minor) Generator-side exhaustive switches over `BodyParam`.** The "callParams projection (`:1481-1483`) is
   unchanged" claim holds for the projection itself, but `paramType(bp)` (`TypeConditionsGenerator.java:95`) and
   `bodyParamCallTypeName(bp)` (`FieldBuilder`) are exhaustive switches over the sealed `BodyParam`; adding
   `RemoteColumnPredicate` forces a delegating arm in each. The compiler catches it, but name it so "unchanged" is
   not read too literally.

3. **(Minor) Validator citation precision.** § Validator cites "the `UnclassifiedArg` + `Rejection.structural` path
   … (`:1100-1106`, `:1128-1132`)". Those lines are the R330/R215 `Rejection.structural` validator methods
   (`validateInputCompositeColumnReferenceField` / `validateInputUnboundField`) — the right precedent, but they do
   not use `UnclassifiedArg`. Tighten to "the `Rejection.structural` validator path".

Sign off: no — return to author to resolve finding 1 (carrier scope); a fresh independent session signs off afterward.
