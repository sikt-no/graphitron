---
id: R630
title: "The fact architecture's durable documentation home"
status: In Progress
bucket: docs
priority: 2
theme: docs
depends-on: []
created: 2026-08-11
last-updated: 2026-08-11
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

Rewrite `docs/architecture/explanation/pipeline-overview.adoc` to the shipped pipeline: capture
(total transcription into the store's base relations: `graphql_` / `graphitron_` / `sql_` /
`jvm_`, with `store_` bookkeeping), the derived strata (the `intent_` claim views and
materialized derivations; validation deriving located violation facts), planning joining facts
into command relations (launcher, condition, projection, fetcher-edge) that the render shell
folds over, the writer's idempotency contract (unchanged), and the consumer compile. Name the
stage verbs (classification gathers, validation derives, planning joins) and the strangler
frame: `GraphitronSchema` and the leaf classifier are the transitional producer surface, live
until each consumer migrates onto the store, and new facts land only in the store. The page must
mark the transitional path as transitional so a reader cannot mistake it for the destination.

The slice also audits the sibling pages that state the retired shape as current, so the
acceptance criterion ranges over pages the slice actually touched:
`reference/code-generation-triggers.adoc` (its diagram still renders "GraphitronSchemaBuilder
(the only place directives are read)"), `explanation/typed-rejection.adoc`,
`explanation/dispatch-axes.adoc`, and the `index.adoc` orientation entries. Audit means: retitle
the claim as transitional or repoint it, not rewrite those pages wholesale; a page needing a
real rewrite gets it in slice 2 or a filed follow-up.

Smallest slice, highest urgency: the current page is actively misleading, and every other slice
assumes a reader can orient on the shipped pipeline.

### Slice 2: the fact-model explanation page

Author `docs/architecture/explanation/fact-model.adoc`, migrating the stable *why* out of
R333's body. Content, at altitude (the page explains and argues; it does not enumerate
relations):

- The key discipline as shipped: base relations key schema elements by the spec grammar's own
  `Name` columns, per relation, all non-null (`graphql_field` on
  `(graph_name, type_name, field_name)` and kin), never by surrogate ids; the canonical
  coordinate string appears in the store only as a rendering of those columns (the two stored
  `coordinate` columns, on the duplicate-declaration overflow and the `diagnostic` read surface,
  say so in their comments). The five-kind sealed coordinate carrier and a mechanical
  never-a-stored-surrogate rule are target-state: the store has no coordinate relation and the
  rendering claim has no enforcer, so per the shipped-and-enforced rule that content stays in
  R333 until a carrier and a gate exist.
- Facts as independent functional dependencies, each found by its own walk; a capability is
  added by adding a fact, not a leaf type; the leaf zoo as the denormalized view that
  multiplication built.
- The provenance discipline: authored and inferred are separate relations when the origins are
  independent walks, a column when one value fills one slot; the resolved value is always a view;
  which population each consumer reads (codegen the resolved view, the editor the authored
  relation, the knowledge surface both).
- Derived reads are views, not stored facts: the candidate space, diagnostics as located
  violation rows with two projections, rendered keys as stable ids, reverse indexes; freshness
  as a snapshot property; location joined-not-stored, with the SDL-cadence exception argued.
- One base, many views, and the re-sourcing invariant: every consumer (codegen, LSP, MCP, the
  test corpus) reads views over one base; no consumer owns a private model; a migration that
  leaves a consumer on the old surface revives the leaves as a shim and forks the model.
- The back half: commands must be complete (the shell decides nothing), the closure invariant
  (every emitted method is one command's render; every callee name resolves to a committed
  command), the seam-placement rule and the single-mint naming regime.

Every migrated claim obeys the shipped-and-enforced rule from Decisions and names its enforcer
on the page (the closure invariant names `MethodClosureOracleTest` / `LauncherRelationClosureTest`;
the comment convention names its `FactSchemaGateTest` gate), so the page stays anchored on
checked references rather than aspiration.

The slice also reconciles the *older* home of the why: `development-principles.adoc`'s first
axiom is still stated in the pre-store shape ("Decide once, at the parse boundary", with the
directive-reading classifier as its exemplar and the containment corollary naming the
classifier-boundary classes). Restate the axiom in fact terms, capture as the boundary and the
derived strata as the carry, and repoint the containment corollary at capture; the page's size
budget (`DocSizeBudgetTest`) forces displacement, so the slice decides which paragraphs the new
page absorbs and which stay. Without this, an agent reads capture-and-derive on the new page and
parse-boundary-classification as the governing axiom on the page it is told to read first.

