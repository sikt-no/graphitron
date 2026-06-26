---
id: R379
title: "Validate @reference path joins compile: terminal-hop target and condition parameter tables"
status: In Review
bucket: bug
priority: 5
theme: structural-refactor
depends-on: []
created: 2026-06-25
last-updated: 2026-06-26
---

# Validate @reference path joins compile: terminal-hop target and condition parameter tables

> An `@reference` path can compile to generated Java that javac rejects with an
> incompatible-types error in two ways: (a) the terminal hop lands on a table
> other than the field return type's `@table`, so the terminal alias is fed to a
> `$fields` overload typed for the return table; (b) a condition method
> (a `ConditionJoin` ON clause, or an `FkJoin` whereFilter) declares a
> concretely-typed jOOQ `Table` parameter that does not match the source/target
> alias the emitter passes it positionally. Both are the same failure family in
> the worst place: a javac error in generated code, surfaced in a downstream
> consumer's build. Move both to build time inside `parsePath`, where every hop's
> source and target table are already resolved. After the hops resolve, assert:
> the terminal hop's already-resolved target table matches the return table; and
> every two-argument condition method's *concretely-typed* table parameters match
> the `(source, target)` tables it will receive (wildcard `Table<?>` parameters
> are unverifiable and accepted). The checks read R232's resolved
> `JoinStep.HasTargetTable.targetTable()` and the condition method's already
> reflected `MethodRef` parameter types; they do not re-derive the hop kind from
> the directive element, nor re-validate intermediate connectivity that
> `parsePathElement` already enforces step by step.

## Symptom

`InlineTableFieldEmitter.buildArm` carries a silent invariant: the `@reference`
path's terminal hop must land on the field return type's `@table`. The emitter
declares an aliased jOOQ table per hop off `ht.targetTable().tableClass()`
(`InlineTableFieldEmitter.java:91-97`), then feeds the terminal alias into
`<ReturnType>.$fields(selectionSet, terminalAlias, env)`
(`InlineTableFieldEmitter.java:133-134`), whose signature was generated for the
return type's table. When the terminal hop's target differs from the return
type's `@table`, javac rejects the *generated* source with an incompatible-types
error, the worst place for the failure to surface.

(Line numbers have drifted from the original filing's `:63/:84/:133`; the
structure is unchanged: terminal alias typed off the last hop's `targetTable`,
passed to a `$fields` overload typed for the return type's table.)

Observed in the wild: `NusGrupperingFagfelt @table("NUSFAGFELT")` reached via
`@reference(path:[{table:"NUSUTDANNINGSGRUPPE"},{table:"NUSFAGGRUPPE"}])` aliases
the terminal table as `Nusfaggruppe` and passes it to
`NusGrupperingFagfelt.$fields(Nusfagfelt, ...)` => "Nusfaggruppe cannot be
converted to Nusfagfelt". The sibling `faggruppe` fields work only because their
path's last table coincides with their target type's table.

Surfaced from a downstream subgraph build (utdanningsregisteret) and confirmed
against the emitter.

### Second symptom: condition-method parameter tables

A path element's `condition` is emitted as a two-argument positional call,
`method(sourceAlias, targetAlias)`, by `JoinPathEmitter.emitTwoArgMethodCall`
(`JoinPathEmitter.java:103`). Two carriers reach that helper:

* a **`ConditionJoin`** (a `condition`-only element, no FK) where the method
  *is* the JOIN's ON clause: `.join(target).on(condition(src, target))`
  (`InlineTableFieldEmitter.java:148-149`);
* an **`FkJoin.whereFilter`** (a `condition` alongside a `{key:}` / `{table:}`
  element) where the FK does the join and the method rides along as a
  `.where(filter(src, target))` on the enclosing SELECT
  (`InlineTableFieldEmitter.java:179`; `JoinStep.java:165-170`).

In both, the emitter passes the source-table alias and the target-table alias
positionally. A condition method may type those parameters as wildcard
`Table<?>` (the idiomatic `TestConditionStub.join(Table<?>, Table<?>)` shape),
in which case the slot accepts any aliased table and nothing can be asserted.
But when the author *concretely* types a parameter, e.g.
`aCondition(NotB src, C tgt)` on a hop whose source is `B`, the emitted
`condition(bAlias, …)` hands a `B`-typed alias into a `NotB`-typed parameter and
javac rejects the generated source. Today nothing checks this: for an
intermediate hop `resolveConditionJoinTarget` reflects only the *second*
parameter (to resolve the target), never the first; for a terminal hop it
ignores the method's parameters entirely (it builds the target from the return
`@table`), so a concretely-mistyped terminal parameter also slips through to
javac. This is the same incompatible-types failure family as the terminal-target
symptom, reached through the condition method's signature instead of the
`$fields` overload.

