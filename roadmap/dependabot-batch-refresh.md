---
id: R1104
title: "Take the open Dependabot bumps, best effort per bump"
status: In Review
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

Status as surveyed on 2026-10-27. Every pull request's diff was checked with `git apply --check` against trunk `16549939c`: all apply cleanly except #518, whose one-line property change has drifted context and is applied by hand.

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
|Take. The Maven build never runs node; this directory is the recipe for the GraphiQL bundle committed under `graphitron-jakarta-rest/src/main/resources/.../graphiql/`, and its README requires the committed output to stay in sync with the recipe. So the bump is checked by `npm ci && npm run build` in that directory: no diff under `graphiql/` means the lockfile change ships alone; a diff ships with it. If node is unavailable to the implementer, drop it from the batch rather than ship an unverified recipe.

|#518
|Testcontainers PostgreSQL 2.0.4 to 2.0.5
|green (July)
|Take; apply by hand (context drift, see above).

|#519
|`maven-plugin-api` 3.9.14 to 3.9.16
|green (July)
|Take.

|#550
|`maven-plugins` group, 13 updates: compiler 3.15.0 to 3.16.0, surefire and failsafe 3.5.6 to 3.6.0, plugin-plugin 3.15.2 to 3.16.0, install and deploy 3.1.4 to 3.2.0, jar 3.5.0 to 3.5.1, enforcer 3.6.2 to 3.6.3, antrun 3.1.0 to 3.2.0, versions 2.21.0 to 2.22.0, build-helper 3.6.1 to 3.6.2, exec 3.5.0 to 3.6.4 (in `docs`, `graphitron-mcp`, `roadmap-tool`), invoker 3.6.0 to 3.10.1
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
|red (2026-09-28): `graphitron-mcp` surefire goal failed after every other module's tests passed; the job log names no failing test, so the cause (the bump, or a trunk-side problem at the time) is undetermined
|Try; reproduce with `-pl :graphitron-mcp` first and read `target/surefire-reports`. Drop from the batch if the bump is the cause and the fix is more than a one-line change.

|#517
|Groovy 4.0.31 to 5.0.7 (the `maven-invoker-plugin` dependency override in `graphitron-maven-plugin/pom.xml`)
|green, but on a much older build
|Try last; a major version under the invoker integration tests' verify scripts, which the pin exists to keep runnable on Java 25. Drop if any script needs changes. Interacts with #550's invoker bump; see below.
|===

## Interaction between #550 and #517

The Groovy override exists because the invoker plugin's own bundled Groovy could not read Java 25 class files. #550 moves invoker from 3.6.0 to 3.10.1, which may bundle a Groovy recent enough that the override is no longer needed. The implementer checks this after the #550 build: if the integration tests pass with the override removed, removing it is preferred to bumping it, since it retires a pin rather than moving one; the pom comment explaining the pin goes with it. If the override is still needed, #517 is tried as written. Either outcome is in scope; a Groovy 5 failure is not a reason to hold #550.

## Implementation

