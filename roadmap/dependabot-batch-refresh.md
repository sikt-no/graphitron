---
id: R1104
title: "Take the open Dependabot bumps, best effort per bump"
status: Backlog
bucket: tech-debt
priority: 5
theme: tooling
depends-on: []
created: 2026-10-27
last-updated: 2026-10-27
---

# Take the open Dependabot bumps, best effort per bump

## Goal

Graphitron's build, test and CI dependencies catch up with the ten Dependabot pull requests open against `main`, taking every bump that lands cleanly and parking the rest, so the open-PR list stops going stale and future Dependabot pull requests target the trunk where they can be merged. The rule that shapes the work: no single bump may hold up the others. A bump that turns out to need more than a version change (code edits, test rework, a migration) is pulled out of the batch, its pull request stays open or is closed with a note, and it gets its own Backlog item if it is worth having.

## Why the pull requests cannot just be merged

All ten target `main`, which only ever moves by `sync-main.yml` fast-forwarding it to `claude/graphitron-rewrite`. That job refuses a non-fast-forward, so merging one Dependabot pull request into `main` on GitHub would stop the sync until someone repairs it by hand. Most of the pull requests are also weeks or months behind trunk, so their CI results describe a tree we no longer have. The bumps are therefore applied on a feature branch off trunk and shipped through the normal flow; once `main` syncs, Dependabot closes each pull request whose bump `main` now carries.

## The batch

Status as surveyed on 2026-10-27 against trunk `89cde04`:

[cols="1,3,2,2"]
|===
|PR |Change |Last CI on the PR |Expectation

|#554
|markdown-it 14.3.0 to 14.3.2 in `graphitron-jakarta-rest/tools/graphiql-build`
|n/a
|Superseded: trunk's lockfile already has 14.3.3. Close.

|#522
|postcss 8.5.16 to 8.5.25 in `graphitron-jakarta-rest/tools/graphiql-build`
|preview only
|Lockfile only; take.

|#518
|Testcontainers PostgreSQL 2.0.4 to 2.0.5
|green (July)
|Take.

|#519
|`maven-plugin-api` 3.9.14 to 3.9.16
|green (July)
|Take.

|#550
|`maven-plugins` group, 13 updates (surefire/failsafe 3.6.0, compiler 3.16.0, javadoc 3.10.1, invoker, enforcer, and others)
|green (2026-09-28)
|Take; it touches build tooling, so the verification build is the real check.

|#541
|`actions/setup-java` v5 to v6
|cancelled, never finished
|Take; trunk CI after the push is the check.

|#514, #513
|`actions/deploy-pages` v4 to v5 and `actions/upload-pages-artifact` v3 to v5
|not exercised (Pages jobs run on trunk pushes only)
|Take together, since they are the upload/deploy pair.

|#551
|`test-dependencies` group: JUnit 6.0.3 to 6.1.3, Mockito 5.23.0 to 5.24.0
|red: `graphitron-mcp` surefire failures
|Try; drop from the batch if the failure is caused by the bump and is not a one-line fix.

|#517
|Groovy 4.0.31 to 5.0.7 (the `maven-invoker-plugin` dependency override in `graphitron-maven-plugin/pom.xml`)
|green, but on a much older build
|Try last; a major version under the invoker integration tests' verify scripts, which the pin exists to keep runnable on Java 25. Drop if any script needs changes.
|===

## Implementation

1. Apply the low-risk set (#522, #518, #519, #550, #541, #514, #513) on one branch off trunk and run the verification build (`mvn install -Plocal-db`). If something fails, bisect within the set and drop the offender rather than fixing forward, unless the fix is trivial (a version-pinned property, a renamed plugin parameter).
2. Try #551 and #517 one at a time on top, each with its own build, so a failure points at one cause. Each either lands or is dropped per the goal's rule.
3. Retarget Dependabot: add `target-branch: "claude/graphitron-rewrite"` to every ecosystem entry in `.github/dependabot.yml`, so future pull requests run CI against the current tree and merging one cannot break the `main` sync. Decide whether `graphitron-jakarta-rest/tools/graphiql-build` deserves its own `npm` entry; today it only receives security-alert pull requests.
4. After trunk syncs to `main`, close what Dependabot did not close itself (#554 and anything dropped), each with a one-line reason, filing a Backlog item for a dropped bump worth pursuing.

## Tests

No new tests. Acceptance is a green verification build and a green trunk CI run (including `docs-build`/`docs-deploy` for the Pages actions) on the tree carrying the taken bumps, and an open Dependabot list holding only bumps this item explicitly dropped.
