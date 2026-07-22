---
id: R512
title: "Schema-qualify @reference(key:) to escape cross-schema FK-name collisions"
status: Spec
bucket: feature
priority: 3
theme: codegen-correctness
depends-on: []
created: 2026-07-22
last-updated: 2026-07-22
---

# Schema-qualify @reference(key:) to escape cross-schema FK-name collisions

A `@reference(path: [{key: "<fk>"}])` foreign-key name is resolved by `JooqCatalog.findForeignKey(String name, String sourceSqlName)` (`JooqCatalog.java`) across every schema in one flat namespace: first the SQL constraint name (`fk.getName().equalsIgnoreCase(name)`), then, only on a miss, the jOOQ `TABLE__CONSTRAINT` Java-constant form via each schema's generated `Keys` class. The method takes a nullable `sourceSqlName` and, when that resolves to a single catalog table, scopes candidates to the FKs touching it (identity-based). When the same constraint name exists in two or more schemas and source scoping cannot break the tie (the source is `null`, non-table-backed, or itself resolves ambiguously), the lookup returns `ForeignKeyLookup.Ambiguous` and the author sees the "ambiguous" rejection from `BuildContext.ambiguousForeignKeyRejection`.

Unlike `@table(name:)`, which accepts a schema-qualified `schema.table` form (`JooqCatalog.parseQualifiedTableName`, the lever the large audited schema-qualified-`@table` bug class settled on), the `key:` grammar has **no** schema-qualified form. So a cross-schema constraint-name collision is not merely ambiguous, it is unfixable by the author: there is no lever to disambiguate. This bites on real multi-schema databases (e.g. Samordna opptak) whose generated schemas carry colliding constraint names; the reactor's own multi-schema fixture already reproduces it (`multischema_a.note` and `multischema_b.note` both hold an FK literally named `note_event_fk`).

The fix is to let a `key:` value carry a leading `schema.` qualifier (`key: "multischema_a.note_event_fk"`) that scopes the FK candidate set to the FK-holder table's schema, symmetric to the `@table` precedent, leaving unqualified behaviour byte-for-byte unchanged.

## Design

**Why a schema-name filter is not the retired bare-name anti-pattern.** The schema-qualified-`@table` bug class retired matching FK *endpoints* and table *directive echoes* by bare SQL name, because table names collide across schemas and a bare-name compare silently picked the first-hit wrong schema. A *schema* qualifier is different in kind: schema names are unique in the catalog by construction (there is no "two schemas with the same name"), and a constraint name is unique *within* a schema. So `(schemaName, constraintName)` identifies exactly one FK. Once `findForeignKey` narrows to that one jOOQ `ForeignKey`, everything downstream (`findForeignKeyRef`, `synthesizeFkJoin`) is already reference-/class-identity based. The qualifier only narrows the candidate set; it never re-introduces a bare-name compare on a colliding namespace.

**Parsing.** Add `parseQualifiedForeignKeyName(String)` (sibling to `parseQualifiedTableName`): split on the first `.`; empty either half is malformed and parses as unqualified (defensive, mirrors the table parser). A `.` cannot appear in a jOOQ constraint name and the Java-constant namespace uses `__`, so a dot unambiguously signals a schema qualifier in both namespaces. Reuse the existing `QualifiedTableName` record shape or introduce a parallel `QualifiedForeignKeyName` (decide in implementation; the table record's field names read fine for an FK too, but a distinct type documents intent).

**Lookup.** In `findForeignKey`, parse the qualifier off `name` up front. Match candidates in both namespaces against the *bare* constraint name (unchanged), then, when a qualifier is present, filter candidates to those whose holder schema (`fk.getTable().getSchema().getName()`) equals the qualifier case-insensitively. This filter runs *before* the existing source-scoping filter and the `distinct()`/ambiguity check, so:
- a qualified key that collides across schemas resolves to the one FK in the named schema (`Resolved`);
- a qualified key naming a schema that has no such FK yields `NotInCatalog` (not a silent first-hit);
- unqualified keys skip the filter entirely.

**Scoping precedence (compose, don't override).** An explicit qualifier and `sourceSqlName` scoping compose: the qualifier narrows to one schema, source scoping still applies its touching-FK filter on top (with the existing `if (!scoped.isEmpty())` guard, so it never empties a correct qualified hit). If the author qualifies with a schema whose FK does not touch the path source, the FK still resolves here, and the *existing* connection check in `BuildContext.parsePathElement` (`foreignKeyTouchesTable`, the "key '…' does not connect to table '…'" arm) catches the mismatch with a clear message. This keeps disagreement handling in the one place that already owns it rather than duplicating a new validation.

**LSP mirror.** `Diagnostics.validateCatalogFk` matches the `key:` value against a flat list of bare FK names case-insensitively, deliberately tracking `findForeignKey` so it never flags a name the generator would accept. A qualified `key:` would be a false-positive "Unknown foreign key" there. Strip the schema qualifier before matching, and — cheap, and it mirrors the generator's `NotInCatalog` — flag a qualifier whose schema/FK pair is absent. (Path-step refinement stays deferred, unchanged.)

**Docs.** Extend the `ReferenceElement.key` doc string in `directives.graphqls` and the `@reference` / `@referenceFor` reference pages (`docs/manual/reference/directives/`) to document the `schema.constraint` form and when it is needed (cross-schema constraint-name collision), pointing at the `@table` qualified form as the sibling.

## Tests

- **Unit tier (the resolution core):** extend `JooqCatalogMultiSchemaTest`. The fixture's `note_event_fk` collision is the vehicle. Assert: (1) `findForeignKey("note_event_fk", null)` is `Ambiguous` naming both schemas (pins the pre-fix hazard as the baseline); (2) `findForeignKey("multischema_a.note_event_fk", null)` is `Resolved` to the A-schema FK, and `"multischema_b.note_event_fk"` to the B-schema FK (holder-schema identity, not FK-target schema); (3) a qualifier naming a schema with no such FK (`"multischema_b.some_a_only_fk"`) is `NotInCatalog`; (4) unqualified unique keys still resolve unchanged. Add `parseQualifiedForeignKeyName` cases mirroring the `parseQualifiedTableName` battery (unqualified, qualified, multi-dot, blank/empty-half malformed).
- **Pipeline tier (the author-facing path):** a fixture with a `@reference(path: [{key: "multischema_a.note_event_fk"}])` where the bare name would be ambiguous asserts the reference resolves (no rejection) and lands the join on the A-schema FK. A sibling asserting the wrong-schema qualifier surfaces the existing "does not connect" rejection (composition with the connection check).
- **LSP tier:** extend `DiagnosticsTest` so a qualified `key:` naming a real `(schema, fk)` pair is *not* flagged, and a qualified key with a bogus schema *is* flagged.

## Out of scope

- The FK-candidate "did you mean" hint scoping/namespacing (owned by R282 and R259; this item does not touch the hint surfaces beyond what falls out of the qualifier parse).
- LSP path-aware completion / hover for qualified keys (the completion arm stays deferred, consistent with `validateCatalogFk`'s existing note).
- Any change to unqualified resolution semantics, the `ForeignKeyLookup` sealed shape, or the `@table` qualifier itself.
