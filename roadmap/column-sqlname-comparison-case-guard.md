---
id: R359
title: "Guard ColumnRef.sqlName() comparisons against case-sensitivity drift"
status: Backlog
bucket: cleanup
priority: 5
theme: model-cleanup
depends-on: []
created: 2026-06-23
last-updated: 2026-06-23
---

# Guard ColumnRef.sqlName() comparisons against case-sensitivity drift

The column-identity sibling of R358, filed by R358 so the defect does not fall into a blind spot silently. `ColumnRef.sqlName()` is, like `TableRef.tableName()`, a case-preserved verbatim identity string, while the jOOQ catalog's `findColumn` lookup (`JooqCatalog.java:813`) is the already-case-insensitive layer (the column analogue of `findTable`). So the same structural drift exists: one live case-sensitive `.sqlName().equals(` (`GraphitronSchemaValidator.java:883`, `!rcf.column().sqlName().equals(ocf.column().sqlName())`) sits alongside six `equalsIgnoreCase` comparison sites (`FieldBuilder.java`, `TypeBuilder.java`, `BuildContext.java`, `NodeIdLeafResolver.java` ×3). R358 scoped itself to `tableName` and explicitly excluded this; the proportionate fix mirrors R358's Phase 2: a `sameColumn(...)`-style predicate on `ColumnRef` plus a guard scan, or fold both identity strings into one canonical-identity pass (R358 "Alternatives considered"). Per-site reachability: `:883`'s operands may, like R358's `:3105`, be non-divergent (it compares two `ColumnRef`s that may share provenance); this item makes that per-site call rather than presuming the defect is live.
