---
id: R509
title: "Bulk DML payloads follow input order, and warn where that cannot be guaranteed"
status: Backlog
bucket: architecture
priority: 5
theme: classification-model
depends-on: []
created: 2026-07-21
last-updated: 2026-07-21
---

# Bulk DML payloads follow input order, and warn where that cannot be guaranteed

R489 made bulk projected / discriminated mutation payloads align deterministically with the
RETURNING result (the `rows<Name>` companion's `VALUES (idx, pk)` join ordered by `idx`). That
closed the payload-vs-RETURNING half of the ordering story but left the RETURNING-vs-input half
implicit, and PostgreSQL does not guarantee RETURNING order in general. The product stance this
item encodes: bulk mutation payloads should match the *input* order wherever we can make that
true by construction, and every surface where we cannot must say so loudly (a build-time
warning and a manual sentence), never silently hand back database scan order.

Per-verb state at filing, and the direction for each:

- **Bulk UPDATE (projected / discriminated / encoded list returns).** The statement already
  builds its `VALUES ... AS v(...)` table from the input list in input order, and the bulk arm
  is already PostgreSQL-gated (`DialectRequirement.RequiresFamily(POSTGRES)`). PostgreSQL allows
  RETURNING to project FROM-list columns, so the fix is structural: thread an `idx` column
  through `v` and project `v.idx` alongside the primary key in the `RETURNING` clause
  (`TypeFetcherGenerator.emitKeysTransaction` grows an UPDATE-aware arm). The reentry companion
  then orders by the *carried* idx instead of the keys array position, and the encoded-list arm
  sorts its keys by idx before encoding. Input order becomes guaranteed by construction, with no
  reliance on RETURNING order at all. This deliberately touches the `RETURNING` shape R489 kept
  out of scope; the companion signature (`keys` element type widens by the idx slot on the bulk
  UPDATE arm) moves with it.
- **Bulk INSERT.** `INSERT ... VALUES ... RETURNING` has no FROM-list to thread an index
  through. PostgreSQL emits RETURNING rows in VALUES order for this statement shape in practice
  (single relation, no parallel insert), and R489's execution fixtures pin exactly that
  (`createFilms` / `createKeyedNodes` / `createContents` assert input order against a real
  database), but the PostgreSQL documentation does not formally promise it. Direction: keep the
  reliance, state it: the manual's ordering-contract sentence names INSERT order as pinned
  engine behavior on PostgreSQL, and the execution fixtures stay the enforcer. Evaluate (and
  likely reject as no-gain) the `INSERT ... SELECT ... ORDER BY idx` alternative, which moves
  the reliance rather than removing it.
- **Bulk DELETE with `[ID!]!` return (EncodedList).** Emits `DELETE ... WHERE row IN (...)`
  with the payload mapped straight off RETURNING, which reports rows in scan order: today this
  is a silent input-order mismatch (existing fixtures pass by ascending-PK accident). Two
  candidate fixes, to be decided at Spec: rewrite onto `DELETE ... USING (VALUES (idx, k...))`
  and project `v.idx` (PostgreSQL-specific, mirrors the UPDATE fix, costs the portable row-IN
  form), or reorder Java-side by matching RETURNING key tuples back to input positions (works
  when the lookup tuple is the primary key; UK-keyed inputs return a PK the input never
  carried, so that path either joins the UK through the re-read or falls back to warning).
  Whichever subset stays unguaranteed gets the warning below.
- **multiRow DELETE broadcast.** One input row deletes many database rows; there is no input
  order to restore beyond the per-input grouping. Permanent warning + manual note, not a fix.
- **Carrier-payload bulk path (`MutationBulkDmlRecordField`).** Per-row statements inside one
  transaction, keys collected in input order; already guaranteed and execution-pinned
  (`bulkInsertWithThreeRowsInNonPkOrderPreservesInputOrderInResponse`). No change.

The warning surface: a new lint rule (a `BuildWarning.LintFinding` slug in the `LintRule`
registry, covered by `LintRuleRegistryCoverageTest`) that fires at build time on any bulk
mutation field whose payload order the generator cannot guarantee to match input order, naming
the field, the reason, and the manual anchor. Silence is reserved for surfaces where the
guarantee holds by construction or by pinned engine behavior. The manual's
`reference/directives/mutation.adoc` ordering paragraph sharpens from "in the order the rows
were written" to a per-verb statement (INSERT: input order, pinned engine behavior; UPDATE:
input order by construction; DELETE / multiRow: whatever the warning says for that shape).

Acceptance sketch (firms up at Spec): execution fixtures proving bulk UPDATE payload order
equals a deliberately anti-PK-ordered input (the discriminating case R489 could not honestly
assert through GraphQL); the DELETE decision implemented or warned; the lint rule pinned with a
fixture per firing shape; R489's direct companion-seam duplicate-keys fixture updated if the
companion's idx source changes shape.
