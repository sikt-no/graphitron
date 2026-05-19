---
id: R183
title: "GitLab pipeline: drop snapshots, publish rewrite reactor on release tags"
status: Backlog
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
- `publish:release` runs `mvn -f graphitron-rewrite/pom.xml versions:set -DnewVersion=$VERSION -DgenerateBackupPoms=false -DprocessAllModules=true` followed by `mvn -f graphitron-rewrite/pom.xml ... clean deploy -P gitlab`.
- `publish:release` tag regex matches `^v\d+\.\d+\.\d+(-RC\d+)?$`, identical to the GitHub workflow on `claude/graphitron-rewrite`.
- `graphitron-rewrite/pom.xml` carries the `gitlab` profile (distribution-management + sources-jar, ported from the root pom). The root-pom copy can stay; it becomes inert without `-f` flag callers.
- Optional: a `release:` job using `release-cli` that creates the GitLab Release object attached to the tag (so "create a release in GitLab" works end-to-end, not just artifact publishing). If included, it depends on `publish:release` succeeding first.

## Out of scope

- Removing the legacy reactor or the root-pom `gitlab` profile. R182 collapses both as part of the unnest.
- Maven Central publishing. The GitHub workflow on `main` is a separate fix once R182 retires the legacy reactor; this item only touches GitLab.
- Cleanup of existing junk `9-gitlab-SNAPSHOT` artifacts already deposited in GitLab Packages. Manual UI task, called out for the implementer to do once the snapshot job stops firing.

## Verification

- Push a throwaway tag (e.g. `v10.0.0-RC0`) on a scratch branch, confirm `publish:release` fires and deploys `no.sikt.graphitron:graphitron:10.0.0-RC0` (and the other rewrite-reactor coordinates) to GitLab Packages.
- Confirm a default-branch push does not trigger any publish job.
- Delete the throwaway tag and its package after the test.

## Closes

- Active publishing hazard surfaced while filing R182: GitLab Packages is currently being fed legacy `9-gitlab-SNAPSHOT` artifacts on every mirror sync.
