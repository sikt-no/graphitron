---
id: R492
title: "Add a {@link}-reference-validity gate to the routine build"
status: Ready
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

## Placement: bound in the main build, on by default, skipped by `maven.javadoc.skip`

The invariant must run in the *default* build, not CI-only: a CI-only check lets a contributor's inner loop drift and only rejects it after push, the caught-late posture "Every invariant has an enforcer" pushes against. So bind an execution of the `maven-javadoc-plugin` `javadoc` goal (doclint=reference) directly in the main `<build>`, on by default.

**Do not use an `activeByDefault` profile.** Maven silently deactivates `activeByDefault` whenever any other profile is named, and this repo always runs `-Plocal-db`, so such a gate would never activate and would pass vacuously on every real build. The `leaf-coverage` profile (`pom.xml`) carries exactly this warning and uses property-negation activation instead. Here the fast-local lever already exists: the `javadoc` goal honours `maven.javadoc.skip`, and the `quick` profile (`pom.xml`) already sets `maven.javadoc.skip=true`. So the gate is on for every default and CI build and off under `-Pquick`, no new profile, and CI (`rewrite-build.yml`, the single `mvn verify -Plocal-db -T 1C`) picks it up for free. Generating javadoc forks the tool per module and costs real wall-clock (recon runs took minutes reactor-wide); `-Pquick` is the documented escape for the inner loop.

## The backlog: 41 pre-existing broken references, measured

Turning the gate on is blocked by references that already fail to resolve. Measured per in-scope module with `maven-javadoc-plugin:javadoc -Ddoclint=reference`: `graphitron` 30, `graphitron-sakila-service` 10, `graphitron-lsp` 1, all others 0. They fall in three kinds, each with a different correct fix:

- **Genuine drift** (a `{@link}` to a renamed, moved, or removed symbol, e.g. `ReturnTypeRef.TableBoundReturnType`, `ServiceCatalog#reflectExternalField`): repoint to the current symbol.
- **Syntax** (a nested member addressed with `.` instead of `#`, e.g. `GraphitronType.TableType`): fix the separator.
- **External / cross-module** (links to jOOQ types like `Record1#value1()` or downstream-module types like `BatchKey.MappedRecordKeyed`): doclint `reference` needs only the referenced type on the classpath (bytecode, not sources), and `maven-javadoc-plugin` puts compile+runtime deps there by default, so these *should* resolve once the goal's classpath is right. The fix is the classpath or goal config, never the comment. **Prohibition: do not rewrite a resolvable `{@link}` to `{@code}` to appease the tool.** That downgrade converts a javadoc-checked pin back into unchecked prose, which is the exact inversion of "documentation names only live tests/code"; it makes the gate erode pins instead of strengthening them. Only a reference that is genuinely dead *after* the classpath is correct gets rewritten.

Driving all 41 to zero is turning-the-gate-green work, not a front-run of R483, and the split is mechanical rather than judgment-based: **R492's fix-scope is exactly the set `-Ddoclint=reference` rejects, each repointed by the minimal change to the correct live symbol.** Where the correct target is not cheaply knowable, that is an R483 flag, not an R492 semantic sweep. R483's domain is precisely what the reference gate *cannot* see: a link that resolves to a live but *wrong* symbol, and prose whose symbols exist but whose assertion is stale. Those pass `reference` untouched and are left for the audit.

## Done

- The `reference`-group gate runs in the default `-Plocal-db` build (and CI) and fails on any dangling `{@link}`/`{@see}`.
- **Positive evidence it actually ran, per in-scope module.** A javadoc gate has the same vacuous-pass failure mode `RoadmapReferenceGuardTest`'s `MIN_SCANNED_FILES` floor guards against: a module that silently skips the goal (an inherited `maven.javadoc.skip`, an empty `sourcepath`, a misbound phase) reports zero warnings and greens having checked nothing. The gate must carry evidence each in-scope module ran the check, not merely "no errors reported". In-scope modules are decided against `RoadmapReferenceGuardTest.IN_SCOPE_MODULES` (10 modules); `sourcepath=src/main/java` correctly excludes generated sources under `target/`, so the gate audits hand-authored links only. `graphitron-sakila-db` has no hand-authored main sources and legitimately contributes nothing.
- All 41 measured broken references resolve (drift repointed, syntax fixed, classpath corrected so legitimate external links resolve; only truly-dead references rewritten). A clean `-Plocal-db` build with the gate active is the proof.
- A planted dangling `{@link}` fails the gate. Unlike R482's pure-Java scanner, the gate is the javadoc tool itself, so this is verified by the build failing rather than by a JUnit meta-test (forking javadoc from a unit test is not worth the machinery); the verification is recorded, not automated.
- **The principles doc is corrected in the same commit that lands the gate.** `development-principles.adoc` grades `{@link}` as "IDE-refactor-tracked, no audit infrastructure"; the moment this gate lands that sentence is false. Rewrite the grade and add an *Enforced by* line naming the gate. Keep it a substitution, not an addition, `DocSizeBudgetTest.developmentPrinciplesStaysUnderBudget` holds the page under its word budget.
- The fast-local skip (`-Pquick`, which already sets `maven.javadoc.skip`) is documented in CLAUDE.md's build section next to `-P!docs`.

This is the prerequisite that lets R483's relink tool count as a real pin rather than a soft improvement; R483 depends on it. Sibling to R482 (the roadmap-reference comment guard), which established the pattern of shipping a comment/javadoc enforcer as the payload.
