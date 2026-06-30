---
id: R182
title: "Retire legacy reactor and unnest graphitron-rewrite to repo root"
status: Spec
bucket: structural
theme: legacy-migration
depends-on: [retire-maven-plugin]
created: 2026-05-19
last-updated: 2026-06-30
---

# Retire legacy reactor and unnest graphitron-rewrite to repo root

Carved out from R26's "Retire legacy + unnest the rewrite aggregator" sub-item so the repo-topology change has its own Spec and review trail.

Today the rewrite reactor lives nested under `graphitron-rewrite/`; the repo root still hosts the legacy `graphitron-parent` reactor (`graphitron-codegen-parent`, `graphitron-common`, `graphitron-example`, `graphitron-maven-plugin`, `graphitron-schema-transform`, `graphitron-servlet-parent`). This is operationally load-bearing in surprising ways: GitHub's `release: created` event always loads workflow files from the default branch, so any release tag, on any branch, runs the `main`-branch `maven-publish.yml`, which builds the root reactor (legacy). A `v10.0.0` tag cut from a rewrite-branch commit would republish the legacy artifacts at 10.0.0, not the rewrite reactor. The rewrite-branch publish workflow that knows about `-f graphitron-rewrite/pom.xml` and accepts `-RC<n>` suffixes is unreachable from a release event. Unnesting collapses the surface: one root reactor, one publish workflow on `main`, no `-f` flag, no nested-vs-root ambiguity.

## Trigger condition (gating)

Every legacy consumer must be migrated to the new plugin before this lands; cadence is dictated by per-consumer feature work. Promoting prematurely strands consumers on a deleted reactor.

**Gate cleared (2026-06-30):** the migration milestone has been reached and the user has signed off on retiring the legacy reactor. The item moves into the active pipeline.

## End state

- `graphitron-rewrite/`'s aggregator POM becomes the repo root POM.
- Modules relocate up one level: `graphitron-javapoet`, `graphitron`, `graphitron-fixtures-codegen`, `graphitron-sakila-db`, `graphitron-sakila-service`, `graphitron-maven`, `graphitron-sakila-example`, `graphitron-lsp`, `roadmap-tool`.
- The legacy `graphitron-parent` reactor is gone, so the sole remaining parent (`graphitron-rewrite-parent`) is promoted to the root POM; no two-way POM merge is needed once the legacy root is deleted first.
- The duplicated `graphitron-javapoet` becomes the only copy (the legacy copy is already gone after the delete commit).
- `.github/workflows/maven-publish.yml` on `main` drops the `-f graphitron-rewrite/pom.xml` flag and adopts the RC-aware tag regex from the rewrite-branch workflow. `rewrite-build.yml`, `preview-docs.yml`, and any path filters lose their `graphitron-rewrite/` prefixes.
- `docs/` and `graphitron-rewrite/docs/` consolidation: pick one location; the AsciiDoctor site config follows.
- `CLAUDE.md` "Scope" rule (legacy modules out-of-scope) is deleted; the whole repo is in scope again.
- `verify-standalone-build.sh` either retires or repurposes (no legacy artifacts exist to leak).

## Concrete steps

Two commits, not one. The deletion and the rename are separated so the rename commit gives `git log --follow` a clean boundary; we are **not** routing this through an R19-style history squash (R19 is abandoned).

1. Confirm gating: every legacy consumer migrated, signed off by user. **Cleared 2026-06-30.**
2. **Commit 1 — delete the legacy reactor.** `git rm -r` the six legacy modules (`graphitron-codegen-parent`, `graphitron-common`, `graphitron-example`, `graphitron-maven-plugin`, `graphitron-servlet-parent`, `graphitron-schema-transform`) and the root `pom.xml` (its `<modules>` list is exactly those six). Delete `.github/workflows/maven-build.yml` in the same commit: it runs `mvn --file pom.xml` against the legacy root reactor on every push/PR to `main` and would fail the required `build` check the moment the root POM is gone (`rewrite-build.yml` already covers the rewrite reactor). `graphitron-rewrite/` stays nested and `docs/` stays put; both still build via `-f graphitron-rewrite/pom.xml`.
3. **Commit 2 — unwrap.** `git mv` the contents of `graphitron-rewrite/` up to the repo root; promote `graphitron-rewrite-parent` to the root POM (fix `relativePath`s; no two-way merge needed since the legacy parent is already deleted); the surviving `graphitron-javapoet` is now the only copy. Keep this commit isolated.
4. Update workflows and CI to drop the `-f graphitron-rewrite/pom.xml` flag and `graphitron-rewrite/` path prefixes: `maven-publish.yml`, `rewrite-build.yml`, `preview-docs.yml`, `.gitlab-ci.yml`, and every workflow `paths:` filter. (`.gitlab-ci.yml` already targets the rewrite reactor, so this is a path-prefix edit, not a legacy-removal.)
5. Update docs and tooling paths: `CLAUDE.md` (scope section, common commands), `.claude/web-environment.md`, the `.claude/skills/*` and roadmap-tool path defaults (`graphitron-rewrite/roadmap` → `roadmap`), `graphitron-rewrite/docs/README.adoc`, and the AsciiDoctor site.
6. Cut a release tag (`v10.0.0` or the next planned RC) to verify the consolidated publish workflow end-to-end against Maven Central staging. Note the release-event publish workflow is governed by `main`'s copy of `maven-publish.yml`, so the hazard only closes once this lands on `main`.

## Verified facts (2026-06-30)

- Root `pom.xml` `<modules>` is exactly the six legacy folders; it no longer lists `graphitron-rewrite` or `docs`.
- `docs/` already parents to `graphitron-rewrite-parent` and is wired in as `<module>../docs</module>` of the rewrite reactor, so deleting the root POM does **not** orphan it.
- `graphitron-rewrite/pom.xml` has no `<parent>` (standalone); no rewrite POM depends on any of the six legacy modules. The delete is safe for the rewrite build.
- `.github/workflows/maven-build.yml` is the legacy build (`mvn --file pom.xml`, JDK 21, matrix over `graphitron-example-server`); it must be deleted with commit 1.

## Risks and mitigations

- **Git history navigation.** Renaming the directory and merging POMs muddies `git log -- <path>` for old paths. Mitigation: do the move in a single dedicated commit so a `git log --follow` boundary is clear, and document the pre-move SHA in `changelog.md`.
- **Consumer migration incomplete.** Mitigated by the gating condition above; do not begin work until user confirms.
- **CI path filters.** Workflows that trigger only on `graphitron-rewrite/**` will silently stop firing if the path filter isn't updated; audit all workflow `paths:` keys before merging.
- **Publish-workflow regression.** The `main` workflow on the day of the move must accept both the legacy `v<MAJOR>.<MINOR>.<PATCH>` and the rewrite `-RC<n>` form; test against a staging tag before the first real release.

## Closes

- R26 sub-item "Retire legacy + unnest the rewrite aggregator" (its closing landing marker). On Done, edit R26 to remove that bullet or mark it shipped.
- The `release`-event publish hazard: legacy artifacts can no longer be cut at 10.0.0 because there is no legacy reactor to build.
