---
id: R1080
title: "A nullable root field returning one object over a row of nulls resolves to null, as v9 resolved it"
status: Backlog
bucket: bug
priority: 6
theme: codegen-correctness
depends-on: [table-child-with-all-null-columns-reads-as-null]
created: 2026-10-20
last-updated: 2026-10-20
---

# A nullable root field returning one object over a row of nulls resolves to null, as v9 resolved it

## Goal

A nullable root field returning one `@table` object (`QueryField.QueryTableField` with a single
wrapper) resolves to `null` when the row it finds holds nothing by R1069's presence rule, as
Graphitron 9 resolved it: its `FetchDBMethodGenerator.generateSelectRow` wrapped the root object's
row in the same `Functions.nullOnAllNull` it used for children. Today a root read that finds a row
whose mapped columns are all `null` returns an object of nulls. R1069 settles the rule for child
fields and lists this as a divergence in the migration guide; this item extends it to the root
launcher (`RootLauncherRenderer`'s `fetchOne()` arms) once R1069's per-type presence fold exists, and
removes that divergence. No consumer has reported the root shape yet; the item is filed so the
divergence has a home.