## Why the checks belong in `parsePath`, reading R232's resolved target

`BuildContext.parsePath` already receives the return type's table as its
`targetSqlTableName` argument (`FieldBuilder.java:687` passes
`returnType.table().tableName()`), and it already resolves every hop's target
table into the model: R232/R129 made `JoinStep.ConditionJoin` carry a
parse-time-resolved `targetTable`, and `FkJoin` / `LiftedHop` carry theirs via
the `WithTarget` capability, all exposed uniformly through
`JoinStep.HasTargetTable.targetTable()`. `parsePath` advances `currentSource`
through exactly this accessor (`BuildContext.java:1160-1161`).

So the terminal-target fact the emitter needs is *already computed* by the time
`parsePath` returns; the bug is that nothing asserts it. The fix reuses that
resolved value rather than re-deriving "what kind of terminal hop is this" from
the raw directive element, which would introduce a fourth predicate over path
data R232 already lifted into the model (a "Generation-thinking" smell: the same
predicate evaluated by multiple consumers means the resolver is under-specified).

The condition-parameter checks belong at the same site for the same reason. By
the time a condition element resolves inside `parsePathElement`, both tables the
emitter will pass are already in hand: the source is `currentSourceSqlName` (the
table entering the hop), and the target is the hop's resolved `targetTable`
(FK-resolved for an `FkJoin`/whereFilter, return-type-resolved or
second-parameter-resolved for a `ConditionJoin`). The reflected `MethodRef`
already carries the parameter type names (`resolveConditionJoinTarget` reads
`params.get(1).typeName()` today at `BuildContext.java:1606`), so the check reads
already-classified data and never re-touches reflection types or the directive
element. It is a new assertion over existing resolved facts, not a re-derivation.

`parsePath` is also the right *site*: it already owns the empty-path FK-inference
fallback (`:1169-1191`) and the condition-terminal target resolution
(`resolveConditionJoinTarget`), it already accumulates author-facing diagnostics
through the `errors` / `ParsedPath.errorMessage()` channel that surfaces upstream
as `Rejection.AuthorError.Structural`, and every output-field caller
(`FieldBuilder.java:687` for `TableBoundReturnType`, `:754` for
`TableInterfaceType`) inherits the checks uniformly. The input-field caller
(`BuildContext.java:1869`) passes `targetSqlTableName = null`; gating the
terminal-target assertion on a non-null target naturally excludes it, and
`@sourceRow` composition (which also passes a null start in its `Path`
derivation) is unaffected. The condition-parameter checks compare a parameter
table to the hop's resolved source/target tables, both of which are non-null
wherever a condition resolves, so they need no such gate.

The intermediate hops are *already* connectivity-validated and must not be
re-checked. `parsePathElement` rejects a `{key:}` whose named FK does not touch
the current source (`BuildContext.java:1416-1422`) and a `{table:}` with no
unique FK from the current source (`:1448-1452`); each step resolves relative to
`currentSource`, which advances through `HasTargetTable.targetTable()`. So the
path is verified link by link as it is built. Re-walking it to re-assert
connectivity would be exactly the "same predicate evaluated by multiple
consumers" smell the *Generation-thinking* principle warns against. This item
adds only what is *not* already checked: the terminal hop's landing table versus
the return table, and condition methods' concretely-typed parameter tables.

## The two checks

### Check 1: the terminal hop lands on the return table

