---
id: R143
title: "Surface a date column on the rendered roadmap table"
status: Ready
bucket: cleanup
priority: 3
depends-on: []
---

# Surface a date column on the rendered roadmap table

## Problem

The rolled-up `graphitron-rewrite/roadmap/README.md` table (`Main.java`) shows ID, Item, Status, Plan, but no time dimension. A reader scanning Active cannot tell which Spec items have been sitting unsigned-off for weeks vs days, and the Backlog list gives no signal distinguishing recently-filed items from ones parked years ago. Both gaps push readers into `git log` per item for routine triage.

## Decisions

Four forks called out on the Backlog stub and during Spec drafting; the resolutions taken into Spec:

- **Two dates: `created:` and `last-updated:`.** `created:` is stamped once at create time and never touched again; `last-updated:` is stamped on every status transition (and on initial create, where it equals `created:`). Together they give "filed N months ago, last touched M weeks ago"; either alone is strictly less informative. The key names map to the operations that write them, not to triage semantics, so an item that never transitions naturally renders only one date.
- **Maintenance: tool-stamped, not author-typed.** Today the `roadmap` skill flips status by hand-editing front-matter (no `status` subcommand exists). This plan adds a `status` subcommand to `Main.java` that validates the transition, writes `status:` and `last-updated:` atomically, and regenerates. The skill's docs flip from "edit `status:` in front-matter" to "invoke the subcommand". Hand-editing remains physically possible but loses tool support; the validator does not gate on it.
- **Render: single combined column in Active; `<sub>` annotation in Backlog.** Active is a table; a new "Updated" column carries the `last-updated:` date on the main line and `<sub>created YYYY-MM-DD</sub>` underneath when the two differ. When they match (the item has never transitioned) only the one line renders. The table widens by one column rather than two. Backlog is a bullet list; an inline `<sub>updated YYYY-MM-DD</sub>` (next to the existing `blocked by:` annotation) is the matching shape, with the same one-line collapse when the dates agree. Format `YYYY-MM-DD` everywhere.
- **No backfill, no migration commit, no lazy stamp.** The renderer tolerates missing values (empty cell). New items stamp on create; pre-R143 items never get a `created:` value at all (including when they next transition), and the `status` subcommand only writes `last-updated:` even if `created:` is absent. The reasoning: stamping `created: <today>` on first transition would record a wrong date (the first-transition date, not the filed date) and silently activate the `<sub>created ...</sub>` annotation on subsequent transitions, misleading readers. Better to render only `last-updated:` for legacy items, honestly admitting we don't know when they were filed. The column is non-uniform forever for pre-R143 items, which is itself a readable signal. The validator stays permissive on the new keys — present-and-malformed fails, absent passes. If a real created date is ever wanted for a specific legacy item, hand-editing the front-matter remains physically possible; the tool just doesn't invent one.

## Phases

### Phase 1: front-matter keys + parser

- Extend `Item` (`Main.java:1095-1127`) with `created: LocalDate` and `lastUpdated: LocalDate`, both nullable.
- Parse `created:` and `last-updated:` in `Item.from`. Accept `null` for either (existing items have neither until they are next touched).
- Validator (`Main.java:779`): if a key is present, it must parse as `YYYY-MM-DD`; absent is permitted. No further gating.

### Phase 2: write paths in the tool

- **`create` subcommand** (`Main.java:222`): append `created: <today>` and `last-updated: <today>` to the front-matter StringBuilder alongside `id:` and `status: Backlog`.
- **New `status` subcommand**: `status <R<n>-or-slug> <new-state>`. Resolves slug, validates the transition against the same state table the skill documents, edits the file in place (writes the new `status:` value; writes `last-updated: <today>`; leaves `created:` strictly untouched — never invented, never overwritten), regenerates the README. Accepts only the five persisting statuses (`Backlog`, `Spec`, `Ready`, `In Progress`, `In Review`); `Done` and `Discarded` are file-deletion transitions per workflow.adoc and remain manual. Reuses `parseFrontMatter` / front-matter serialisation already in use by `writeChangelogNextId`. The reviewer-rule guard ("reviewer ≠ last committer") remains the skill's job, not the tool's: the subcommand performs the mechanical edit; the human/agent gate happens before the call.

### Phase 3: renderer

- **Markdown Active** (`renderActive`, `Main.java:896`): table header becomes `| ID | Item | Status | Updated | Plan |`. Cell content is the `last-updated:` date; if `created:` differs, append ` <sub>created YYYY-MM-DD</sub>` on the same cell. If `last-updated:` is absent, the cell renders empty.
- **Markdown Backlog** (`appendBacklogLine`, `Main.java:1046`): append ` <sub>updated YYYY-MM-DD</sub>` after the title link and before any `blocked by:` annotation. If `created:` differs and is present, the sub-annotation becomes `<sub>updated Y-M-D, created Y-M-D</sub>`. Absent dates render no annotation.
- **AsciiDoc Active** (`renderAdocStatusBoard`, `Main.java:329`): `[cols="1,4,1,1,1", ...]`, header row gains `Updated`, per-row emits the same `last-updated: + (created ...)` shape using AsciiDoc line-break syntax.
- **AsciiDoc Backlog** (`appendBacklogAdocLine`, `Main.java:432`): same `_(updated YYYY-MM-DD)_` / `_(updated ..., created ...)_` suffix.
- The plan-page attribute box (`renderAdocPlan`, `Main.java:510`) gains `Created` and `Updated` rows, each suppressed when absent.

### Phase 4: skill and docs sweep

- `.claude/skills/roadmap/SKILL.md`: replace "flip `status:` in the front-matter, then run the guards and regenerate" with the `status` subcommand invocation; keep the reviewer-rule guard text intact.
- `graphitron-rewrite/docs/workflow.adoc`: mention the auto-stamp on transitions so the workflow doc and the skill agree.
- `README.md` intro paragraph in `Main.java:873` (rendered into `graphitron-rewrite/roadmap/README.md`): document `created:` / `last-updated:` alongside the existing front-matter dimensions, and note that pre-R143 items render `last-updated:` only once they next transition; `created:` is never backfilled.

### Phase 5: tests

- `MainTest` (or sibling unit test class): cover `create` stamping both dates today; cover `status` subcommand stamping `last-updated:` today and leaving `created:` strictly untouched in both shapes (present-with-old-value, absent); cover rejection of invalid transitions and rejection of `Done` / `Discarded` as target states.
- Renderer test: assert the new `Updated` column appears in both markdown and AsciiDoc Active output; assert the combined `updated ..., created ...` shape when the dates differ and the collapsed shape when they match; assert items without dates render an empty cell with no annotation.
- Validator test: present-but-malformed date fails; absent date passes.

## Out of scope

- Relative-age display (`3 weeks ago`). The ISO date is enough for triage; computed relative ages would either need to be baked into the README (rotting on every regeneration) or rendered client-side (the README is static markdown).
- Auto-flipping `deferred:` based on staleness, or any other policy that reads the new keys to drive behaviour. This item only surfaces the data points.
- Touching the `changelog.md` front-matter shape; closed items don't carry per-row dates in this scheme.
- Forcing uniformity on pre-R143 items via a backfill subcommand, or stamping a synthetic `created:` on first transition. Pre-R143 items get `last-updated:` (the first time they next transition) and never get `created:`; the column-non-uniformity is permanent and is itself a readable "this is a pre-R143 item" signal.
