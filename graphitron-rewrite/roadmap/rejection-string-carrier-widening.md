---
id: R66
title: "Widen string-carrier intermediates onto Rejection (R58 follow-up)"
status: Backlog
bucket: architecture
priority: 6
theme: structural-refactor
depends-on: []
---

# Widen string-carrier intermediates onto Rejection (R58 follow-up)

R58 lifted the direct candidate-hint producers onto typed
[`Rejection.AuthorError.UnknownName`](../graphitron/src/main/java/no/sikt/graphitron/rewrite/model/Rejection.java)
factories. Five intermediate carriers still flatten the typed shape into prose before it reaches
a `Rejection` consumer, blocking five candidate-hint producers from reaching the typed surface
their factories (`unknownForeignKey`, `unknownTypeName`, `unknownEnumConstant`,
`unknownNodeIdKeyColumn`, `unknownColumn`) already exist for. R58 Phase D shipped the factories;
this plan adds the carrier widenings so the typed values reach consumers.

> R58 is currently *In Review*. If R58 reverts, R66 Phase A reverts with it (the producer-side
> factories disappear). The dependency is captured in prose rather than `depends-on:` because
> the factories are merged on the working branch.

## Carrier audit

1. **`BuildContext.ParsedPath.errorMessage: String`** — populated by `parsePathElement`'s
   `List<String> errors` accumulator (`BuildContext.java:388`); surfaces at every
   `parsePath().hasError()` consumer in `FieldBuilder`, `BuildContext`, `NodeIdLeafResolver`,
   `TypeBuilder`. Widening to a single `rejection: Rejection` unlocks the producer at
   `BuildContext.java:583-584` (FK SQL name miss → `unknownForeignKey`).

2. **`InputFieldResolution.Unresolved.reason: String`** — built by
   `BuildContext.classifyInputField` and consumed by `BuildContext` (typename inference, nested
   field aggregation) and `InputFieldResolver`. Widening to `rejection: Rejection` unlocks
   `BuildContext.java:875-878` (typename in `@nodeId` → `unknownTypeName`) and
   `BuildContext.java:1015-1016` (column-in-path-leg → `unknownColumn`).

3. **`ArgumentRef.ScalarArg.UnboundArg.reason: String`** — single producer at
   `FieldBuilder.java:849-852` (column on filter table). Called out as out of scope in R58 Phase
   D. Widening to `rejection: Rejection` unlocks the `unknownColumn` migration.

4. **`EnumMappingResolver.EnumValidation.Mismatch(Rejection rejection)`** — the carrier already
   holds a `Rejection`, but the producer at `EnumMappingResolver.java:159-162` joins per-constant
   misses into a single prose blob via `String.join("; ", mismatches)` and wraps in
   `Rejection.structural(...)`. Surfacing each miss as `unknownEnumConstant` with its own
   candidate set requires widening the component to `List<Rejection> rejections`. The
   "GraphQL type is not an enum" branch at `:145-146` is single-rejection; under the widened
   shape it becomes `List.of(Rejection.structural(...))`.

5. **`TypeBuilder` aggregations** — same multi-miss pattern as (4):
   - `TypeBuilder.java:397-409` (`keyColumnErrors: List<String>`) joins to one
     `Rejection.structural(...)`. Widen the accumulator to `List<Rejection>` and migrate per
     miss to `unknownNodeIdKeyColumn`.
   - `TypeBuilder.java:686-708` (`failures: List<InputFieldResolution.Unresolved>`) joins
     `Unresolved.reason` strings. After Phase A2 each `Unresolved` carries a `Rejection`; this
     site fans those out per miss onto `unknownColumn`.

## Phases

