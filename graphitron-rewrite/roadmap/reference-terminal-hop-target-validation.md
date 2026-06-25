---
id: R379
title: "Validate @reference terminal hop resolves to the field return type table"
status: Spec
bucket: bug
priority: 5
theme: structural-refactor
depends-on: []
created: 2026-06-25
last-updated: 2026-06-25
---

# Validate @reference terminal hop resolves to the field return type table

> An `@reference` path whose terminal hop lands on a table other than the field
> return type's `@table` compiles to generated Java that javac rejects with an
> incompatible-types error. Move the failure to build time: after `parsePath`
> resolves the hops, assert the terminal hop's already-resolved target table
> matches the return type's table, and reject with a pointed Graphitron
> diagnostic. The check reads R232's resolved `JoinStep.HasTargetTable.targetTable()`;
> it does not re-derive the hop kind from the directive element.

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

## Why the check belongs in `parsePath`, reading R232's resolved target

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

`parsePath` is also the right *site*: it already owns the empty-path FK-inference
fallback (`:1169-1191`) and the condition-terminal target resolution
(`resolveConditionJoinTarget`), it already accumulates author-facing diagnostics
through the `errors` / `ParsedPath.errorMessage()` channel that surfaces upstream
as `Rejection.AuthorError.Structural`, and every output-field caller
(`FieldBuilder.java:687` for `TableBoundReturnType`, `:754` for
`TableInterfaceType`) inherits the check uniformly. The input-field caller
(`BuildContext.java:1869`) passes `targetSqlTableName = null`; gating the check
on a non-null target naturally excludes it, and `@sourceRow` composition (which
also passes a null start in its `Path` derivation) is unaffected.

## The check is per terminal-hop kind

The terminal hop is the last resolved `JoinStep`. The meaningful assertion
differs by permit:

* **Terminal `FkJoin`** (produced by both terminal `{table:X}` and terminal
  `{key:K}` elements; `synthesizeFkJoin` resolves both into an `FkJoin` whose
  `targetTable` is the side the join lands on). The check is
  `terminal.targetTable().tableName()` equals `targetSqlTableName`
  (case-insensitive). This single comparison subsumes the original filing's
  separate `{table:}` and `{key:}` cases, because R232's resolved `targetTable`
  already encodes "where this hop lands" regardless of whether the author named
  the destination table or the FK constant. In the `NUSFAGGRUPPE` example the
  terminal `FkJoin.targetTable` is `NUSFAGGRUPPE`, the return table is
  `NUSFAGFELT`, and the comparison fails.

* **Terminal `ConditionJoin`**. `resolveConditionJoinTarget`'s terminal branch
  *constructs* `targetTable` from the carrier field's return-type `@table`
  (`JoinStep.java:204-209`), so `targetTable().equals(targetSqlTableName)` is
  tautologically true and proves nothing. The meaningful check moves to the
  *source* side: the path's penultimate resolved table (`currentSource` entering
  the terminal hop) must be the input the condition method expects to receive.
  Validate that the path delivered the condition its input, not that the path
  "ends on" the target. The condition method's *first* parameter names that
  expected input; `resolveConditionJoinTarget` already reflects on the *second*
  parameter for intermediate hops, so the reflection machinery to read the first
  parameter is adjacent, not new. When the penultimate table does not match the
  method's first-parameter table, reject with a diagnostic naming both.

* **Terminal `LiftedHop`** does not arise from `@reference` path parsing
  (single-hop terminal only, produced by the `@sourceRow` leaf-PK arm, which
  passes a null target); the exhaustive switch handles it with a no-op or an
  `IllegalStateException` documenting that it is unreachable here.

## Implementation

Flat file-by-file; the whole change is one new validation block plus its tests.

- `BuildContext.parsePath` (`BuildContext.java:1131`): after the element loop
  and the empty-path inference fallback, before `return new
  ParsedPath(List.copyOf(resolvedElements), null)`, add a terminal-target
  assertion gated on `targetSqlTableName != null && !resolvedElements.isEmpty()`.
  Switch on the terminal `JoinStep` permit:
  - `FkJoin` => compare `targetTable().tableName()` to `targetSqlTableName`
    (case-insensitive); on mismatch append a diagnostic to `errors` and fall
    through to the existing `if (!errors.isEmpty())` return.
  - `ConditionJoin` => resolve the condition method's first-parameter table and
    compare it to the penultimate `currentSource`; on mismatch append a
    diagnostic. (Track the penultimate source alongside the loop's
    `currentSource` advance, or recompute from `resolvedElements` of size n-1.)
  - `LiftedHop` => unreachable; document.
  Because the loop already mutates `currentSource` through
  `HasTargetTable.targetTable()`, the terminal target is reachable as the last
  element's `targetTable()` without a second walk.
