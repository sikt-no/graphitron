---
id: R172
title: "Audit: forbid service-side references to <outputPackage>.inputs.*"
status: Backlog
bucket: architecture
priority: 8
theme: mutation-write
depends-on: []
created: 2026-05-17
last-updated: 2026-05-17
---

# Audit: forbid service-side references to `<outputPackage>.inputs.*`

R94 emits a graphitron-internal Java class per SDL input type under
`<outputPackage>.inputs.<InputName>`. The class is a Jakarta-validation
target: the fetcher boundary calls
`<InputName>.fromMap(env.getArgument(...))`, hands the result to
`validator.validate(...)`, and discards it. Service code (under the
consumer's package, never under `<outputPackage>.inputs`) must not
reference these classes; doing so re-creates the
service-side-graphitron-coupling R150's design rules out.

R94's original spec bundled three layered enforcement mechanisms:
1. A sealed marker interface `GraphitronInternalInput` with a generated
   `permits` clause listing every emitted class.
2. A generated `package-info.java` whose Javadoc states
   "Graphitron-internal validation targets; do not reference from
   service code".
3. A scanning audit test that walks consumer sources for references to the package.

graphitron-javapoet does not currently support records, sealed
classes/interfaces, `permits` clauses, or `package-info.java` emission;
(1) and (2) are blocked on a graphitron-javapoet upgrade and were
deferred out of R94. R94 ships with the package boundary plus per-class
Javadoc carrying the "graphitron-internal" intent.

This item adds the audit (3) as a standalone check. The audit walks
the consumer's compile-output classpath (or source roots, depending on
where the rewrite can plumb in) and flags any service-side reference
to `<outputPackage>.inputs.*` from outside the graphitron-emitted
code. The signal becomes a build-time error so the convention is
structurally carried even without a sealed marker.

## Acceptance

- A test or build-time check fails when a `@service`-implementing
  class (or any consumer class outside `<outputPackage>.inputs`)
  references a type in `<outputPackage>.inputs.*`.
- The failure names the offending reference and the rule it violates.
- The graphitron-emitted code under `<outputPackage>.inputs.*` is
  exempt (it self-references inside the package for nested-input
  `fromMap` recursion).
- The sakila fixture project passes without changes (no service code
  references the graphitron-emitted classes today).

## Out of scope

- Restoring R94's sealed marker / `package-info.java` (those return
  when graphitron-javapoet learns records + sealed; see the
  rewrite-internal graphitron-javapoet roadmap).
- Compile-time language-level prevention (we accept that reflection
  can bypass any audit; the audit is the cheap upstream signal).

## Dependencies

Blocked on R94 (`emit-input-records`) shipping the package.