1. Apply the low-risk set (#522, #518, #519, #550, #541, #514, #513) on one branch off trunk and run the verification build (`mvn install -Plocal-db`). The bumps are applied as edits to our own tree, not by merging the Dependabot branches, since those are based on stale `main` commits. If something fails, bisect within the set and drop the offender rather than fixing forward, unless the fix is trivial (a version-pinned property, a renamed plugin parameter).
2. Try #551 and then the Groovy question (see above) one at a time on top, each with its own build, so a failure points at one cause. Each either lands or is dropped per the goal's rule. A dropped bump never blocks publishing the ones that passed: what is green ships.
3. Retarget Dependabot: add `target-branch: "claude/graphitron-rewrite"` to every ecosystem entry in `.github/dependabot.yml`, so future pull requests run CI against the current tree and merging one cannot break the `main` sync. Decide whether `graphitron-jakarta-rest/tools/graphiql-build` deserves its own `npm` entry; today it only receives security-alert pull requests.
4. After trunk syncs to `main`, close what Dependabot did not close itself (#554 and anything dropped), each with a one-line reason, filing a Backlog item for a dropped bump worth pursuing.

## Other solutions we've considered

- Merging the pull requests on GitHub into `main`: breaks `sync-main.yml`, as explained above.
- Retargeting Dependabot first and letting it reopen fresh pull requests against trunk, then merging those: leaves ten pull requests to drive to green one by one, each with a 30+ minute CI run, for changes that are one or two lines each. One branch with one verification build is cheaper, and Dependabot closes the superseded pull requests itself.

## Tests

No new tests. Acceptance is a green verification build and a green trunk CI run (including `docs-build`/`docs-deploy` for the Pages actions) on the tree carrying the taken bumps, and an open Dependabot list holding only bumps this item explicitly dropped.

## Implementation notes

What the batch came to, for the In Review gate:

* **Taken:** #518, #519, #550 (plus `exec-maven-plugin` in `graphitron/pom.xml`, which the pull request predates), #541, #514, #513, #522 and #551.
  * #522: `npm ci && npm run build` in `graphitron-jakarta-rest/tools/graphiql-build` reproduces the committed bundle with no diff, so the lockfile ships alone.
  * #551: the September red did not reproduce. `-pl :graphitron-mcp` on the bumped tree runs 151 tests green, with `dependency:list` confirming JUnit 6.1.3 on the module's test classpath, so the cause was trunk-side at the time.
* **Groovy:** invoker 3.10.1 declares Groovy 4.0.31 itself (its `groovyVersion` property imports `groovy-bom` 4.0.31), the version our override pinned. With the override and its comment removed, the 11 invoker integration tests pass, `verify.groovy` scripts included. The pin is retired, and #517 (Groovy 5.0.7) is not taken: invoker owns its Groovy now.
* **Closed without taking:** #554 (superseded: trunk's lockfile carries markdown-it 14.3.3) and #517.
* **Dependabot:** both ecosystem entries carry `target-branch: "claude/graphitron-rewrite"`. No `npm` entry for `graphiql-build`: a version-update pull request there needs a bundle rebuild and commit beside the lockfile, which Dependabot cannot do. Security-alert pull requests, which ignore `target-branch` and keep targeting `main`, already cover it. When one arrives, it is applied on the trunk the way #522 was.
* **Verification:** the first full build of the low-risk set failed only in `GraphQLQueryTest.filmsByTitleBean_absentInput_buildsNoBeanAndLeavesTheFieldUnfiltered`, which saw two other classes' transient films (150, 151): a victim R863 already records, not a bump. Resuming from `graphitron-sakila-example` was green.

## Reviewer findings

### Round 1 (2026-10-27, Spec -> Ready, reviewer session 012sVnS83TmVK37gGHNvqRf6)

Verdict: Ready. The goal reads on its own: the build, test and CI dependencies catch up with what Dependabot has proposed, every bump that lands cleanly ships through the normal trunk flow, a bump that needs real work is parked rather than blocking the rest, and future Dependabot pull requests target the trunk so they can be merged. The plan uses the existing shapes (one feature branch, the verification build, `dependabot.yml`'s own `target-branch` key) and adds no mechanism. The facts behind it check out against the tree and GitHub: the ten pull requests are open against `main`, `sync-main.yml` refuses a non-fast-forward, `rewrite-build.yml` runs pull-request CI for `claude/graphitron-rewrite`, trunk's GraphiQL lockfile already carries markdown-it 14.3.3, and every "from" version in the batch table matches the poms and workflows.

One note for the implementer, which does not change the design:

1. #550 predates `exec-maven-plugin` in `graphitron/pom.xml` (added on 2026-10-26, also at 3.5.0), so applying the pull request's diff leaves that fourth site behind. Bump it with the others. Applying the diff also bumps `build-helper-maven-plugin` in `graphitron-model/pom.xml`, which the table lists by version but not by module.
