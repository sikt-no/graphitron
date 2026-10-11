---
id: R1011
title: "An input class is emitted for every reachable input type, though only a validated field reads one"
status: Backlog
bucket: cleanup
theme: codegen-correctness
depends-on: []
created: 2026-10-03
last-updated: 2026-10-03
---

# An input class is emitted for every reachable input type, though only a validated field reads one

## Goal

Graphitron emits a class into `<outputPackage>.inputs` for every input type a field argument reaches, transitively, and the only reader of any of them is the validator pre-step, which materialises one through `fromMap` when a validated field takes an input-typed argument. In the sakila example that is 101 classes and no reader at all: the one validated field, `Query.filmLookupValidated`, takes a scalar, and every condition, DML statement and service binding reads the raw argument map instead. When this lands, an input class is emitted only where a validated field's pre-step reads it, so a consumer's generated sources hold no class nothing calls. Found while cataloguing the generated code's patterns for the model of what the generator reads; `InputRecordGenerator` states the reachable-closure rule this narrows.
