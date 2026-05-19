---
id: R182
title: "Retire legacy reactor and unnest graphitron-rewrite to repo root"
status: Backlog
bucket: structural
theme: legacy-migration
depends-on: [retire-maven-plugin]
created: 2026-05-19
last-updated: 2026-05-19
---

# Retire legacy reactor and unnest graphitron-rewrite to repo root

Carved out from R26's "Retire legacy + unnest the rewrite aggregator" sub-item so the repo-topology change has its own Spec and review trail.

Today the rewrite reactor lives nested under `graphitron-rewrite/`; the repo root still hosts the legacy `graphitron-parent` reactor (`graphitron-codegen-parent`, `graphitron-common`, `graphitron-example`, `graphitron-maven-plugin`, `graphitron-schema-transform`, `graphitron-servlet-parent`). This is operationally load-bearing in surprising ways: GitHub's `release: created` event always loads workflow files from the default branch, so any release tag, on any branch, runs the `main`-branch `maven-publish.yml`, which builds the root reactor (legacy). A `v10.0.0` tag cut from a rewrite-branch commit would republish the legacy artifacts at 10.0.0, not the rewrite reactor. The rewrite-branch publish workflow that knows about `-f graphitron-rewrite/pom.xml` and accepts `-RC<n>` suffixes is unreachable from a release event. Unnesting collapses the surface: one root reactor, one publish workflow on `main`, no `-f` flag, no nested-vs-root ambiguity.

## Trigger condition (gating)

Every legacy consumer must be migrated to the new plugin before this lands; cadence is dictated by per-consumer feature work. Until then, this stays in Backlog. Promoting prematurely strands consumers on a deleted reactor.

## End state

- `graphitron-rewrite/`'s aggregator POM becomes the repo root POM.
- Modules relocate up one level: `graphitron-javapoet`, `graphitron`, `graphitron-fixtures-codegen`, `graphitron-sakila-db`, `graphitron-sakila-service`, `graphitron-maven`, `graphitron-sakila-example`, `graphitron-lsp`, `roadmap-tool`.
- The two parent POMs (top-level `graphitron-parent` and `graphitron-rewrite-parent`) merge into a single root parent.
- The duplicated `graphitron-javapoet` becomes the only copy.
- `.github/workflows/maven-publish.yml` on `main` drops the `-f graphitron-rewrite/pom.xml` flag and adopts the RC-aware tag regex from the rewrite-branch workflow. `rewrite-build.yml`, `preview-docs.yml`, and any path filters lose their `graphitron-rewrite/` prefixes.
- `docs/` and `graphitron-rewrite/docs/` consolidation: pick one location; the AsciiDoctor site config follows.
- `CLAUDE.md` "Scope" rule (legacy modules out-of-scope) is deleted; the whole repo is in scope again.
- `verify-standalone-build.sh` either retires or repurposes (no legacy artifacts exist to leak).

## Concrete steps

1. Confirm gating: every legacy consumer migrated, signed off by user.
2. Single squash commit, repo-topology change in isolation: delete legacy modules and root `pom.xml`; `git mv` the contents of `graphitron-rewrite/` up; merge the two parent POMs; dedupe `graphitron-javapoet`.
3. Update workflows (`maven-publish.yml`, `rewrite-build.yml`, `preview-docs.yml`, any others) to drop the `-f` flag and the `graphitron-rewrite/` path prefixes.
4. Update docs: `CLAUDE.md` (scope section, common commands), `.claude/web-environment.md`, `graphitron-rewrite/docs/README.adoc`, and the AsciiDoctor site.
5. Update the roadmap tool's path defaults if any are hardcoded (`graphitron-rewrite/roadmap` → `roadmap`).
6. Cut a release tag (`v10.0.0` or the next planned RC) to verify the consolidated publish workflow end-to-end against Maven Central staging.

## Risks and mitigations

- **Git history navigation.** Renaming the directory and merging POMs muddies `git log -- <path>` for old paths. Mitigation: do the move in a single dedicated commit so a `git log --follow` boundary is clear, and document the pre-move SHA in `changelog.md`.
- **Consumer migration incomplete.** Mitigated by the gating condition above; do not begin work until user confirms.
- **CI path filters.** Workflows that trigger only on `graphitron-rewrite/**` will silently stop firing if the path filter isn't updated; audit all workflow `paths:` keys before merging.
- **Publish-workflow regression.** The `main` workflow on the day of the move must accept both the legacy `v<MAJOR>.<MINOR>.<PATCH>` and the rewrite `-RC<n>` form; test against a staging tag before the first real release.

## Closes

- R26 sub-item "Retire legacy + unnest the rewrite aggregator" (its closing landing marker). On Done, edit R26 to remove that bullet or mark it shipped.
- The `release`-event publish hazard: legacy artifacts can no longer be cut at 10.0.0 because there is no legacy reactor to build.
