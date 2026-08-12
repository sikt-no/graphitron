---
id: R630
title: "The fact architecture's durable documentation home"
status: In Review
bucket: docs
priority: 2
theme: docs
depends-on: []
created: 2026-08-11
last-updated: 2026-08-12
---

# The fact architecture's durable documentation home

## Problem

The fact-oriented architecture has shipped (the store per R595, the claim strata per R589, the
command-driven emit per the R549/R563 programmes), but the documentation agents orient from still
describes the retired architecture, and the discipline that keeps the new one debt-free lives in
transient space:

- `docs/architecture/explanation/pipeline-overview.adoc` describes the pre-pivot pipeline
  (parse, classify, validate, emit) and states "classification is the only place directives are
  read", which is no longer the shipped shape. Capture, the store, the `intent_` strata,
  violations-as-facts, and the command layer do not appear. An agent orienting from it gets the
  architecture we dissolved.
- The model's *why* lives in R333's body, a roadmap item. Roadmap space is transient by this
  repo's own conventions (items delete on Done, ids are non-citable from durable artifacts), and
  R333 itself names `docs/architecture/` as the eventual home of its stabilized content. The
  strangler window is exactly when agents most need that content, and today the only path to it
  is knowing a 2,200-line roadmap file exists.
- The `principles-architect` agent (the forward voice consulted while drafting specs) reads
  `graphitron-principles.adoc`, `development-principles.adoc`, and the architecture index. None
  of its sources state the fact-model discipline: base fact versus derived view, authored and
  inferred as separate relations coalesced by views, joined-not-stored, definition-keyed
  authored facts versus use-keyed derived bindings, new facts land only in the store while the
  transitional surface drains. Those are precisely the rules whose violations accumulate as
  technical debt during the migration window, and no agent-facing doc names them.
- The fact catalog's *what* is the DDL (`graphitron-model.sql`), by R333's explicit division of
  labour, and the DDL is already self-describing: every one of its relations and columns carries
  a `COMMENT ON` (1,019 of them at writing), per the header's stated convention. But nothing
  renders that reference surface; a human reading the docs site cannot see the schema that is
  now the model of record.

## Decisions taken (the frame the slices execute)

- **The fact catalog stays DDL-only.** No hand-authored reference page mirrors the relation
  list; two homes for the *what* is the drift this pivot just finished eliminating. The
  reference documentation is **generated** from the DDL's own `COMMENT ON` text, so it cannot
  drift from the schema by construction.
- **Comments are the reference prose, and may use AsciiDoc.** The `COMMENT ON` convention is
  already universal in the DDL; this item promotes it to the documentation source. Comment text
  may use AsciiDoc inline syntax (monospace, emphasis, lists); the doc generator interpolates it
  into the rendered pages, and asciidoctor, already in the docs build, is the only renderer
  involved. AsciiDoc over markdown because the site is AsciiDoc: staying in one markup removes
  the conversion boundary and its escaping (the repo does render markdown elsewhere, so this is
  a boundary-count argument, not a capability one). Adoption rewrites nothing: measured against
  the live corpus, zero of the 1,019 comments contain a backtick, the single unpaired
  constrained-emphasis opener renders literally, and no comment mis-renders. This keeps the
  reference documentation in the same file as the model, reviewed in the same diff.
- **The explanation/reference split follows Diátaxis, and the division of labour is R333's.**
  The generated reference owns the *what* (relations, columns, keys, constraints, their
  comments). A new authored explanation page owns the *why* (the modeling discipline, the
  invariants, the arguments). Where they disagree, the DDL wins, same as R333 states today.
- **The two authored pages split by role, stated so the second to land cannot duplicate the
  first.** The pipeline overview owns *stages and their order*: what runs when, orientation, one
  paragraph per stage, an xref where rationale lives. The fact-model page owns *the modeling
  discipline and its invariants*. Slice 2 deletes from the pipeline overview every sentence it
  takes over, replacing it with an xref.
- **Only shipped-and-enforced content migrates to durable pages.** A claim moves out of R333
  when it is true of the shipped store *and* can name its enforcer (the live test or gate that
  fails when it breaks). Target-state claims stay in R333, which carries a `status:` telling the
  reader it is a plan; a durable page asserting the destination as the state would be misleading
  in exactly the way the current pipeline overview is.
