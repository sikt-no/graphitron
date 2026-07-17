---
id: R500
title: "Aliased duplicate reference selections mint duplicate SQL aliases ($fields projects by field name, not result key)"
status: Backlog
bucket: bug
priority: 4
theme: codegen-correctness
depends-on: []
created: 2026-07-17
last-updated: 2026-07-17
---

# Aliased duplicate reference selections mint duplicate SQL aliases ($fields projects by field name, not result key)

Selecting the same reference field twice under different aliases with divergent sub-selections (`a: ref { x } b: ref { y }`) produces two entries in `getFieldsGroupedByResultKey()` whose `SelectedField.getName()` is identical, so the generated `$fields` switch (`TypeClassGenerator.emitSelectionSwitch`) fires the same inline arm for both entries and projects two `DSL.multiset(...).as("<fieldName>")` terms with the same SQL alias; the per-field readers in `FetcherEmitter` also read by field name, so even absent the SQL alias collision the two aliases could not be told apart on the read side. The failure is loud today (duplicate-alias jOOQ error, not silent-wrong data), which is why this is filed separately rather than folded into R499: R499's occurrence merge keys on name+arguments *within* one result-key bucket, while this defect spans *distinct* result-key buckets and needs projections aliased by result key (`entry.getKey()`) plus result-key-aware readers (e.g. reading the column by `env.getField().getResultKey()` instead of the schema field name). The two fixes are orthogonal; this one is lower priority because no silent data corruption occurs. Surfaced during the R499 Spec trace.