The terminal hop is the last resolved `JoinStep`. The assertion is by permit:

* **Terminal `FkJoin`** (produced by both terminal `{table:X}` and terminal
  `{key:K}` elements; `synthesizeFkJoin` resolves both into an `FkJoin` whose
  `targetTable` is the side the join lands on). The check is
  `terminal.targetTable().tableName()` equals `targetSqlTableName`
  (case-insensitive). This single comparison subsumes the original filing's
  separate `{table:}` and `{key:}` cases, because R232's resolved `targetTable`
  already encodes "where this hop lands" regardless of whether the author named
  the destination table or the FK constant. In the `NUSFAGGRUPPE` example the
  terminal `FkJoin.targetTable` is `NUSFAGGRUPPE`, the return table is
  `NUSFAGFELT`, and the comparison fails. This is the check whose outcome R381
  consumes (see "Relationship to R381"), so it is the one exposed as a typed
  verdict.

* **Terminal `ConditionJoin`** needs no landing-table assertion:
  `resolveConditionJoinTarget`'s terminal branch *constructs* `targetTable` from
  the carrier field's return-type `@table` (`JoinStep.java:204-209`), so
  `targetTable().equals(targetSqlTableName)` is tautologically true. The terminal
  `ConditionJoin`'s *method parameters* are still checked, but by Check 2, not
  here. (Earlier drafts proposed a "source-side" assertion comparing the
  penultimate table to the method's first parameter; that is subsumed by Check 2,
  which validates the first parameter against the source uniformly for every
  condition hop, terminal or not.)

* **Terminal `LiftedHop`** does not arise from `@reference` path parsing
  (single-hop terminal only, produced by the `@sourceRow` leaf-PK arm, which
  passes a null target); the exhaustive switch handles it with a no-op or an
  `IllegalStateException` documenting that it is unreachable here.

### Check 2: condition methods' concrete parameter tables match the aliases passed

Every two-argument condition method the path emits is called positionally as
`method(sourceAlias, targetAlias)`. For each such method the emitter reaches
through `emitTwoArgMethodCall`:

* a **`ConditionJoin.condition()`** — source is the hop's `currentSource`, target
  is the `ConditionJoin.targetTable` (return table for a terminal hop,
  second-parameter-resolved for an intermediate hop);
* an **`FkJoin.whereFilter()`** — source is the FK hop's `originTable`, target is
  the FK hop's `targetTable`, both already resolved by `synthesizeFkJoin`. This
  includes the first-hop parent-correlation `OnConditionJoin` case
  (`InlineTableFieldEmitter.java:171-172`).

The check, applied once per condition method (a single shared helper, e.g.
`validateConditionParamTables(method, sourceSqlName, targetSqlName, …)`):

- For parameter 0 (source) and parameter 1 (target): if the declared type is a
  wildcard (`typeName.contains("<?>")` or equals `org.jooq.Table`, the same
  predicate `resolveConditionJoinTarget` uses at `:1610`), **skip it** — a
  wildcard slot accepts any aliased table and nothing is assertable. This is the
  idiomatic `(Table<?>, Table<?>)` shape and must remain valid.
- Otherwise resolve the parameter's concrete type to a catalog table
  (`Class.forName` + `findTableByClass`, reusing `resolveConditionJoinTarget`'s
  machinery) and compare to the expected table (parameter 0 vs. the source,
  parameter 1 vs. the target). On mismatch, append a diagnostic naming the
  method, the parameter position, the table it declares, and the table it will
  actually receive.

For an intermediate `ConditionJoin`, parameter 1 *defines* the target (the target
is resolved *from* it), so the parameter-1 comparison is self-consistent by
construction and effectively only parameter 0 is a live check there. For a
terminal `ConditionJoin`, parameter 1 is a live check against the return table
(the terminal branch resolves the target from the return `@table` and never reads
the parameter today, so a concretely-mistyped terminal parameter 1 currently
reaches javac). The single helper covers all of these uniformly; no per-permit
special-casing is needed beyond passing the right (source, target) pair.

## Implementation