Amend R333 in the same slice: each migrated section keeps its heading, with a one-line xref to
the page as the body (never a copy), so sibling items that cite R333 sections by name still
resolve and the *why* has one home at every moment. Because only shipped-and-enforced text
moves, the amendment is a relocation, not a redesign of what R333's reviewer signed off, and
R333's residue keeps shrinking toward its own Done-and-delete condition. The page cites live
symbols and published docs only, per the javadoc conventions; no roadmap ids in the page body.

### Slice 3: retool the principles-architect and the reviewer taxonomy

Update `.claude/agents/principles-architect.md` and the shared "what to look for" taxonomy in
`.claude/skills/reviewer-prompt/SKILL.md`:

- Add the slice 1 and slice 2 pages to the agent's ordered reading list.
- Add fact-discipline findings to the taxonomy: a new leaf type where a fact belongs; a
  derivation stored where a view belongs; a new fact landing on the transitional surface instead
  of the store during the strangler window; provenance flattened to a tag column where the
  origins are separate walks; a consumer growing a private model instead of re-sourcing;
  emit-library vocabulary entering the model (the R545 boundary); use-site resolution keyed on a
  definition coordinate or vice versa.
- The agent stays read-only and verdict-free; only its sources and taxonomy change.

### Slice 4: generate the schema reference from the DDL comments

A doc-generation step that boots the store from `graphitron-model.sql` (the same bootstrap jOOQ
codegen already uses), reads the store's metadata *and* the meta-relations through the shared
catalog reader from Decisions, and renders the reference: one page per family plus an index, the
page set, titles, ordering, and index preamble all read from the family rows, the per-object
prose from the comments, prefix-less relations placed by their exemption rows. "Relation" is
pinned throughout this item as tables *and* views (112 tables and 14 views at writing, 12 of the
views the `intent_` claim stratum): both carry comments and columns and both render; only tables
additionally render primary keys, foreign keys, and CHECK constraints, since views carry none.
Nothing about the page structure is parsed from prose or hardcoded in the generator.
This slice introduces the meta-relations and their bidirectional gates (the Decisions bullet),
and migrates the family-definition prose from the header comment into the rows. Comment text
interpolates as AsciiDoc into the rendered `.adoc` pages. The output is generated at build and
never committed; the DDL is the only authored artifact, so the reference cannot drift.

Generated-not-committed removes the failure signal the committed precedent
(`docs/manual/_generated/supported-directives.adoc`, verify-gated) gets for free, so the slice
carries its own enforcers:

- **Non-vacuity floors**, in the falsifiability pattern of the roadmap-tool check tests: every
  relation in the catalog (tables and views alike, per the pinned word above) appears on exactly
  one page, via its family or its exemption row, and every rendered relation carries non-empty
  comment text. A generator that renders a plausible empty reference fails loudly.
- **A comment renderability gate** beside the existing comment-coverage gate in the
  `FactSchemaGateTest` family, guarding both directions: it rejects markdown-isms (`**bold**`,
  pipe-separator tables, `[text](url)` links), the same slip `check-adoc-tables` guards in
  authored pages, and it rejects accidental AsciiDoc activation in innocent prose, a pair of
  `_Word` tokens silently italicizing everything between them. The second direction has a solved
  precedent in-tree: the roadmap-tool's `InertSpans` with `GeneratedAdocSpanGateTest`, whose
  javadoc makes this item's own argument (the docs-profile `WARN` gate cannot hold the line
  because it does not run under a `-P!docs` build). This lands the failure where the comment is
  authored (`graphitron-model`) instead of surfacing as an Asciidoctor `WARN` in the docs render
  two modules away.
- The generation and its floors bind into the base build, not the docs render profile, following
  the docs pom's own argument for `check-adoc-xrefs` ("only render-site lives in that profile").
  The render step itself sits in the `docs` module, which the reactor order admits without
  reordering (`graphitron-model` builds early, `docs` late), with the reader exposed by
  `graphitron-model`.

### Slice 5: drift guard for the authored pages

The authored explanation pages name relations and families in prose. Add a build check that
every backtick-quoted identifier matching a store family prefix in `docs/architecture/**.adoc`
resolves to a relation (or column, or observed family prefix) in the store, in the spirit of the
roadmap-tool's `check-module-enumeration`. The guard reads the same catalog reader as slice 4
(the Decisions rule): if the guard regexed the `.sql` while the generator read the booted
store's metadata, two mechanisms of different fidelity would answer "what relations exist". The
guard makes slice 2's page unable to rot the way the pipeline overview did. The guard needs only
the store-metadata half of the reader, not the meta-relations, so this slice is
standalone-implementable: whichever of slices 4 and 5 lands first stands up the reader, and
landing this one first is fine. Can land with slice 2 if convenient; kept separate so slice 2 is
not blocked on tooling.

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
