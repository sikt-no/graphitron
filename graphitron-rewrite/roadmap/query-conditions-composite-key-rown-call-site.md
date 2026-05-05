---
id: R79
title: "Generated QueryConditions composite-key call-site emits Row instead of RowN"
status: Backlog
bucket: architecture
priority: 9
theme: nodeid
depends-on: []
---

# Generated QueryConditions composite-key call-site emits Row instead of RowN

A composite-key NodeId argument feeding a generated query-condition method produces Java code that does not compile. The condition method's parameter is declared `org.jooq.RowN` (set in `FieldBuilder.compositeImplicitBodyParam`), and the body uses the `DSL.row(Field<?>[]).eq(arg)` / `.in(rows)` shape so it can stay arity-agnostic. The matching call-site code in `ArgCallEmitter.buildNodeIdDecodeExtraction` projects each decoded record via `((Record) _r).valuesRow()` (scalar arity-N) and `.map(Record::valuesRow)` (list arity-N). Both expressions resolve to `Record.valuesRow()` on the raw `Record` interface, which returns `org.jooq.Row`, not `org.jooq.RowN`. `Row3<...>` (what the typed pattern would yield without the cast) and `RowN` are siblings under `Row`, neither a subtype of the other, so javac rejects the assignment with `Row cannot be converted to RowN` (scalar) and an `inference variable T has incompatible bounds` error on `Collectors.toList()` (list). Reproduced in a real consumer (opptak-subgraph `QueryConditions.java`); the rewrite's own pipeline tests miss this because they assert on emitted strings rather than compiling the output for this specific shape.

Likely fix: build a real `RowN` at the call site instead of leaning on `valuesRow()` -- e.g. `DSL.row(_r.intoArray())` for the scalar arm and `.map(r -> DSL.row(r.intoArray()))` for the list arm. Add a compilation-tier test that exercises a composite-key NodeId argument feeding a `RowN` / `List<RowN>` condition param so the gap that hid this bug is closed; auditing the other end-to-end NodeId arity-N paths for the same call-site / body-param-type contract drift is in scope.
