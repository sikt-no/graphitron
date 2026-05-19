---
id: R183
title: "GitLab pipeline: drop snapshots, publish rewrite reactor on release tags"
status: In Progress
bucket: bug
theme: legacy-migration
depends-on: []
created: 2026-05-19
last-updated: 2026-05-19
---

# GitLab pipeline: drop snapshots, publish rewrite reactor on release tags

`.gitlab-ci.yml` was added when the rewrite reactor still lived nested under `graphitron-rewrite/` and was not the publish target. The current file therefore runs `mvn ... -P gitlab clean deploy` at the working-tree root with no `-f graphitron-rewrite/pom.xml`, against the legacy `graphitron-parent` reactor whose `${revision}${changelist}` resolves to `9-gitlab-SNAPSHOT`. The `gitlab` Maven profile only exists on the root pom; `graphitron-rewrite/pom.xml` has no equivalent.

Now that `claude/graphitron-rewrite` is the GitLab default branch and the GitHub → GitLab push-mirror is live, the `publish:snapshot` job fires on every mirrored commit and deploys legacy artifacts at `9-gitlab-SNAPSHOT` to Sikt's GitLab Packages registry. The `publish:release` job has the same misdirection plus its `^v\d+\.\d+\.\d+$` regex rejects the `-RC<n>` suffixes the rewrite is meant to ship through Maven Central (see GitHub workflow rationale in `claude/graphitron-rewrite`'s `.github/workflows/maven-publish.yml`).

This item fixes both jobs and the policy question that surfaced them: snapshot publishing is dropped entirely. Snapshots are not a useful consumer-facing artifact for the rewrite (consumers depend on release coordinates; SNAPSHOT cleanup in GitLab is a manual UI configuration; and developer caches do not need a registry round-trip).

## End state

