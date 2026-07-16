---
id: R492
title: "Add a {@link}-reference-validity gate to the routine build"
status: Spec
bucket: cleanup
priority: 11
theme: docs
depends-on: []
created: 2026-07-16
last-updated: 2026-07-16
---

# Add a {@link}-reference-validity gate to the routine build

A broken `{@link}` (or `{@see}`) reference currently compiles clean and passes the routine gate. The maven-javadoc-plugin `jar` goal that would resolve references is bound only in the `release` profile (`pom.xml`); the routine build and CI run `mvn verify -Plocal-db` with no javadoc and no doclint; and `javac` ignores javadoc entirely. So a dangling link target is invisible until a release build, and `{@link}` carries only the IDE-refactor tracking the principles grade as "no audit infrastructure" (`development-principles.adoc`). In a text-editing, multi-agent workflow, where agents edit `.java` as text rather than through IDE rename refactors, that tracking gives near-zero protection: a rename silently orphans every link that named the old symbol.

This item adds a reference-resolution gate that runs in the routine `-Plocal-db` build and fails on a dangling `{@link}`/`{@see}` target, so a link that names a live symbol becomes a real, gate-enforced pin.

## Mechanism: the javadoc tool's own resolver, not a hand-rolled scanner

The gate is the `maven-javadoc-plugin` `javadoc` goal (already managed at 3.12.0 in the root pom's `pluginManagement`, used today by the release profile's `attach-javadocs`) configured with `<doclint>reference</doclint>`, not a meta-test in the R482 `RoadmapReferenceScanner` mould. The distinction is load-bearing: a scanner would have to reimplement javadoc's symbol resolution (imports, nested types, inherited members, and types resolved off the dependency classpath), and it would false-positive on exactly the legitimate external and cross-module links in the backlog below. The javadoc tool resolves references authoritatively; recon confirms a dangling reference under the `reference` group is fatal (BUILD FAILURE, non-zero exit), which is the gate behaviour we want with no extra `failOnWarnings` wiring.

**Group scope: `reference` only.** Not `missing` (which would flag every undocumented `@param`/`@return` across thousands of comments), not `html` or `syntax`. The gate's single job is link validity.

## Placement: a default-on profile, skippable locally like docs

Generating javadoc forks the tool per module and costs real wall-clock (recon runs took minutes reactor-wide), the same "slow generation step" profile that `-P!docs` already lets the inner loop skip. Mirror that: the gate lives in a profile active by default so CI and a full `mvn verify -Plocal-db` run it, and `-P!<id>` turns it off for the fast local loop. CI (`rewrite-build.yml`) keeps running the same `mvn verify -Plocal-db` it does today and picks up the gate for free. (Final placement shape confirmed at Ready; this is the lean.)

## The backlog: 41 pre-existing broken references, measured

Turning the gate on is blocked by references that already fail to resolve. Measured per in-scope module with `maven-javadoc-plugin:javadoc -Ddoclint=reference`: `graphitron` 30, `graphitron-sakila-service` 10, `graphitron-lsp` 1, all others 0. They fall in three kinds, each with a different correct fix:

- **Genuine drift** (a `{@link}` to a renamed, moved, or removed symbol, e.g. `ReturnTypeRef.TableBoundReturnType`, `ServiceCatalog#reflectExternalField`): repoint to the current symbol.
- **Syntax** (a nested member addressed with `.` instead of `#`, e.g. `GraphitronType.TableType`): fix the separator.
- **External / cross-module** (links to jOOQ types like `Record1#value1()` or downstream-module types like `BatchKey.MappedRecordKeyed`): the fix is getting the module's javadoc dependency classpath right so the link *resolves*, not downgrading a legitimate `{@link}` to `{@code}`. Only a reference that is genuinely dead after the classpath is correct gets rewritten.

Driving all 41 to zero is turning-the-gate-green work, not a front-run of R483: R492 touches only references that fail to resolve, by the mechanical fix above; R483 reads the whole surface for semantic drift with the gate already live.

## Done

- The `reference`-group gate runs in the default `-Plocal-db` build (and CI) and fails on any dangling `{@link}`/`{@see}`.
- All 41 measured broken references resolve (drift repointed, syntax fixed, classpath corrected so legitimate external links resolve; only truly-dead references rewritten). A clean `-Plocal-db` build with the gate active is the proof.
- A planted dangling `{@link}` fails the gate. Unlike R482's pure-Java scanner, the gate is the javadoc tool itself, so this is verified by the build failing rather than by a JUnit meta-test (forking javadoc from a unit test is not worth the machinery); the verification is recorded, not automated.
- The local skip (`-P!<id>`) is documented in CLAUDE.md's build section next to `-P!docs`.

This is the prerequisite that lets R483's relink tool count as a real pin rather than a soft improvement; R483 depends on it. Sibling to R482 (the roadmap-reference comment guard), which established the pattern of shipping a comment/javadoc enforcer as the payload.
