---
id: R411
title: "Wire-coercion cast guard for @condition and @externalField (R261 Slice 2)"
status: Backlog
bucket: architecture
priority: 2
theme: structural-refactor
depends-on: [dimensional-model-pivot]
created: 2026-07-01
last-updated: 2026-07-01
---

# Wire-coercion cast guard for @condition and @externalField (R261 Slice 2)

## Problem

R261 Slice 1 landed the wire-coercion cast guard for the three `@service` sites (A: input-bean
scalar field, B: `@service` scalar arg, E: input-bean enum field) plus the shared classify-time
predicate (`WireCoercionResolver`), the `WireCoercionError` sealed family, and the enum-constant
parity home (`EnumMappingResolver.checkEnumConstants`). This item is R261's deferred Slice 2: the
same defect on the two non-`@service` arg-classification sites the Slice 1 spec named but left out:

- **C — `@condition` nested input field.** `ConditionResolver.rewrapForNested` builds a 2-arg
  `NestedInputField` whose leaf is `Direct`; `ArgCallEmitter` then emits `(Long) _m.get("filmId")`
  for a nested `ID`/enum. The `@tableMethod` / `@condition` argument path in
  `ServiceCatalog.reflectServiceMethod` (the slot-types-aware `reflectTableMethod`) also still uses
  `ServiceCatalog.legacyArgExtraction` (no wire-coercion check) — Slice 1 deliberately scoped the
  reject to the `@service` caller only.
- **D — `@externalField` / accessor arg.** `FetcherEmitter` emits a `(ReflectedParamType) env.getArgument(...)`
  cast to the reflected backing-method parameter type.

## Why deferred from R261

Slice 1 rode R256's landed typed-rejection channel (Done). Sites C/D consume the *same* Slice 1
predicate unchanged, but their channel is R222's (`dimensional-model-pivot`) dimensional
`ConditionCall` / `ExternalFieldCall` siblings, which R222 has not yet pinned (R222 is `Spec`, and
its body notes it "added no per-leaf wire-coercion check"). Per the R261 Sequencing section's
reviewer recommendation, C/D were carved here so R261 could close on the `@service` slice alone and
drop `dimensional-model-pivot` from its `depends-on`.

## Constraint

This item must **consume** the Slice 1 predicate (`WireCoercionResolver.checkScalar`) and the
`WireCoercionError.Assignability` / `EnumConstantDivergence` arms with **no re-derivation** — the
"one predicate, one home" rule. It only threads the predicate through R222's channel for the
`legacyArgExtraction` path (retire that shim) and the `@externalField` accessor-arg site, and adds
pipeline-tier tests mirroring `WireCoercionCastGuardPipelineTest` for the two new sites.
