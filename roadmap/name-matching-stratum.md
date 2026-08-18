---
id: R697
title: "Name matching is a stratum: side relations, match views, and folds nowhere else"
status: Spec
bucket: architecture
theme: classification-model
depends-on: []
created: 2026-08-17
last-updated: 2026-08-18
---

# Name matching is a stratum: side relations, match views, and folds nowhere else

## Problem

Matching an authored name against a catalog name is one concern, and today its ingredients are
restated across the views that need it instead of stated once in a layer of their own. Three
restatements, each an independent drift hazard:

- The case fold. Thirteen in-scope lines carry an inline `UPPER(`; `intent_column_match_claim`
  alone spells `UPPER(COALESCE(fb.name_ref, f.field_name))` four times (join arm, `matched_by`
  CASE, `ROW_NUMBER` ordering), and it is the view whose comment records a measured seventy-times
  regression from getting its shape wrong.
- The effective-name rule. "The name to resolve by is the `@field` binding where one decoded, else
  the field's own name" (`COALESCE(fb.name_ref, f.field_name)`) is written six times across two
  views, and its table-grain sibling `COALESCE(table_ref, type_name)` twice.
- The tier decision. `intent_field_reference_step_hop` computes which name tier matched twice,
  independently: once in its join predicate and again in its `key_matched_by` CASE. Two spellings
  of one resolution agree exactly until one of them changes.

The store's own layering doctrine (fact-model's "Resolutions layer among themselves") already
contains the fix and already ships one instance of it: `intent_spelled_table` is the single home of
the table-name rule, its rows are matches (one row per authored spelling meeting one candidate
`sql_table`, ambiguity as rows, arity as a column), and every consumer of a table binding sits on
top of it. The key and column namespaces never got that layer, which is why their rules leak into
the hop view and the classifier. This item completes the stratum.

A previous revision of this item proposed `GENERATED ALWAYS AS (UPPER(...))` columns on eleven base
relations instead. That treated the fold's spelling, not its location: the rule count stayed the
same, and the mechanism coupled this item to the capture-layer rewrite (R701), because the current
`FactSink.flush()` names every column and H2 rejects an insert naming a generated column. It also
over-read its doctrinal warrant: the `graphql_duplicate_declaration.coordinate` precedent blesses a
rendered key handed outward as a stable id, not an intermediate of a match predicate. The empirical
groundwork that revision produced is kept in the appendix; the mechanism is replaced.

## The design

Per namespace an authored name can resolve in (table names, key/constraint names, column names),
the matching stratum has up to four layers, all views, no DDL columns, no writer change:

1. **Authored side**: the spellings the graph writes, with the defaulting rule stated once and a
   folded twin column beside the authored form.
2. **Census side**: what the catalog offers. Where a namespace carries two names per entity (a
   column and a constraint each answer to a jOOQ name and a SQL name), a re-grain view turns the
   pair into one spelling column with a tier; a single-name namespace reads its `sql_` relation
   directly.
3. **Match view**: one row per (authored spelling, census row) pair that matches. Ambiguity is
   rows, arity and tier are columns, and no collapse happens here.
4. **Reductions**: the readers that key or collapse the matches (a binding per type, a claim per
   field), where each consumer's own rule (require one candidate, take the first by tier) lives.

The fold discipline follows from the layering: a fold is stated once per side of a match. A side
that is a named relation exposes its folded spelling as a view column, so the match predicate is
bare equality with no function on either side; a side that is a base `sql_` relation folds inline
in the match view, once. No fold appears anywhere outside the stratum.

## The relations

New and changed, in dependency order:

**`intent_field_effective_name`** (authored side, columns): `(graph_name, type_name, field_name,
effective_name, effective_name_folded, tier)`, one row per `graphql_field` row, total, with no
consumer's mask baked in; `tier` says whether a decoded `@field` binding answered or the field name
defaulted, in the shape `intent_resolved_field_claim.tier` established. Its two consumers scope
differently (`intent_field_accessor_hop` covers object and input fields, the column classifier only
leaf output fields under a resolved scope), which is exactly why the relation carries no guard of
either. The accessor hop matches Java member slots case-sensitively, so the unfolded column is the
primary one and the folded twin is visibly the SQL-matching column; the view comment owns that
asymmetry.

