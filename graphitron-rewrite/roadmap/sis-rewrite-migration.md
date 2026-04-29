---
title: "sis-graphql-spec migration to graphitron-rewrite"
status: Backlog
bucket: cleanup
priority: 13
theme: legacy-migration
depends-on: []
---

# sis-graphql-spec migration to graphitron-rewrite

Track the consumer-side schema work needed to bring `sis-graphql-spec` cleanly onto graphitron-rewrite. This plan exists because sis is the canonical large-scale consumer; closing it out validates the rewrite's classification contracts end-to-end and lets us close courtesy windows on shims (notably [`retire-synthesis-shims`](retire-synthesis-shims.md), which gates on this work).

The earlier blocker, ~200 sis event types colliding on `__NODE_TYPE_ID = "195"` after silent promotion to `NodeType`, has been resolved upstream by retiring the type-level shim in `TypeBuilder.buildTableType`: a `@table` SDL type without `implements Node @node` is now a `TableType` regardless of metadata. The latest sis build against rewrite confirms no typeId-collision errors remain. The residual work splits into three independent phases plus one upstream bucket.

## Phase 1: declare `@nodeId` on every Relay-style ID field

The two surviving field-level synthesis shims still fire today on bare scalar `ID` fields whose context implies they should be Relay node IDs:

- `FieldBuilder` Path-2: output `ID` on a `NodeType` parent (parent type `implements Node @node`).
- `BuildContext.classifyInputField`: input `ID` on a `@table` input whose backing jOOQ class carries `__NODE_TYPE_ID` / `__NODE_KEY_COLUMNS`.

Each fires a per-occurrence WARN. The roadmap item [`retire-synthesis-shims`](retire-synthesis-shims.md) flips the WARN to a terminal classifier error once consumer schemas declare `@nodeId` explicitly.

For every WARN line in the sis build log:

1. Open the SDL field it names.
2. Add `@nodeId` to the field declaration.
3. If the field's parent type isn't already `implements Node @node`, decide whether it should be (the WARN trigger condition implies it usually should), and add it.

Done when the sis build log contains zero `@nodeId synthesis` WARNs. At that point the upstream shim retirement can land safely.

Estimate: ~250+ fields per the latest build; mechanical, and ideally split across PRs by logical type group.

## Phase 2: annotate filter inputs with `@table`

~25 filter input types currently fail with "table … could not be resolved". Filter inputs need `@table(name: "…")` so the generator knows which jOOQ table's columns the filter can target.

For each error in the build log:

1. Identify the filter input type (typically `*Filter` or `*Where`).
2. Add `@table(name: "<jooq-table-name>")` matching the table the filter targets.
3. Verify the filter's fields all reference columns that exist on that table.

Done when no "table … could not be resolved" errors remain for filter input types.

Estimate: ~25 types; straightforward once the table mapping is known.

## Phase 3: clean up author-error directives

A handful of types declare `@node` or `@nodeId` but lack the corresponding metadata (no `id` column, missing `keyColumns`, etc.). These are pre-existing schema bugs surfaced more loudly now that the type-level shim no longer papers over them.

For each remaining error:

1. Determine intent: was this meant to be a `NodeType`, a `TableType`, or neither?
2. Either add the missing metadata or remove the directive that doesn't apply.

Done when the build log contains only deferred-feature errors (Phase 4) and no author errors.

Estimate: small, case-by-case.

## Phase 4 (out of scope, tracked upstream): deferred features

Remaining errors flag features the rewrite hasn't implemented yet. These are graphitron-rewrite roadmap items, not sis migration work. Track them so the migration owner knows what's blocking final green; do not attempt schema workarounds.

## Sequencing

Recommended order:

1. **Phase 2** first: smallest, unblocks the most types from `UnclassifiedType`.
2. **Phase 3** next: small, removes noise.
3. **Phase 1** last: largest, mechanical, splittable across PRs.

Phase 4 is parallel work upstream, not on the sis side.

## Verification per phase

After each phase, rerun the sis build against graphitron-rewrite and diff WARN/ERROR counts by category against the previous run. Each phase should drive its target category to zero without introducing new errors in other categories.

## History

The original blocker was the `TypeBuilder.buildTableType` shim that silently promoted any `@table` SDL type whose backing jOOQ class carried `__NODE_TYPE_ID` / `__NODE_KEY_COLUMNS` to `NodeType`, even without `implements Node @node`. In sis, ~200 event types shared `__NODE_TYPE_ID = "195"` and were promoted in lockstep, then symmetrically demoted to `UnclassifiedType` by the registry-uniqueness check. That shim has been retired; `@table` without `implements Node @node` is now a `TableType` regardless of metadata, and field-level synthesis only applies inside types that opt in via `implements Node @node`. The remainder of this plan tracks the consumer-side schema cleanups that the type-level shim used to obscure.
