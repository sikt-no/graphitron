---
id: R174
title: "graphitron-javapoet: emit records, sealed/permits, package-info.java"
status: Backlog
bucket: architecture
priority: 9
theme: classification-model
depends-on: [unskip-javapoet-tests]
created: 2026-05-17
last-updated: 2026-10-26
---

# graphitron-javapoet: emit records, sealed/permits, package-info.java

## Goal

An emitter in the rewrite can produce a Java record, a sealed type
with a `permits` clause, and a `package-info.java` file through
`graphitron-javapoet`, the in-tree copy of JavaPoet that all generated
source passes through. Today it cannot: the fork was taken from
Square's JavaPoet before records (Java 14+) and sealed types
(Java 17+), so `TypeSpec.Kind` offers only `CLASS`, `INTERFACE`,
`ENUM` and `ANNOTATION`, and there is no way to write a
`package-info.java`. For a consumer of graphitron nothing changes when
this item lands; it unblocks generated-code shapes that do change what
consumers see, starting with R94's input types.

R94 (`emit-input-records`) ran into this limitation: the spec called
for one Java record per SDL input type plus a sealed marker interface
plus a `package-info.java`. R94 shipped a degraded form (plain
classes-with-accessors, no marker, no package-info) and deferred the
spec-shape ambition to this item. R172 ships the audit that
substitutes for the marker-and-package-info enforcement in the
meantime.

## Plan: port from Palantir's JavaPoet

Square archived JavaPoet in October 2024, and its README names
Palantir's JavaPoet (`com.palantir.javapoet:javapoet`, Apache 2.0) as
the option for current Java language features. Palantir's version
already implements records and sealed types and is actively released
(0.22.0 on 2026-10-18). Rather than writing these from scratch, port
Palantir's implementation into `graphitron-javapoet`, keeping our
package and our additions, and credit Palantir in the module README
and the touched files' headers next to the existing Square
attribution.

What 0.22.0 provides, read from its sources jar:

1. **Records.** `TypeSpec.Kind.RECORD` (rendered keyword `record`),
   `TypeSpec.recordBuilder(String)` and `recordBuilder(ClassName)`,
   and `TypeSpec.Builder.recordConstructor(MethodSpec)`. The record
   constructor's parameters become the record components in the
   header; a non-empty constructor body is emitted inside the type.
   `MethodSpec.compactConstructorBuilder()` produces the compact form
   (`Foo { ... }`, no parameter list).
2. **Sealed types.** `TypeSpec.Builder.addPermittedSubclass(...)` and
   `addPermittedSubclasses(...)` (overloads for `TypeName`, `Type`,
   `TypeMirror`), rendering `sealed ... permits A, B, C`. `build()`
   rejects permitted subclasses on a type without `Modifier.SEALED`.
   Palantir validates only `SEALED`; confirm that `Modifier.NON_SEALED`
   on a subtype renders as `non-sealed` and add handling if not.
3. **`package-info.java`.** Not in Palantir. Add it ourselves: either
   a `JavaFile`-level option or a small dedicated spec that renders
   the package declaration with its Javadoc and package-level
   annotations, written through the same `writeToPathReporting` path
   as other files.

The port is concentrated in `TypeSpec` (the `Kind` enum, builder,
header emit, validation) and `MethodSpec` (the compact-constructor
flag and its emit), with `CodeWriter` touched for header rendering.
Palantir's sources use Java 16+ syntax such as pattern-matching
`instanceof`; that is fine here, since generator code targets Java 25.
The source the port *emits* must still be valid at Java 17, which
records and sealed types are.

Sequencing: `graphitron-javapoet`'s own 400-test suite is currently
skipped (`skipTests` in its pom). R560 turns it back on; land that
first so this change to the emit framework runs against the fork's
regression suite, and port Palantir's record and sealed tests
alongside the code.

## Acceptance

- `TypeSpec.recordBuilder("Foo")` emits a valid Java record header
  with the specified components, modifiers, implements clause, and
  any methods, including a compact canonical constructor.
- A sealed class or interface built with permitted subclasses emits
  the `sealed ... permits ...` header, and a `non-sealed` subtype
  emits correctly.
- A `package-info.java` with Javadoc and package-level annotations can
  be emitted.
- Existing emit sites compile unchanged (additive APIs).
- A round-trip test compiles each new shape as Java 17 source.

## Out of scope

- Downstream re-emit work (R94's classes-to-records migration is its
  own slice once this item ships).
- Pattern-matching, switch-pattern, or other later-Java-version
  syntactic features. Records, sealed, and package-info are the
  Java-17 frontier the rewrite needs immediately.

## Other solutions we've considered

- **Depend on Palantir's JavaPoet directly instead of the fork.** Our
  fork already matches Palantir in the most disruptive respect (fields
  are private behind accessors such as `kind()` and `name()`), so most
  call sites would survive. Against it: 289 files import
  `no.sikt.graphitron.javapoet` and would all change; our additions
  have no Palantir equivalent and would need a new home (in use
  outside the module: `CodeBlock.declare` and friends, 16 call sites;
  `TypeSpec.referencedClassNames`, 8; `CodeBlock.statementOf`, 2;
  `JavaFile.writeToPathReporting`, 1; `CodeBlock.empty`, 1); and
  Palantir is still 0.x with frequent releases, so API breaks are
  allowed and we would track them. It would also reverse the module
  README's stated reason for forking: keeping the emitter an
  implementation detail with no compatibility obligations. Reconsider
  if the fork's own additions shrink or Palantir reaches 1.0.
- **Implement records and sealed types from scratch.** Same result as
  the port, with more design work and less tested code.
- **A different code-generation library** (Roaster, JavaParser's
  printer, JCodeModel, Micronaut's sourcegen fork). None shares
  JavaPoet's API, so every emitter would be rewritten, for features a
  port provides at a fraction of the cost.