Flat file-by-file; the change is two validation additions (one post-loop, one
per-condition-element) plus the typed-verdict hook plus tests.

- **Check 2 — condition parameter tables (`BuildContext.parsePathElement`,
  `BuildContext.java:1389`).** Add a shared helper
  `validateConditionParamTables(MethodRef method, String sourceSqlName,
  String targetSqlName, List<String> errors, <site context>)` that, for
  parameter 0 against `sourceSqlName` and parameter 1 against `targetSqlName`,
  skips wildcard `Table<?>` parameters (the `:1610` predicate) and otherwise
  resolves the concrete parameter type via `Class.forName` + `findTableByClass`
  and compares table names case-insensitively, appending a diagnostic on
  mismatch. Call it at each condition resolution site already in
  `parsePathElement`:
  - the `{condition:}`-only branch (`:1472-1488`), after
    `resolveConditionJoinTarget` yields the target: source = `currentSourceSqlName`,
    target = the resolved `ConditionJoin.targetTable`;
  - the `{key:}` whereFilter branch (`:1424-1431`) and the `{table:}` whereFilter
    branch (`:1453-1460`), after `synthesizeFkJoin` yields the `FkJoin`: source =
    `originTable`, target = `targetTable`.
  Because the helper reads the resolved source/target already in scope at each
  site, no second walk and no permit switch are needed. The wildcard skip keeps
  the idiomatic `(Table<?>, Table<?>)` signature valid.
- **Check 1 — terminal hop lands on the return table (`BuildContext.parsePath`).**
  After the element loop and the empty-path inference fallback, before the final
  `return new ParsedPath(...)`, compute the terminal-target verdict in
  `computeTerminalTargetVerdict`, gated on `startSqlTableName != null &&
  targetSqlTableName != null && !resolvedElements.isEmpty()`, switching on the
  terminal `JoinStep` permit:
  - `FkJoin` => `TerminalTargetVerdict.Mismatch` when
    `targetTable().tableName()` differs from `targetSqlTableName`
    (case-insensitive), else `Match`.
  - `ConditionJoin` => `Match` by construction (target is built from the return
    `@table`; its parameters are covered by Check 2).
  - `LiftedHop` => unreachable; document with an `IllegalStateException`.
  When the gate is not met, the verdict is `NotApplicable`. The terminal target is
  reachable as the last element's `targetTable()` (the loop already advances
  `currentSource` through `HasTargetTable.targetTable()`); no second walk.

  **Implementation deviation from the original draft (scope correction).** The
  draft above said to *append the `Mismatch` diagnostic to `errors` inside
  `parsePath`* (self-reject). That rested on a wrong assumption about
  `parsePath`'s caller set: it assumed only the inline/split output callers
  (`TableBoundReturnType`, `TableInterfaceType`), the input-field caller (null
  target), and `@sourceRow` (assumed null *target*). In fact `@sourceRow` passes a
  non-null target (the leaf table) but a null *start*, and `parsePath` is also
  called by `@tableMethod` (FieldBuilder), `@nodeId` references, and
  `RecordTableField` — all passing non-null start + non-null target, several with
  their *own* terminal-target checks and pinned-message tests (e.g. the
  `@tableMethod` "last hop lands on '…'" check). Self-rejecting in `parsePath`
  would preempt those and re-introduce exactly the *Generation-thinking*
  same-predicate-two-consumers smell this item cites. So Check 1 instead:
  computes the verdict and threads it onto `ParsedPath` (the typed hook below)
  *without* forcing it into `errorMessage`, and the two inline output callers in
  `FieldBuilder` (`TableBoundReturnType`, `TableInterfaceType`) — the only emit
  shape carrying the `$fields(terminalAlias)` invariant Check 1 protects — inspect
  `referencePath.terminalTargetVerdict()` and reject `Mismatch` via
  `mismatch.diagnostic()` before constructing the field. The double-gate
  (`startSqlTableName != null` excludes `@sourceRow`; `targetSqlTableName != null`
  excludes input-field) keeps the verdict `NotApplicable` outside the
  inline/split projection. The predicate stays single-sourced; the rejection is
  scoped to where the invariant lives.

  **Follow-up (out of scope here).** `@tableMethod`'s hand-rolled terminal-target
  check (`FieldBuilder`, the "last hop lands on '…'" rejection) is now the lone
  remaining duplicate of the lifted predicate; it is a candidate to consume
  `terminalTargetVerdict()` once its pinned-message test is migrated.
