---
id: R174
title: "graphitron-javapoet: emit records, sealed/permits, package-info.java"
status: Backlog
bucket: architecture
priority: 9
theme: classification-model
depends-on: []
created: 2026-05-17
last-updated: 2026-05-17
---

# graphitron-javapoet: emit records, sealed/permits, package-info.java

`graphitron-javapoet` is forked from Square's JavaPoet at a point that
predates Java records (Java 14+) and sealed types (Java 17+). The
emit framework supports four `TypeSpec.Kind` values: `CLASS`,
`INTERFACE`, `ENUM`, `ANNOTATION`. Records, sealed/permits clauses,
and `package-info.java` files cannot be generated through the framework
today. The rewrite uses graphitron-javapoet exclusively for code emit,
so any emitter that wants to produce these shapes hits a wall.

R94 (`emit-input-records`) ran into this limitation: the spec called
for one Java record per SDL input type plus a sealed marker interface
plus a `package-info.java`. R94 shipped a degraded form (plain
classes-with-accessors, no marker, no package-info) and deferred the
spec-shape ambition to this item. R172 ships the audit that
substitutes for the marker-and-package-info enforcement in the
meantime.

This item extends graphitron-javapoet with:
1. `TypeSpec.Kind.RECORD` plus the corresponding `recordBuilder` API.
   Records carry an ordered component list, a canonical compact-form
   constructor (no body — javapoet renders the compact form), and
   accessor synthesis.
2. `sealed` modifier and `permits` clause support on `TypeSpec`
   (classes and interfaces). The builder accepts a `permits` list of
   `TypeName`; the rendered output emits `sealed ... permits A, B, C`
   in the type header.
3. `non-sealed` modifier support on subtypes of sealed parents.
4. Package-info emission: either a new `PackageSpec` shape or a
   `JavaFile`-level option to render `package-info.java`. The file
   carries Javadoc and any package-level annotations.

Each change is a substantive emit-framework upgrade with downstream
impact across the rewrite once available. The follow-on consumer
work (re-emitting R94's `<outputPackage>.inputs.*` as records + sealed
marker + package-info) is its own slice.

## Acceptance

- `TypeSpec.recordBuilder("Foo")` emits a valid Java record header
  with the specified components, modifiers, implements clause, and
  any methods.
- `TypeSpec.classBuilder("Foo").sealedTo(List.of(ClassName.get(...)))`
  (or equivalent API) emits the `sealed ... permits ...` header.
- `JavaFile.packageInfo(...)` (or equivalent) emits a valid
  `package-info.java` carrying Javadoc and package-level annotations.
- Existing emit sites compile unchanged (additive APIs).
- A round-trip test confirms each new shape emits valid Java 17
  source.

## Out of scope

- Downstream re-emit work (R94's classes-to-records migration is its
  own slice once this item ships).
- Pattern-matching, switch-pattern, or other later-Java-version
  syntactic features. Records, sealed, and package-info are the
  Java-17 frontier the rewrite needs immediately.
