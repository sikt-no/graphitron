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
their factories already exist for:

1. **`BuildContext.ParsedPath.errorMessage: String`** — accumulated by `parsePathElement`'s
   `List<String> errors`, surfaces at every `parsePath().hasError()` consumer in `FieldBuilder`,
   `BuildContext`, `NodeIdLeafResolver`, `TypeBuilder`. Widening to `Rejection rejection` unlocks
   `BuildContext:584` (FK SQL name → `unknownForeignKey`).

2. **`InputFieldResolution.Unresolved.reason: String`** — built by `BuildContext.classifyInputField`
   and consumed by `BuildContext` (typename inference, nested field aggregation) and
   `InputFieldResolver`. Widening unlocks `BuildContext:877` (typename in `@nodeId` →
   `unknownTypeName`) and `BuildContext:1013` (column in path leg → `unknownColumn`).

3. **`ArgumentRef.ScalarArg.UnboundArg.reason: String`** — single producer site
   (`FieldBuilder:853`, column on filter table). Already called out as out of scope in R58 Phase D.
   Widening unlocks the `unknownColumn` migration.

4. **`EnumMappingResolver.EnumValidation.Mismatch`** — joins multiple per-constant misses into a
   single prose blob via `String.join("; ", mismatches)`. Migrating to `unknownEnumConstant` per
   miss requires widening the carrier to a `List<Rejection>` (or splitting the carrier shape so
   the per-miss typed value reaches a consumer). The factory `Rejection.unknownEnumConstant` is
   already in place from R58 Phase D.

5. **`TypeBuilder` error lists** (`TypeBuilder:403` keyColumnErrors, `TypeBuilder:704` failures-list
   aggregation) — same multi-miss aggregation pattern as `EnumMappingResolver`. Migrating
   `TypeBuilder:403` to `unknownNodeIdKeyColumn` and `TypeBuilder:704` to `unknownColumn` requires
   the same shape-widening.

The factories (`unknownForeignKey`, `unknownTypeName`, `unknownEnumConstant`,
`unknownNodeIdKeyColumn`, `unknownColumn`) are already in place from R58 Phase D; what this plan
adds is the carrier widenings so the typed values reach consumers. Each of (1)-(3) is independently
shippable; (4) and (5) need a design decision on the multi-miss carrier shape (single rejection
with `List<Rejection>` payload vs. lifting the carrier to emit one `ValidationError` per miss)
before producer migration.