- **Typed verdict hook (`ParsedPath`, `BuildContext.java:974`).** Add a small
  sealed `TerminalTargetVerdict` (`Match` / `Mismatch(String fieldName, String
  terminalTableName, String returnTableName)` / `NotApplicable`) and thread it as
  a third `ParsedPath` component. The `Mismatch` record carries exactly the names
  Check 1's diagnostic prints, and the diagnostic string is *formatted from* the
  record so the two cannot drift. Existing `ParsedPath` construction sites pass
  `Match` / `NotApplicable`. This is the structural hook R381 Slice B consumes
  (see "Relationship to R381"); it scopes to Check 1 only, since that is the
  verdict R381 surfaces. Check 2's condition-parameter mismatches are ordinary
  `errors`-channel structural rejections and need no typed carrier.
- **Diagnostic message shapes.** Check 1: name the field, the terminal hop as the
  author wrote it (the `{table:}` / `{key:}` / `{condition:}` value), the table it
  resolves to, and the return type's `@table`, mirroring R232's actionable tone
  ("the terminal hop resolves to `NUSFAGGRUPPE`, but field `...` returns
  `NusGrupperingFagfelt` whose `@table` is `NUSFAGFELT`; the path must end on
  `NUSFAGFELT`"). Check 2: name the condition method, the parameter position, the
  table the parameter declares, and the table the emitter will pass it ("condition
  method `…` parameter 1 is typed `NotB` but this hop's source table is `B`"). All
  route through `Rejection.AuthorError.Structural` and follow R259's scoping
  conventions.
- No emitter change. `InlineTableFieldEmitter.buildArm` keeps its invariants;
  once the validator rejects the violating shapes, the emitter's
  `terminalAlias` / `$fields` pairing and its `emitTwoArgMethodCall` calls are
  only reached for paths that satisfy them. Optionally add an assertion comment at
  `:91-97` cross-referencing this validation.

## Tests

Pipeline-tier is the primary behavioural tier (per `testing.adoc`); the failure
is a classify-time rejection, so it lands there.

- `ReferencePathTerminalTargetTest` (pipeline-tier) — **Check 1**: an SDL whose
  `@reference` terminal hop lands on the wrong table classifies the field as
  `UnclassifiedField` with a `Rejection.AuthorError.Structural` carrying the
  pointed diagnostic.
  - terminal `{table:X}` with `X` != return `@table` (the `NUSFAGGRUPPE`
    reproduction, mapped onto a sakila fixture: e.g. a field returning a
    `@table("language")` type reached via a path whose last `{table:}` is
    `film`);
  - terminal `{key:K}` where `K` bridges penultimate to a table other than the
    return `@table`;
  - and the mirror happy-path cases for each kind, asserting the field still
    classifies (no false rejections of valid schemas).
- `ReferencePathConditionParamTest` (pipeline-tier) — **Check 2**: an SDL whose
  condition method declares a concretely-typed table parameter that does not
  match the alias it will receive classifies the field as `UnclassifiedField`
  with the pointed diagnostic. Cover both carriers and both parameter positions:
  - a `{condition:}` (ON-clause) hop whose method's parameter 0 is a concrete
    table other than the hop's source (the `aCondition(NotB, …)` reproduction);
  - a *terminal* `{condition:}` hop whose method's parameter 1 is a concrete
    table other than the return `@table`;
  - a `{table:X, condition:F}` whereFilter whose method's parameter is a concrete
    table other than the FK hop's source/target;
  - and the mirror happy-path cases, including the idiomatic
    `(Table<?>, Table<?>)` wildcard signature, asserting it still classifies
    (the wildcard skip is exercised, not just described).
- The intermediate-connectivity rejections are *pre-existing* (`parsePathElement`
  already rejects a disconnected `{key:}` / `{table:}`); add a mid-path-disconnect
  case per kind as a regression fence documenting that the whole path is covered,
  but note in the test that this behaviour is not introduced by this item.
- Assert message content (the table names, parameter position, field name appear)
  rather than exact string equality, consistent with the diagnostic-message tests
  for R236/R259.
- Confirm no behaviour change for valid schemas: the existing sakila `@reference`
  fixtures continue to classify and the compile-spec / execute-spec tiers stay
  green. This item is validate-and-reject only; it never alters a path that
  already resolves correctly.

## Settled design notes

1. **Validate-and-reject only; no auto-append.** The original filing flagged an
   open question: reject only, versus auto-appending an implicit terminal hop
   when a unique FK bridges the last path table to the return table. Settled on
   reject only. Auto-appending at build time is exactly the catalog-guessing the
   `@reference` docs refuse ("the generator does not guess"): the authored SDL
   would no longer contain the hop, and a maintainer reading `path: [...]` would
   find a join in the generated SQL with no source they can point to. The
   convenience of "fill in the missing hop" belongs in the editor as a reviewable
   code action that writes explicit SDL text, which is R381's rung 4, not a
   silent build-time inference here.
2. **Reuse R232, do not re-derive.** Check 1 reads
   `JoinStep.HasTargetTable.targetTable()`; Check 2 reads the condition method's
   reflected `MethodRef` parameter types, both already resolved. Neither
   re-inspects the raw directive element to decide the hop kind, and neither
   re-walks the path to re-assert intermediate connectivity that
   `parsePathElement` already enforces step by step (`:1416-1422`, `:1448-1452`).
   This item adds only assertions over facts already in the model.
3. **Concrete is validated, wildcard is accepted.** A condition method that types
   its table parameters as `Table<?>` stays fully decoupled from Graphitron's
   path: the wildcard slot accepts any aliased table, so there is nothing to
   assert and nothing is asserted. Validation bites only when the author *opts
   into* naming a concrete table for a parameter; then a wrong name is a mistake
   that would otherwise break codegen, caught at build time. The check imposes no
   new signature requirement; it validates a constraint the author chose to
   express. The whereFilter `(src, target)` two-table signature is itself a
   pre-existing contract (`emitTwoArgMethodCall` always emits two arguments); this
   item does not change what condition methods receive, only that concretely-typed
   parameters must agree with it.
4. **Sibling diagnostic items.** R236 (candidate-hint drawn from path-origin
   instead of terminal table) and R282 (global FK candidate hint on the
   `unknownForeignKeyRejection` surface) harden adjacent `@reference` diagnostic
   surfaces. This item is independent of both (it adds a new assertion rather
   than scoping an existing hint), but the diagnostic message should follow the
   same scoping/namespace conventions R259 established so the family reads
   consistently.

## Relationship to R381

R381 (LSP-guided `@reference` path authoring) is the prevention layer to this
item's safety net. R381 rung 3 surfaces *Check 1's terminal-target verdict* as
an edit-time LSP diagnostic, and R381's substrate consumes the verdict as a
typed projection (the R233 pattern: the validator produces the verdict, the LSP
consumes it via the catalog snapshot rather than re-deriving it). The
`TerminalTargetVerdict` threaded onto `ParsedPath` (see Implementation) is that
hook: it exposes Check 1's outcome as a small typed result (`Match` /
`Mismatch(fieldName, terminalTableName, returnTableName)` / `NotApplicable`)
rather than only an error string baked into `ParsedPath.errorMessage()`, so R381
can render it without re-parsing the message. The hook scopes to Check 1 only:
R381 rungs 2-3 are about bridging the resolved penultimate source to the
return-type table, which is exactly Check 1's fact. Check 2's condition-parameter
rejections are not consumed by R381 and stay on the plain `errors` channel.
R379's obligation ends at producing this typed verdict at the `parsePath`
boundary; wiring it through the catalog snapshot is R381's job. The reject path
is unchanged. R379 ships independently and first; R381 Slice B depends on this
hook existing.