**`intent_type_table_spelling`** (authored side, tables): `(graph_name, type_name, spelling,
spelling_folded, tier)` over `graphitron_table`, the `COALESCE(table_ref, type_name)` rule stated
once. `intent_spelled_table`'s first union arm and `intent_bound_table`'s join both read it instead
of each restating the rule.

**`intent_key_spelling`** and **`intent_column_spelling`** (census side): re-grains of
`sql_constraint` and `sql_column`, keyed by the census key plus the folded spelling, one row per
name the entity answers to, with `matched_by` naming the tier (`SQL_NAME`, `JOOQ_NAME`). Built as
`UNION ALL` of the two tiers, no window, no graph column: a rule over the census in the
`intent_class_member_slot` shape, scoped by a consumer through `store_graph_source` like any other
source-keyed read. Not duplicates of their base relations: each re-grains two name columns into one
matching surface and adds the tier, and `intent_column_spelling` is the relation the LSP's
`CatalogColumns.isNamed` re-sources onto when its reads move to the store.

**`intent_spelled_key`** (match view): the key-reference analogue of `intent_spelled_table`, keyed
on the written spelling, population every `key_ref` the graph authors (union over
`graphitron_field_reference_step`, `graphitron_argument_reference_step`,
`graphitron_reference_for_step`, the same population honesty the table view states). Carries the
qualified-split rule (a spelling with a dot binds schema and name), the tier via
`intent_key_spelling`, the SQL-name-shadows-jOOQ-name rule (a jOOQ-tier row survives only where no
SQL-tier row matches the spelling in scope), `candidates`, and `matched_by`. Its comment states its
silences: no row means no key of that name in the graph's catalog scope, which is a different
silence from a jOOQ-tier match suppressed by shadowing. Extracting it now, with one reader, is not
premature: the rule already has two spellings inside that one reader, and two of the three
populations it covers have no resolution at all yet, so the argument-side work lands on this
relation instead of minting the rule's third copy.

**`intent_field_column_match`** (match view): one row per candidate column match at a field site:
the site's resolved table from `intent_field_column_scope`, the effective name from
`intent_field_effective_name`, the candidates from `intent_column_spelling`, joined on bare
equality over pre-folded columns. Carries `matched_by` and the column's ordinal; performs no
collapse. This is the relation an editor's "why did this not match" hover reads, which no current
relation can answer because the classifier collapses before anything else sees the candidates.

**Reductions rewired**: `intent_column_match_claim` becomes a thin collapse over
`intent_field_column_match` (first match in tier-then-ordinal order, exactly its current rule),
mirroring how `intent_bound_table` sits over `intent_spelled_table`; its output columns and
comments are unchanged, which is what keeps `ColumnMatchClaimTest` passing as the regression pin.
`intent_field_reference_step_hop` joins `intent_spelled_key` and drops both of its copies of the
tier decision. `intent_field_accessor_hop` joins `intent_field_effective_name` (unfolded column).

## The enforcer

"A rule stated once cannot drift from itself" is true of the rule and false of the confinement:
nothing yet stops the next author from writing `UPPER(` into a fourth view. So the stratum gets a
gate, a `FactSchemaGateTest` sibling reading `INFORMATION_SCHEMA.VIEWS`: no `UPPER(`/`LOWER(` in
any view definition outside the matching stratum. The allowed set is derived from the stratum's
naming convention (the `_spelling`, `spelled_`, `_match`, and effective-name views), not from a
hand-kept list, and the two legitimate non-matching folds are excluded by structural predicates
rather than by name: the `meta_` family by its roster prefix, and the accessor prefix grammar by
its single-character `LOWER(SUBSTRING(x, n, 1))` shape. This replaces the previous revision's
generation-expression gate, whose subject (generated columns) no longer exists in the plan.

## Performance constraints