- `publish:snapshot` job removed (or commented out with a one-line rationale referencing this item).
- `publish:release` runs `mvn -f graphitron-rewrite/pom.xml versions:set -DnewVersion=$VERSION -DgenerateBackupPoms=false -DprocessAllModules=true` followed by `mvn -f graphitron-rewrite/pom.xml ... clean deploy -P gitlab,local-db -Ddb.url=jdbc:postgresql://postgres:5432/rewrite_test`.
- `publish:release` tag regex matches `^v\d+\.\d+\.\d+(-RC\d+)?$`, identical to the GitHub workflow on `claude/graphitron-rewrite`.
- `graphitron-rewrite/pom.xml` carries the `gitlab` profile (distribution-management + sources-jar, ported from the root pom), but with **no `<snapshotRepository>`** so an accidental `mvn deploy` on `10-SNAPSHOT` fails fast (the rewrite parent's stated invariant, see `graphitron-rewrite/docs/README.adoc` publishing section). The root-pom copy stays in place until R182 deletes the entire legacy reactor; in the meantime it is unreachable without a `-f pom.xml` flag callers no longer pass.
- Pipeline base image bumps from `maven:3.9-eclipse-temurin-21` to `maven:3.9-eclipse-temurin-25`. The rewrite parent's `requireJavaVersion` enforcer rule (graphitron-rewrite/pom.xml:230-232) requires JDK 25, so the legacy image fails the build the moment `-f graphitron-rewrite/pom.xml` takes effect.
- `publish:release` runs against a `postgres:18-alpine` service (alias `postgres`), with `init.sql` from `graphitron-sakila-db/src/main/resources/` applied in the script via `postgresql-client` installed in `before_script`-style apt-get. jOOQ codegen runs in-pipeline against the live service (no Testcontainers / Docker-in-Docker), and the `-Plocal-db` profile in `graphitron-sakila-db/pom.xml` is activated alongside `gitlab` to point codegen at the service rather than spinning up a Testcontainer.
- `graphitron-sakila-example`'s `local-db` profile is parameterized: `test.db.url`, `test.db.username`, and `test.db.password` are exposed as pom properties (with a `localhost:5432` default) and referenced via `${test.db.url}` in surefire's `systemPropertyVariables`. The publish job passes `-Dtest.db.url=jdbc:postgresql://postgres:5432/rewrite_test` so the execution-tier tests connect to the service rather than a Testcontainer or a non-existent `localhost`.
- The same apt-get step also installs `gcc`, because `graphitron-lsp` builds a native tree-sitter shared library at `generate-resources` (`graphitron-lsp/src/main/native/build-native.sh`) that's bundled into the published `graphitron-lsp` jar. Without `gcc`, `build-native.sh` exits 127 on `cc` and the deploy fails before `versions:set` even returns to the parent reactor.
- Tests run in the publish pipeline. The earlier "skip tests, GitHub gates them" rationale was load-bearing only when Docker-in-Docker was the alternative; once a real Postgres is in the runner anyway, running tests is a cheap deploy-boundary sanity check, and GitHub's publish workflow is itself in flux per R182 so cannot be relied on as a gate yet.

## Out of scope

- Removing the legacy reactor or the root-pom `gitlab` profile. R182 collapses both as part of the unnest; the root-pom copy of the profile dies with the legacy `pom.xml` it lives in.
- Maven Central publishing. The GitHub workflow on `main` is a separate fix once R182 retires the legacy reactor; this item only touches GitLab.
- Cleanup of existing junk `9-gitlab-SNAPSHOT` artifacts already deposited in GitLab Packages. Manual UI task, called out for the implementer to do once the snapshot job stops firing.
- A `release:` job using `release-cli` to create a GitLab Release object attached to the tag. Skipped by default: consumers depend by Maven coordinate, so a release-list entry in GitLab's UI is cosmetic. Can be added in a follow-up if a visible release surface is wanted.

## Implementation

Shipped on this branch:

- `graphitron-rewrite/pom.xml` gained the `gitlab` profile (no `<snapshotRepository>`, so accidental `mvn deploy` on `10-SNAPSHOT` still fails fast).
- `.gitlab-ci.yml` lost `publish:snapshot`, bumped to `maven:3.9-eclipse-temurin-25`, rewrote `publish:release` to use `-f graphitron-rewrite/pom.xml` on both `mvn` calls, added `-DprocessAllModules=true` to `versions:set`, widened the tag regex to `^v\d+\.\d+\.\d+(-RC\d+)?$`, and updated the header comment block.
- `publish:release` provisions a `postgres:18-alpine` service, installs `postgresql-client` + `gcc` in the runner via apt-get, applies `init.sql` to the service, and runs `clean deploy -P gitlab,local-db -Ddb.url=jdbc:postgresql://postgres:5432/rewrite_test`. Tests + codegen run; only the `MAVEN_SKIP_OPTS` variable that previously carried `-Djooq.codegen.skip=true` was removed.

The initial pass missed three things: several rewrite-reactor modules (`graphitron-sakila-service`, `graphitron-sakila-example`) compile against jOOQ-generated classes from `graphitron-sakila-db` (skipping codegen broke the reactor's compile phase regardless of which modules ultimately deploy); `graphitron-lsp` invokes a native build at `generate-resources` (`build-native.sh`) that needs `cc`, which the `maven:3.9-eclipse-temurin-25` runtime image doesn't ship; and `graphitron-sakila-example`'s `local-db` profile hardcoded `test.db.url=jdbc:postgresql://localhost:5432/...` as a literal in surefire's `systemPropertyVariables`, where CLI `-D` overrides can't reach it. The postgres-service path covers the first, an apt-get `gcc` covers the second, and parameterizing the surefire literal through pom properties covers the third.

## Risks and mitigations

- **`versions:set` half-applies.** Without `-DprocessAllModules=true`, only the aggregator pom updates and child modules keep `${revision}`; the deploy then publishes mismatched coordinates. Mitigation: the flag is included in the script above.
- **Mirror sync re-triggering publish.** With `publish:snapshot` removed and `publish:release` keyed on tags, default-branch pushes from the GitHub → GitLab mirror cannot trigger any deploy. The only deploy path is an explicit tag.
- **Legacy junk in GitLab Packages.** The `9-gitlab-SNAPSHOT` artifacts already deposited by the buggy job persist after this change; their removal is a manual UI step in Settings → Packages and registries (already called out under *Out of scope*).
- **JDK 25 image availability.** `maven:3.9-eclipse-temurin-25` is published on Docker Hub; confirm before merging by running `docker pull` (or letting the first pipeline run on the throwaway tag resolve it). Fallback if absent: an `openjdk:25-jdk` image plus Maven install in `before_script`, deferred until needed.
- **`postgres:18-alpine` image and apt-get reachability.** The publish job depends on the Postgres image being pullable by the GitLab runner and on `apt-get install postgresql-client` succeeding in the maven image. Both are routine, but a runner without outbound network for either layer would silently break the job at job-start time. The throwaway-tag verification below catches this.
- **init.sql drift.** The publish job applies `graphitron-sakila-db/src/main/resources/init.sql` to a freshly-started postgres service. If the schema ever drifts in a way that init.sql no longer cleanly applies (e.g. a partial migration committed without updating init.sql), the publish job fails at the psql step. That's a fail-fast property by design — the same drift would break local-db builds for every developer — but worth noting as the failure mode.
- **Native build toolchain.** `graphitron-lsp`'s `build-native.sh` invokes `cc` directly with C11 flags. The apt-get `gcc` install is enough for x86_64 Linux runners (which is what `maven:3.9-eclipse-temurin-25` is). The native lib is bundled into the deployed jar at `lib/linux-x86_64/`, so a runner that can't provide a working compiler doesn't produce a working artifact; the failure is loud (exit 127) rather than silent. The pom's `build-native-linux-x86_64` profile activates by OS detection, so this is the only target the GitLab pipeline produces — macOS / Windows native builds are not part of the GitLab publish path.

## Verification

- Confirm the GitLab runner pulls `maven:3.9-eclipse-temurin-25` successfully (a `docker pull` from a local Docker host suffices, or observe the pull step in the throwaway-tag pipeline run below).
- Push a throwaway tag (e.g. `v10.0.0-RC0`). Either route works: push to GitHub and let the mirror carry it (exercises the production path), or create the tag directly in GitLab (faster smoke test; the mirror may delete it on the next sync but the deposited package survives). Confirm `publish:release` fires and deploys all five expected coordinates to GitLab Packages at `10.0.0-RC0`:
    - `no.sikt:graphitron-rewrite-parent` (pom)
    - `no.sikt:graphitron-javapoet`
    - `no.sikt:graphitron`
    - `no.sikt:graphitron-maven-plugin`
    - `no.sikt:graphitron-lsp`
  The remaining rewrite-reactor modules (`graphitron-fixtures-codegen`, `graphitron-sakila-db`, `graphitron-sakila-service`, `graphitron-sakila-example`, `graphitron-roadmap-tool`, `graphitron-docs`) carry `maven.deploy.skip=true` and must NOT appear in the registry.
- Confirm a default-branch push does not trigger any publish job.
- Delete the throwaway tag and its package after the test.

## Closes

- Active publishing hazard surfaced while filing R182: GitLab Packages is currently being fed legacy `9-gitlab-SNAPSHOT` artifacts on every mirror sync.
