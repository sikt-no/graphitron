---
id: R625
title: "Gate argMapping leaf types against their bound parameter"
status: Backlog
bucket: validation
priority: 3
theme: diagnostics
depends-on: []
created: 2026-08-11
last-updated: 2026-08-11
---

# Gate argMapping leaf types against their bound parameter

An `argMapping` entry's resolved leaf carries a GraphQL type, and the target it binds to carries a Java type, and nothing anywhere compares them. `@routine(argMapping: "pBrukernavn: input")` binds a whole input object to a `String` IN parameter, passes every validation, and emits `env.<String>getArgument("input")`, which throws `ClassCastException` on a `LinkedHashMap` at request time. The mirror surface already gates: `RoutineDirectiveResolver.bindArgs` compares a `columnMapping` column's `columnClass()` against the parameter type and rejects a mismatch, on the stated grounds that the mismatch "would be a javac error in the generated source". The argument-sourced side has no counterpart, so the same class of authoring error lands as a runtime failure instead of a build error, which inverts the project's normal bias.

The gate is not a one-line type equality check, which is why this is its own item rather than a slice of `roadmap/unify-argmapping-resolution-seam.md`:

* `roadmap/routine-chain-residue.md` records live enum and ID-as-String coercion residue: a GraphQL enum leaf legitimately binds to a `String` parameter, and an `ID` leaf legitimately binds to a `String`. A naive `equals` on type names rejects working schemas.
* The same item notes that jOOQ's table-valued-function codegen exposes no `Parameter` constants, so the routine side has no `DataType` to compare against and reads the Java `paramType` instead. Its suggestion, lifting the parameter `DataType` onto `RoutineRef.ArgBinding` at the parse boundary, is probably the prerequisite for a principled gate here.
* The check wants to be shared across directives for the same reason resolution does: `@service`, `@condition` and `@routine` all bind GraphQL leaves to typed targets. But the target types come from bytecode reflection on one side and the catalog on the other, so the comparison needs a common currency before it can be written once.

Scoping question for whoever picks this up: whether the gate is a rejection or a deferral for the coercion cases it cannot yet decide. A rejection that fires on a legitimate enum-to-String binding is worse than the current hole; a deferral that names the unproven pairing may be the honest first shape.

## Cross-references

* `roadmap/unify-argmapping-resolution-seam.md` (R624): unifies the resolution seam and explicitly leaves this hole at its current width. Landing that first gives this item one place to add the check instead of four.
* `roadmap/routine-chain-residue.md` (R448): the coercion residue and the parameter-`DataType` lift this item likely depends on.
