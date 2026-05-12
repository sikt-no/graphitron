---
id: R143
title: "Surface a date column on the rendered roadmap table"
status: In Review
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

## Implementation

All five phases shipped in the In Progress → In Review commit:

- **Phase 1 (front-matter keys + parser)**: `Item` gains `created: LocalDate` and `lastUpdated: LocalDate`, both nullable; parsed in `Item.from` via `parseDate` which accepts both bare-string YAML values and SnakeYAML's auto-parsed `java.util.Date` shape (ISO `YYYY-MM-DD` values get auto-parsed; explicit-string values stay strings). A present-but-malformed value throws `IllegalArgumentException` at parse time naming the slug, key, and offending value. Absent passes.
- **Phase 2 (write paths)**: `runCreate` appends `created: <today>` and `last-updated: <today>` to the front-matter StringBuilder. New `runStatus` subcommand resolves a slug or `R<n>` via `resolveItemFile`, then delegates to the pure `applyStatusTransition` which validates target and transition against `TARGET_STATES` / `ALLOWED_TRANSITIONS`, writes the new `status:` value and a fresh `last-updated:`, and leaves `created:` strictly untouched (never invented). `Done` and `Discarded` are rejected as target states. Reviewer-rule guard stays the skill's job.
- **Phase 3 (renderer)**: Markdown `renderActive` table header becomes `| ID | Item | Status | Updated | Plan |` with cell content `last-updated:` (plus ` <sub>created ...</sub>` when the two dates differ). Markdown `appendBacklogLine` emits `<sub>updated Y-M-D</sub>` (or `<sub>updated Y-M-D, created Y-M-D</sub>`) between the description and any `blocked by:` annotation. AsciiDoc `renderAdocStatusBoard` becomes `[cols="1,4,1,1,1"]` with the new `Updated` column. AsciiDoc `appendBacklogAdocLine` emits italic `_(updated ...)_`. The plan-page attribute box gains `Created` and `Updated` rows, both suppressed when absent.
- **Phase 4 (docs sweep)**: `.claude/skills/roadmap/SKILL.md` rewritten to invoke the `status` subcommand instead of hand-editing front-matter. `graphitron-rewrite/docs/workflow.adoc` gains a bullet describing the auto-stamp under "Item file conventions". The `render` intro paragraph documents `created:` / `last-updated:` alongside the other front-matter dimensions, and notes that pre-R143 items render `last-updated:` only once they next transition and never get a `created:` backfill.
- **Phase 5 (tests)**: new `RoadmapDateColumnTest` (21 cases) covers `create` stamping both dates today, `status` stamping `last-updated:` and preserving `created:` (both present and absent shapes), rejection of invalid transitions and of `Done` / `Discarded` as targets, `resolveItemFile` accepting both slug and `R<n>`, all four renderer cells in both markdown and AsciiDoc (differing dates, matching dates, absent dates), the plan-page attribute box on both shapes, and the parser's "absent-passes, malformed-fails" semantics. `AdocLinkPrefixTest`'s `Item` factory updated for the two new constructor parameters.

### Notes for the reviewer

- The `applyStatusTransition` method is package-visible so tests can exercise the rejection paths without going through `Main.main` (which calls `System.exit`). The error-message strings are pinned by tests; they're also user-facing CLI output.
- R143 itself remains a "pre-R143" item: no `created:` value, and `last-updated:` will only land on its next status transition (so its row in the rendered table currently shows an empty `Updated` cell). This is the documented legacy behaviour, not an oversight; backfilling R143 would activate the `<sub>created ...</sub>` annotation with a date that's not the item's filing date.
- The Spec's note about Spec → Spec revise stamping `last-updated:` is enforced: `ALLOWED_TRANSITIONS` permits `Spec -> Spec`, and `applyStatusTransition` writes a fresh `last-updated:` every call. Reviewers who run the subcommand for a revise will see the date bump; reviewers who edit the file directly will not (consistent with the Decisions section's "the tool stamps; bypassing the tool means no stamp" framing).

## Out of scope

- Relative-age display (`3 weeks ago`). The ISO date is enough for triage; computed relative ages would either need to be baked into the README (rotting on every regeneration) or rendered client-side (the README is static markdown).
- Auto-flipping `deferred:` based on staleness, or any other policy that reads the new keys to drive behaviour. This item only surfaces the data points.
- Touching the `changelog.md` front-matter shape; closed items don't carry per-row dates in this scheme.
- Forcing uniformity on pre-R143 items via a backfill subcommand, or stamping a synthetic `created:` on first transition. Pre-R143 items get `last-updated:` (the first time they next transition) and never get `created:`; the column-non-uniformity is permanent and is itself a readable "this is a pre-R143 item" signal.