- **Phase A — single-Rejection carrier widenings (items 1-3).** Independent; each independently
  shippable.
  - **A1 (item 1)**: widen `ParsedPath.errorMessage: String` → `rejection: Rejection`. Update
    `parsePathElement`'s accumulator from `List<String>` to `List<Rejection>` and join into a
    single `Rejection` at `parsePath` exit (one rejection per path-parse failure preserves the
    current per-call-site error semantic). Migrate the FK-name producer at `:583-584` onto
    `unknownForeignKey`. Other producers in `parsePathElement` construct
    `Rejection.structural(reason)` to preserve current prose at the byte boundary.
  - **A2 (item 2)**: widen `InputFieldResolution.Unresolved.reason: String` → `rejection:
    Rejection`. Migrate `:875-878` onto `unknownTypeName` and `:1015-1016` onto `unknownColumn`.
    Other `Unresolved` construction sites wrap in `Rejection.structural(reason)`. Required
    before B2's second site (`TypeBuilder.java:686-708`) so `Unresolved` already carries the
    typed shape its consumer can fan out.
  - **A3 (item 3)**: widen `ArgumentRef.ScalarArg.UnboundArg.reason: String` → `rejection:
    Rejection`. Migrate `:849-852` onto `unknownColumn`.

- **Phase B — multi-miss carrier widenings (items 4-5).** Decided shape: **`List<Rejection>` at
  the aggregating carrier; consumer fans out to N `UnclassifiedField`/`UnclassifiedType` (and
  therefore N `ValidationError`s).** Rationale: keeps R58's single-rejection-per-`Unclassified*`
  / `ValidationError` invariant intact; each typed miss surfaces as its own validator entry,
  which is more useful for IDE/LSP consumers than a joined prose blob. Considered alternatives:
  a `Rejection.Multi(List<Rejection>)` seal arm (rejected: complicates the seal and forces
  every `Rejection` consumer to handle nesting); widening `ValidationError` itself (rejected:
  R58 Phase I just locked the single-rejection shape).
  - **B1 (item 4)**: widen `EnumValidation.Mismatch(Rejection)` →
    `Mismatch(List<Rejection>)`. Producer at `:159-162` emits one `unknownEnumConstant` per
    missing constant. Branch at `:145-146` becomes a singleton list. Consumer in `FieldBuilder`
    (column-bound enum filter classification) fans out to one `UnclassifiedField` per typed
    rejection.
  - **B2 (item 5)**: same widening on `TypeBuilder.keyColumnErrors` and the `failures`-list
    rendering at `:686-708`. Producers emit one `UnclassifiedType` per typed miss instead of
    one type-level `Rejection.structural(joined)`. Author-side UX shift: a multi-miss `@node`
    type now produces N validator entries (one per unresolved key column) instead of one entry
    with semicolon-joined prose. Documented in the changelog as an intentional trade for typed
    candidate hints.

## Tests

- Pipeline (extending `R58TypedRejectionPipelineTest`):
  - One case per Phase A unlock point (`unknownForeignKey` via path-parse, `unknownTypeName`
    via `@nodeId`, `unknownColumn` via path leg, `unknownColumn` via filter-table column).
  - Phase B fan-out: a 3-miss enum mapping produces 3 `ValidationError`s, each carrying
    `Rejection.AuthorError.UnknownName` with `attemptKind = ENUM_CONSTANT` and its own
    candidate list.
  - Phase B fan-out: a 2-miss `@node(keyColumns:)` produces 2 `ValidationError`s, each carrying
    `unknownNodeIdKeyColumn`.
- Model (extending `RejectionRenderingTest`): `EnumValidation.Mismatch.message()` joins the
  list via `; ` so the byte-stable log surface validator consumers depend on is preserved when
  a caller chooses to render the multi-miss carrier as a single line (e.g. log emission).

## Out of scope

- Threading nested rejection chains as a typed `Rejection.NestedReject` arm. Same deferral as
  R58 — not on any in-flight consumer's path.
- LSP fix-its consuming the typed `UnknownName.candidates` lists this plan unlocks. R18 wires
  the consumer; this plan supplies the typed shape.
- Lifting `ArgumentRef.UnclassifiedArg.reason` onto `Rejection`. Separate axis (argument
  classification, not field classification); same shape-pattern but different producer set.
- `BuildWarning.message`. Single producer site; premature.
- Renaming `RejectionKind`. Same deferral as R58.
