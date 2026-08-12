---
id: R636
title: "The generated schema reference tells the truth about view columns"
status: Backlog
bucket: cleanup
priority: 5
theme: docs
depends-on: []
created: 2026-08-12
last-updated: 2026-08-12
---

# The generated schema reference tells the truth about view columns

Two small fidelity gaps in the generated schema reference, both about what the renderer says versus
what the object is. First, H2 reports every view column as nullable, so the rendered reference tells
a reader that `meta_family.prefix` is nullable one line after the prose calls it "the roster's key,
unique by gate"; the same reads wrong across every `intent_` view and the `diagnostic` surface. A
view column's nullability is not a fact the engine knows, so the honest render is to omit the clause
for views rather than print an engine artefact as a constraint. Second,
`SchemaReferencePages.renderRelation` gates the whole key/foreign-key/check line behind a non-empty
primary key (`SchemaReferencePages.java:203`). Today that loses nothing: all 112 base tables declare
a primary key. But nothing gates that invariant, and `FactSchemaGateTest`'s partition gate reads the
primary-key map, so a primary-key-less base table would pass it vacuously and then silently drop its
foreign keys and check constraints from the reference. Either decouple the constraint render from the
primary key, or add the primary-key totality gate the renderer is quietly assuming.
