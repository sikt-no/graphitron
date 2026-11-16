---
id: R522
title: "Reconcile the emitted-code seam-pin assertion convention with the testing doc"
status: Backlog
bucket: tech-debt
priority: 2
theme: dev-loop
depends-on: []
created: 2026-07-24
last-updated: 2026-07-24
---

# Reconcile the emitted-code seam-pin assertion convention with the testing doc

The development principles ban code-string assertions on generated method bodies, yet the accepted emission-pin convention (R45's `TenantRoutedFetcherPipelineTest`, `TenantRuntimeKeyTypeTest`, R46's `TenantFanOutFetcherPipelineTest`) asserts `TypeSpec.toString()` fragments that include call-site shapes inside method bodies, on the argument that the pinned strings are the *seams* (carrier statics, factory keys, routing calls) rather than incidental body text. The R46 Done-gate review flagged the tension: either the testing doc gains a paragraph legitimising seam pins and drawing the line against incidental-body pins (and the existing tests get audited against that line), or the pins migrate to structural `TypeSpec` assertions. Decide once, write it down, and align the three test classes; today each new reviewer re-litigates the convention.

A fourth to align: `GeneratedSourcesLintTest.emittedLoadersAreBuiltWithTheRequestsOptions` (graphitron-sakila-example) reads every generated `newDataLoader(` / `newMappedDataLoader(` call and requires it to end in `ErrorRouter.loaderOptions(<env>)`. It sits in the lint family, which the testing doc sanctions for generator hygiene, but what it pins is a call-site seam inside method bodies, so the line this item draws decides whether it stays a lint.