- Diagnostic message shape: name the field, the terminal hop as the author wrote
  it (the `{table:}` / `{key:}` / `{condition:}` value), the table it actually
  resolves to, and the return type's `@table`. Mirror the actionable tone R232
  established for condition-first paths ("the terminal hop resolves to
  `NUSFAGGRUPPE`, but field `...` returns `NusGrupperingFagfelt` whose `@table`
  is `NUSFAGFELT`; the path must end on `NUSFAGFELT`"). The message is the
  primary user-facing artifact of this item.
- No emitter change. `InlineTableFieldEmitter.buildArm` keeps its invariant; once
  the validator rejects the violating shape, the emitter's `terminalAlias` /
  `$fields` pairing is only ever reached for paths that satisfy it. Optionally
  add an assertion comment at `:91-97` cross-referencing this validation so a
  future reader knows where the invariant is enforced.

## Tests

Pipeline-tier is the primary behavioural tier (per `testing.adoc`); the failure
is a classify-time rejection, so it lands there.

- `ReferencePathTerminalTargetTest` (pipeline-tier): an SDL whose `@reference`
  terminal hop lands on the wrong table classifies the field as
  `UnclassifiedField` with a `Rejection.AuthorError.Structural` carrying the
  pointed diagnostic. One case per terminal-hop kind:
  - terminal `{table:X}` with `X` != return `@table` (the `NUSFAGGRUPPE`
    reproduction, mapped onto a sakila fixture: e.g. a field returning a
    `@table("language")` type reached via a path whose last `{table:}` is
    `film`);
  - terminal `{key:K}` where `K` bridges penultimate to a table other than the
    return `@table`;
  - terminal `{condition:C}` where the penultimate table is not the condition
    method's first-parameter input (source-side rejection);
  - and the mirror happy-path cases for each kind, asserting the field still
    classifies (no false rejections of valid schemas).
- Assert message content (the table names and field name appear) rather than
  exact string equality, consistent with the diagnostic-message tests for
  R236/R259.
- Confirm no behaviour change for valid schemas: the existing sakila
  `@reference` fixtures continue to classify and the compile-spec / execute-spec
  tiers stay green. This item is validate-and-reject only; it never alters a
  path that already resolves correctly.

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
2. **Reuse R232, do not re-derive.** The per-hop-kind branch reads
   `JoinStep.HasTargetTable.targetTable()` and (for `ConditionJoin`) the
   condition method's parameter tables, both already resolved. It must not
   re-inspect the raw directive element to decide the hop kind; that would
   parallel R232's parse-time resolution and create a second predicate that can
   drift from it.
3. **Sibling diagnostic items.** R236 (candidate-hint drawn from path-origin
   instead of terminal table) and R282 (global FK candidate hint on the
   `unknownForeignKeyRejection` surface) harden adjacent `@reference` diagnostic
   surfaces. This item is independent of both (it adds a new assertion rather
   than scoping an existing hint), but the diagnostic message should follow the
   same scoping/namespace conventions R259 established so the family reads
   consistently.

## Relationship to R381

R381 (LSP-guided `@reference` path authoring) is the prevention layer to this
item's safety net. R381 rung 3 surfaces *this same terminal-target verdict* as
an edit-time LSP diagnostic, and R381's substrate consumes the verdict as a
typed projection (the R233 pattern: the validator produces the verdict, the LSP
consumes it via the catalog snapshot rather than re-deriving it). To make that
sharing clean, the terminal-target check here should expose its outcome as a
small typed result (resolved / mismatch-with-table-names) rather than only an
error string baked into `ParsedPath.errorMessage()`, so R381 can render it
without re-parsing the message. This is a structural hook for R381, not extra
behaviour in R379; the reject path is unchanged. R379 ships independently and
first; R381 Slice B depends on this hook existing.