- **Prose in `COMMENT ON`; structure in meta-relations, under the `meta_` prefix.** The
  reference's *prose* is the comment text, bound to its object by the engine. The reference's
  *skeleton*, what the renderer needs beyond prose (the family definitions, their titles and
  page order, any grouping of relations within a page), is authored as rows in meta-relations in
  the same DDL file, starting with `meta_family` (prefix as the key, a rendered title, an
  ordinal, an AsciiDoc definition). Parsing structure out of comment prose is rejected; so is
  hardcoding it in the generator. The family-definition prose migrates from the DDL's header
  comment into those rows (one home); the header keeps the conventions and rationale that are
  about the schema as a whole. The prefix is `meta_`, a new family, by the header's own naming
  rule (a family is named for whose vocabulary its rows are written in): these rows are the
  schema describing itself, not the store's runtime record of what it read, so `store_` would be
  the wrong home. The `meta_` family describes itself like any other: `meta_family` carries its
  own family row. One relation is deliberately prefix-less today: the `diagnostic` read surface,
  whose own comment carries the no-family argument in prose precisely because no gate could say
  it mechanically. The meta stratum gives that case a home a prefix key cannot: a sibling
  exemption relation (working name `meta_prefixless_relation`: relation name as the key, the
  page that renders it, a reason), in the exemption polarity the schema gates already use, so a
  new prefix-less relation fails the gate until it carries an authored row.
- **One catalog reader, many views.** Everything that needs "what relations exist" (the doc
  generator, the drift guard) reads it from one place: the booted store's metadata plus the
  meta-relations, exposed by `graphitron-model`. The page set and the guard's prefix set derive
  from that reader, never enumerated a second time in code or prose. The meta-relations are
  closed against the observed schema by bidirectional gates in the `FactSchemaGateTest` family:
  every observed relation prefix has a family row, every family row has at least one observed
  relation, every prefix-less relation has an exemption row, ordinals are unique. (The DDL
  header's prose taxonomy has already drifted twice over: its "Ten families" count misses the
  `intent_` stratum it describes below, and the prefix-less `diagnostic` view fits no prefix at
  all; rows plus gates make both classes of drift impossible.)
- **One item, ordered slices.** The slices below are independently landable and ordered by
  priority; each is a full vertical (content plus any guard it needs).

## Slices, by priority

### Slice 1: rewrite the pipeline overview

Shipped at `d37e9b6`. `docs/architecture/explanation/pipeline-overview.adoc` now describes the
shipped pipeline (capture, the derived strata, planning, the render shell fold, the unchanged
writer contract, consumer compile) with the classification walk named transitional throughout;
the sibling pages the slice audited were retitled or repointed rather than rewritten.

### Slice 2: the fact-model explanation page

Shipped at `f1b5eea`, provenance-amended at `07ac443`. `docs/architecture/explanation/fact-model.adoc`
carries the migrated *why* (key discipline, facts-not-leaves, provenance shapes, derived-reads-as-views,
one-base-many-views, the closed command graph), every claim naming its enforcer.
`development-principles.adoc`'s first axiom is restated in fact terms ("Decide once, at capture;
carry the decision and its provenance as facts") within `DocSizeBudgetTest`'s budget. R333's
migrated sections keep their headings with one-line xref bodies.

### Slice 3: retool the principles-architect and the reviewer taxonomy

Shipped at `e75d9dc`. `.claude/agents/principles-architect.md`'s reading list gained the slice 1
and slice 2 pages; both it and `.claude/skills/reviewer-prompt/SKILL.md`'s taxonomy gained the
six fact-discipline findings (leaf-where-a-fact-belongs, derivation-stored-where-a-view-belongs,
provenance-flattened, private-model, emit-vocabulary-entering-the-model, keying-axis-confusion).
The agent stayed read-only and verdict-free.

### Slice 4: generate the schema reference from the DDL comments

