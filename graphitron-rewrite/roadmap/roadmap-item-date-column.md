---
id: R143
title: "Surface a date column on the rendered roadmap table"
status: Spec
bucket: cleanup
priority: 3
depends-on: []
---

# Surface a date column on the rendered roadmap table

## Problem

The rolled-up `graphitron-rewrite/roadmap/README.md` table (`Main.java`) shows ID, Item, Status, Plan, but no time dimension. A reader scanning Active cannot tell which Spec items have been sitting unsigned-off for weeks vs days, and the Backlog list gives no signal distinguishing recently-filed items from ones parked years ago. Both gaps push readers into `git log` per item for routine triage.

## Decisions

Three forks called out on the Backlog stub; the resolutions taken into Spec:

- **Semantics: last status transition.** A `last-updated:` front-matter key, stamped by the roadmap tool on every transition (and on initial create). Rationale: created-date stops being meaningful once an item enters Spec and lives there for months; due-date carries deadline connotations that misframe a no-deadline backlog; last-transition is the data point that directly maps to triage ("has this been moving?"). The key name is `last-updated:` rather than `last-transition:` because the tool also touches it on backfill and on any future "mass-edit" operation that bumps recency, not only on status flips.
- **Maintenance: tool-stamped, not author-typed.** Today the `roadmap` skill flips status by hand-editing front-matter (no `status` subcommand exists). This plan adds a `status` subcommand to `Main.java` that validates the transition, writes both `status:` and `last-updated:` atomically, and regenerates. The skill's docs flip from "edit `status:` in front-matter" to "invoke the subcommand". Hand-editing remains physically possible but loses tool support; the validator does not gate on it.
- **Render: new "Updated" column in Active; `<sub>` annotation in Backlog.** Active is a table; widening it to five columns keeps the data point scannable. Backlog is a bullet list; an inline `<sub>updated YYYY-MM-DD</sub>` (next to the existing `blocked by:` annotation) is the matching shape. Format `YYYY-MM-DD` everywhere.

## Phases

### Phase 1: front-matter key + parser

- Extend `Item` (`Main.java:1095-1127`) with `lastUpdated: LocalDate`.
- Parse `last-updated:` in `Item.from`; accept `null` during the migration window (Phase 4).
- After Phase 4 lands, tighten the validator (`Main.java:779`) to reject items missing `last-updated:` or carrying a non-`YYYY-MM-DD` value, mirroring the existing checks for `id:`.

### Phase 2: write paths in the tool

- **`create` subcommand** (`Main.java:222`): append `last-updated: <today>` to the front-matter StringBuilder alongside `id:` and `status: Backlog`.
- **New `status` subcommand**: `status <R<n>-or-slug> <new-state>`. Resolves slug, validates the transition against the same state table the skill documents, edits the file in place (`status:` and `last-updated:` both), regenerates the README. Reuses `parseFrontMatter` / front-matter serialisation already in use by `writeChangelogNextId`. The reviewer-rule guard ("reviewer ≠ last committer") remains the skill's job, not the tool's: the subcommand performs the mechanical edit; the human/agent gate happens before the call.
- **`backfill-last-updated` one-shot subcommand**: walks every item file, shells `git log -1 --date=short --format=%ad -- <relative-path>`, writes `last-updated:` to any file without it. Idempotent; re-running is a no-op once every file has the key. Used once during Phase 4.

### Phase 3: renderer

- **Markdown Active** (`renderActive`, `Main.java:896`): table header becomes `| ID | Item | Status | Updated | Plan |`; per-row cell is `i.lastUpdated().toString()`.
- **Markdown Backlog** (`appendBacklogLine`, `Main.java:1046`): append ` <sub>updated YYYY-MM-DD</sub>` after the title link and before any `blocked by:` annotation.
- **AsciiDoc Active** (`renderAdocStatusBoard`, `Main.java:329`): `[cols="1,4,1,1,1", ...]`, header row gains `Updated`, per-row emits the date cell.
- **AsciiDoc Backlog** (`appendBacklogAdocLine`, `Main.java:432`): same `_(updated YYYY-MM-DD)_` suffix shape.
- The plan-page attribute box (`renderAdocPlan`, `Main.java:510`) gains an `Updated` row.

### Phase 4: migration commit

In a single landing commit:

1. Run `backfill-last-updated` against the live roadmap directory.
2. Run `generate` to refresh `README.md`.
3. Tighten the validator (Phase 1 second bullet) so the new key becomes mandatory from this point on.

This keeps the migration window to a single commit; verify-mode CI on the very next push enforces the key on every item.

### Phase 5: skill and docs sweep

- `.claude/skills/roadmap/SKILL.md`: replace "flip `status:` in the front-matter, then run the guards and regenerate" with the `status` subcommand invocation; keep the reviewer-rule guard text intact.
- `graphitron-rewrite/docs/workflow.adoc`: mention the auto-stamp on transitions so the workflow doc and the skill agree.
- `README.md` intro paragraph in `Main.java:873` (rendered into `graphitron-rewrite/roadmap/README.md`): document `last-updated:` alongside the existing front-matter dimensions.

### Phase 6: tests

- `MainTest` (or sibling unit test class): cover `create` stamping today's date; cover `status` subcommand stamping today's date and rejecting invalid transitions; cover `backfill-last-updated` idempotency and skip-existing semantics.
- Renderer test: assert the new `Updated` column appears in both markdown and AsciiDoc Active output, and the `<sub>updated ...</sub>` annotation appears in Backlog output.
- Validator test: missing `last-updated:` fails; malformed value fails.

## Out of scope

- Relative-age display (`3 weeks ago`). The ISO date is enough for triage; computed relative ages would either need to be baked into the README (rotting on every regeneration) or rendered client-side (the README is static markdown).
- Auto-flipping `deferred:` based on staleness, or any other policy that reads `last-updated:` to drive behaviour. This item only surfaces the data point.
- Touching the `changelog.md` front-matter shape; closed items don't carry per-row dates in this scheme.
