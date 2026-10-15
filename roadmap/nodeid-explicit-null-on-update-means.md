---
id: R1042
title: "What an explicit null on a @nodeId UPDATE input means, and whether it agrees with the INSERT rule"
status: Backlog
bucket: bug
priority: 4
theme: mutation-write
depends-on: []
created: 2026-10-15
last-updated: 2026-10-15
---

# What an explicit null on a @nodeId UPDATE input means, and whether it agrees with the INSERT rule

## Goal

An author of a `@mutation(typeName: UPDATE)` input with a nullable `@nodeId` field can say what an explicit `null` on it does, and the answer agrees with what the same `null` does on an INSERT. R880 settled one answer on the UPDATE rail: a nullable reference that writes no matched-key column *clears*, writing `NULL` to every column it contributes to SET (`CarrierNullRule.OnExplicitNull.Clears`). Two things now push against that answer, and this item exists to decide them together rather than leave either as an accident. First, sis's Graphitron 9 tests expect an explicit null on a `@nodeId` UPDATE field to be *ignored*, as if omitted: `endreVeiledningsforhold` sends `null` for `veilederkategoriId` / `internstatusId`, whose columns are NOT NULL foreign keys, and today the statement fails in Oracle with a `DataAccessException`. Second, R1039 makes an explicit null on an INSERT `@nodeId` contribute nothing to a column it shares with another writer, while R880's single-row and bulk UPDATE SET agreement (`appendAgreementValue` with `nullAgrees`) still makes a clearing writer conflict with a co-writer that sets the same SET column. A settled answer lets the UPDATE emitters and the user manual state one rule for what a `null` on a reference means across write rails.

## Context

- Filed from R1039's Spec, which kept INSERT-family rails in scope and moved the UPDATE contract here because changing it retracts a reviewed R880 decision rather than fixing an emitter.
- Candidate answers for the first question: keep *clear* (sis changes its schema or its tests, and R881 improves the error a NOT NULL column produces), adopt *leave unchanged* (an UPDATE can then never clear a reference, which R880 built on purpose), or make it an author choice per field. Column nullability is R881's axis; whichever answer wins should not quietly read it.
- For the second question, R1039's principle is that a `@nodeId` names a reference, not a column, so the absence of a reference says nothing about a column another reference also writes. Applying it here flips `nullAgrees` for `Clears` writers and gates the cleared writer's `NULL` on shared SET columns, across the direct and payload UPDATE arms.
