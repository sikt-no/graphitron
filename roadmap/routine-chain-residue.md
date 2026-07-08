---
id: R448
title: "Routine chains: ordering, binding, and corpus residue"
status: Backlog
bucket: improvement
theme: service
depends-on: []
created: 2026-07-08
last-updated: 2026-07-08
---

# Routine chains: ordering, binding, and corpus residue

Non-gating residue recorded during R435, none of it blocking the shipped surface:

* **Root ordering reconciliation**: a root routine chain carries no ordering surface
  (`QueryRoutineTableField` is not a `SqlGeneratingField`, so the deterministic-order rule
  exempts it), while a child routine list *requires* `@defaultOrder`. An `@defaultOrder`
  surface over the root chain's catalog terminus reconciles the two positions.
* **Correlated value-arg `DataType` binding**: mixed (`Field`-overload) routine calls type
  argument-sourced values by their Java `paramType` read, not a two-arg
  `DSL.val(v, dataType)`; jOOQ's TVF codegen exposes no `Parameter` constants to reference.
  Shares the enum/ID-as-String coercion residue with the R300 root slice. Lift the parameter
  `DataType` onto `RoutineRef.ArgBinding` at the parse boundary when either site needs it.
* **`ClassifiedCorpus` entries** for the chain shapes (the R281 spec-by-example grind): the
  R435 fixtures live in `GraphitronSchemaBuilderTest`'s R435 block; migrating them into the
  corpus retires the ad-hoc block per the classified-corpus loop.
