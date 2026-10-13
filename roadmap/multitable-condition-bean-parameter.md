---
id: R1022
title: "A bean-typed @condition parameter on an interface or union field is built, not refused"
status: Backlog
bucket: feature
priority: 7
theme: codegen-correctness
depends-on: []
created: 2026-10-13
last-updated: 2026-10-13
---

# A bean-typed @condition parameter on an interface or union field is built, not refused

## Goal

A `@condition` parameter typed as a Java bean (a record, or a class with a public no-arg constructor and setters) on a field that returns an interface or union is built from the delivered input-object `Map`, exactly as it already is on a single-table field. Today such a field fails the build: the multitable branch-safety gate (`FieldBuilder.isBranchSafeExtraction`) refuses `CallSiteExtraction.InputBean`, so an author whose condition wants a typed value has to fall back to `Map<String, Object>` on precisely the fields where the per-branch conditions are hardest to read. The user manual (`reference/directives/condition.adoc`, Input-object parameters) states the refusal as a limit. The branch path calls the same `<Parent>Conditions` glue methods, and those host the `create<Bean>` builders through `InputBeanHelperRegistry`, so admitting the arm may need little more than flipping the gate. What is missing is evidence: a pipeline case showing the bean built on a multitable field, an execution case on an interface or union field filtering by a bean member, and the limit removed from the manual. Until that work starts, the refusal is the correct behaviour and is a clean build error. Surfaced at the In Review -> Done review of the item that introduced bean-typed condition parameters.