Shipped at `d5d6c32`. The `meta_` stratum landed as three views over row values, not the tables
the Decisions bullet proposed: `meta_family` (the roster, migrated out of the header),
`meta_prefixless_relation` (`diagnostic`'s exemption, page `NULL` meaning the index, since a
five-arm union claims no single family), `meta_relation_family` (the `INFORMATION_SCHEMA` census
joined against both). Views rather than tables keep the rows constant per DDL hash by
construction, so `StoreRefresh`, the partition gate and the warm census need no exemption for
them; `FactSchemaGateTest` gained four bidirectional roster gates instead of the FK the
Decisions bullet expected. `CommentRenderabilityGateTest` holds the accepted AsciiDoc subset
(narrower than Decisions; see Open questions) over every comment and meta value, scanned
totally. `StoreCatalog` (`graphitron-model`) is the one census reader; `SchemaReferencePages`
(`roadmap-tool`, not `docs`, so rendering code stays off the production classpath) renders one
page per family plus an index into docs staging at build, with non-vacuity floors. The render
binds into the docs module's base build before `check-adoc-xrefs`; output is never committed.

### Slice 5: drift guard for the authored pages

Shipped at `c7a7f1e`. `check-schema-identifiers` (`roadmap-tool`, bound at `verify`) resolves
every backtick-quoted identifier in `docs/architecture/**.adoc` that starts with an observed
family prefix against `StoreCatalog`, as a family, a relation, or a `relation.column` pair,
skipping verbatim blocks via the shared `InertSpans` block context. Landed together with slice 4
rather than standalone, since both needed the same reader.

## Open questions (to settle at Ready)

- **The accepted AsciiDoc subset.** Settled with slice 4, narrower than the Decisions bullet's
  "monospace, emphasis, lists": the gate (`CommentRenderabilityGateTest`, beside the coverage gate) accepts
  plain prose plus paired single-backtick monospace spans only. Emphasis is rejected outright,
  because a deliberate `_pair_` and the accidental-activation pair the gate exists to catch are
  mechanically indistinguishable; lists needed no rule, because a comment is one SQL literal on
  one physical line and the control-character rule pins that, so block content is impossible by
  construction. Attribute references, bracketed inline macros and autolinking URL schemes are
  rejected as live substitutions; the subset widens by deliberate gate edit, never by drift.
  `InertSpans` was evaluated and deliberately not reused: it polices *generated* AsciiDoc for
  markdown-sourced spans an emitter forgot to neutralize (a paired backtick span is its
  finding), while this gate polices *authored* AsciiDoc for subset membership (a paired
  backtick span is its accepted construct); the gate's javadoc carries the argument. The
  positive direction, accepted-therefore-renders, is held by the docs render's WARN gate over
  the generated pages.

## Acceptance

- The pipeline overview describes the shipped pipeline; the transitional surface is named as
  transitional; the pages named in slice 1's audit no longer state the pre-store pipeline as
  current (pages beyond the audit list are follow-up items, not this criterion's range).
- The fact-model page carries the migrated *why*, every claim on it shipped-and-enforced with
  its enforcer named; each migrated R333 section keeps its heading with a one-line xref body, so
  sibling items citing sections by name still resolve; the pipeline overview no longer carries
  the sentences the page took over; `development-principles.adoc`'s first axiom is restated in
  fact terms within its size budget.
- The principles-architect's reading list and the reviewer taxonomy name the new pages and the
  fact-discipline findings.
- The docs site renders the generated schema reference from the DDL comments, with page
  structure read from the meta-relations; regenerating after a comment or meta-row edit changes
  the output with no other action; the non-vacuity floors, the comment renderability gate, and
  the meta-relation totality gates fail the base build when violated.
- The drift guard fails the build on a renamed relation still named by an authored page, and
  reads the same catalog reader as the generator.

## Relationships

- **R333** (the Graphitron data model): this item executes the migration R333's Scope section
  reserves for itself ("the stabilized model migrates out: to `docs/architecture/`, or into the
  `graphitron-model` DDL"). R333 stays Ready and governing for content not yet migrated; its
  Done-and-delete condition comes closer with each slice-2 section landed.
- **R595** (the fact store, shipped): the DDL and its `COMMENT ON` convention are the substrate
  slice 4 renders. The one schema change this item requests is the documentation meta-relation
  stratum (the `meta_` family: the family relation, the prefix-less exemption relation, and
  their gates); no captured-fact relation changes.
- **R545** (the model owns no emit vocabulary): slice 3 encodes its boundary as a named finding
  so drafts get caught before they add to that debt.
- **R115** (capability catalog): adjacent knowledge-surface work; no dependency either way.
