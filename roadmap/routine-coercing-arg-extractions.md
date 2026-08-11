---
id: R625
title: "Honour coercing argMapping leaf extractions on routine bindings"
status: Backlog
bucket: validation
priority: 3
theme: diagnostics
depends-on: []
created: 2026-08-11
last-updated: 2026-08-11
---

# Honour coercing argMapping leaf extractions on routine bindings

`RoutineDirectiveResolver` hardcodes `new CallSiteExtraction.Direct()` on every argument-sourced binding it mints, and `RoutineCallEmitter.argExpression` never reads `arg.extraction()` at all. So the `extraction` component of a routine binding's `ParamSource.Arg` is a model fact with no consumer: an invariant with no enforcer, and the load-bearing half of the coercion residue that `roadmap/routine-chain-residue.md` records.

`roadmap/unify-argmapping-resolution-seam.md` (R624) makes the routine resolver call `ServiceCatalog.argExtraction`, which is the coercion-aware gate the `@service` side already uses: enum-constant parity through `EnumMappingResolver.checkEnumConstants`, scalars through `WireCoercionResolver.checkScalar` against graphql-java's coercion output type rather than Java identity. That closes the validation hole, but R624 deliberately stops at rejecting a non-`Direct` result as a deferral, because *honouring* one needs emitter work that item does not carry. This item is that work.

## What lands here

* **The `CallSiteExtraction.EnumValueOf` arm on a routine binding.** A GraphQL enum leaf bound to a Java enum routine parameter needs the `valueOf` lift at the call site, the way `ArgCallEmitter` already does it for `@service`.
* **The `CallSiteExtraction.JooqConvert` arm**, same shape.
* **The parameter `DataType` lift onto `RoutineRef.ArgBinding`.** `roadmap/routine-chain-residue.md` describes this directly: jOOQ's table-valued-function codegen exposes no `Parameter` constants, so the correlated (`Field`-overload) call path types an argument-sourced value by its Java `paramType` read rather than a two-arg `DSL.val(value, dataType)`. Both this item and a principled coercion want the `DataType` resolved at the parse boundary and carried on the binding, which is where that residue note says it belongs.

Landing R624 first is what makes this tractable: it gives the check one call site instead of four, and it converts each unhandled combination into a named deferral, so the work here is enumerable from the deferral messages rather than from a survey.

## Open question for whoever picks this up

Whether an enum-typed routine parameter is reachable today through the identity binding path. R624's open questions flag the same thing from the other side: if an existing fixture binds an enum argument to a routine parameter and works, then R624 cannot ship the deferral without regressing it, and the `EnumValueOf` arm has to move into R624 rather than waiting here. Resolve this before sizing either item.

## Cross-references

* `roadmap/unify-argmapping-resolution-seam.md` (R624): lands the classify-time gate and the deferrals this item converts into emitter arms. Prerequisite.
* `roadmap/routine-chain-residue.md` (R448): the coercion residue and the parameter-`DataType` lift.