The stratum stacks derived relations under the view carrying the measured seventy-times scar, so
the shape rules the neighboring comments already record are constraints here, not afterthoughts.
Match views and census re-grains carry no window function and no `DISTINCT`, so H2 can merge them
into their readers; collapse and arity counting live in reductions. Where a new view wants arity as
a column (the `candidates` precedent from `intent_bound_table`), the choice between a window and a
reader-side count is made by measurement and recorded in the view's comment. The rewritten
`intent_column_match_claim` keeps the derivation-first FROM-clause discipline its comment mandates,
and the rewrite is measured against the current shape on the same store the seventy-times figure
came from before it ships.

## Java restatements, and where they go

The `equalsIgnoreCase` census in the generator splits into two families, and only one belongs to
this stratum. Authored-name-meets-catalog sites (`JooqCatalog`'s table, key, and column lookups,
`TableRef.findColumn`, the LSP's `CatalogColumns.isNamed` and `CatalogKeys`, `TenantBindingIndex`'s
two-tier scope match) restate what the match views answer, and they retire as their consumers
re-source onto the store (R638 sequences that work; this item does not block it and is not blocked
by it). Catalog-name-vs-catalog-name sites, where both operands are already canonical and the fold
is a hedge rather than a semantic, will never be absorbed by any match view and should become exact
comparisons: that is R702's census and its own item.

## Out of scope

Unchanged from the previous revision: the single-character accessor-prefix fold is prefix grammar,
not name matching, and the `meta_` census views fold `INFORMATION_SCHEMA` columns that are not our
base relations. Both keep their inline folds and both are excluded from the gate structurally.

## Verification

The existing pipeline and capture-agreement tests are the harness, as before: real SDL and a real
catalog read through the rewritten views. Per-view anchors in `rewrite/derive` for each new
relation, in the `ColumnMatchClaimTest` pattern: hand-written expectations the view cannot produce
by construction, including the shadowing silence on `intent_spelled_key` and the case-sensitivity
asymmetry on `intent_field_effective_name`. `ColumnMatchClaimTest` itself passes unchanged, pinning
that the claim view's output is preserved through the rewiring. Plus the fold gate above, and the
merge measurement from the performance section.

## Appendix: the folded-column groundwork, kept for the future performance option

The previous revision ran a standalone rehearsal against jOOQ 3.20.11 OSS and H2 2.4.240 whose
results stay load-bearing for anyone reopening the stored-fold option: jOOQ codegen accepts a
`GENERATED ALWAYS AS` column silently (no readonly marking in OSS); the current
`insertInto(table).columns(table.fields())` flush shape fails loudly at H2 ("Generated column ...
cannot be assigned"); the record path (`record.insert()`, `batchInsert`) is safe; filtering the
column list works; and H2 names its generated columns in `INFORMATION_SCHEMA.COLUMNS`
(`IS_GENERATED`, `GENERATION_EXPRESSION`). `VARCHAR_IGNORECASE` was tested and rejected: the fold
reaches primary keys, so a quoted-identifier catalog legitimately declaring `"Title"` beside
`"title"` would silently lose a row through the shared-family `onDuplicateKeyIgnore`.

With matching concentrated into three views over a workspace-wide store, and H2 rejecting
expression indexes as a syntax error, stored folded columns on the three catalog relations become
more attractive under this design, not less. That option stays deferred behind two triggers, both
required: R701 landing (writers name their columns, so a generated column costs no writer change),
and a measurement showing a match view slow on a real workspace store. It is a performance move
with no correctness content once this item confines the rule, and no DDL comment may claim
otherwise.

## Care

Broad but view-only: it rewrites the bodies of the matching views and adds relations beside them,
so it conflicts with anything else editing those views, most concretely the language-server
recomposition (R638, In Progress), which is writing new reads against the same predicates.
Coordinate on ordering per view; neither item is a prerequisite of the other (R638 records that
decision), but landing `intent_spelled_key` before the argument-side resolution work exists is what
keeps the key rule at one spelling. New views need their comments in the same pass; the schema
gates fail an uncommented relation. The `meta_family` roster already houses the `intent_` family;
the new residents follow its two-layer placement note (base derivations, not reductions, except the
rewired claim view which stays a reduction).
