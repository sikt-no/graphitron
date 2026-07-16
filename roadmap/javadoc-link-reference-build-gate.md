---
id: R492
title: "Add a {@link}-reference-validity gate to the routine build"
status: Backlog
bucket: cleanup
priority: 11
theme: docs
depends-on: []
created: 2026-07-16
last-updated: 2026-07-16
---

# Add a {@link}-reference-validity gate to the routine build

A broken `{@link}` (or `{@see}`) reference currently compiles clean and passes the routine gate. The maven-javadoc-plugin `jar` goal that would resolve references is bound only in the `release` profile (`pom.xml`); the routine build and CI run `mvn verify -Plocal-db` with no javadoc and no doclint; and `javac` ignores javadoc entirely. So a dangling link target is invisible until a release build, and `{@link}` carries only the IDE-refactor tracking the principles grade as "no audit infrastructure" (`development-principles.adoc`). In a text-editing, multi-agent workflow, where agents edit `.java` as text rather than through IDE rename refactors, that tracking gives near-zero protection: a rename silently orphans every link that named the old symbol.

This item adds a reference-resolution gate that runs in the routine `-Plocal-db` build and CI and fails on a dangling `{@link}`/`{@see}` target, so a link that names a live symbol becomes a real, gate-enforced pin. Likely mechanism: `-Xdoclint:reference` (the reference group only, not the missing-`@param` firehose) on a `verify`-phase javadoc goal, or a focused meta-test that resolves link targets against the compiled classpath; the mechanism choice belongs to this item's Spec. Enabling reference checking across the reactor is expected to surface a backlog of pre-existing broken links, which must be fixed to make the gate green; that cleanup is in scope here.

This is the prerequisite that lets R483's relink tool count as a real pin rather than a soft improvement; R483 depends on it. Sibling to R482 (the roadmap-reference comment guard), which established the pattern of shipping a comment/javadoc enforcer as the payload.
