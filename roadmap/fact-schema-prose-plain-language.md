---
id: R836
title: "The fact schema's prose is written for its author, not its reader"
status: Backlog
bucket: dx
priority: 3
theme: docs
depends-on: []
created: 2026-08-26
last-updated: 2026-08-26
---

# The fact schema's prose is written for its author, not its reader

`graphitron-model.sql` is the backbone of the project: its comments render into the generated
schema reference, the jOOQ-generated Javadoc, and the vocabulary every other module inherits. That
prose is currently unreadable for anyone who is not an AI agent steeped in the project. Measured
across the file's 2,265 `COMMENT ON` bodies: the median sentence is 44 words (technical-writing
norms are 15 to 25), the 90th-percentile relation comment is 512 words in one unbroken paragraph,
and the longest (`intent_input_field_filter_role`) is 1,371 words in a single SQL string literal.
Three kinds of writing are fused into each comment: what a row is (reference), how to read and
join it (guidance), and why every rejected alternative was rejected (design argumentation;
"deliberately" appears 61 times, "which is" 480 times). Load-bearing project words (grain,
cadence, census, stratum, seat, drain) are used without definition, and one comment cites "the
store glossary", an artifact that does not exist anywhere in the tree. Because the model's
language infects everything downstream, this is a project-wide legibility problem, not a local
style nit.

Two things in the tree prove the fix is possible without losing content:
`docs/architecture/explanation/naming-the-row.adoc` explains the same concepts in a friendly
register, and `meta_family` already splits a plain-language `introduction` column from its
doctrinal `definition` charter. This item extends that split to the whole file.

## What must be preserved

- The grain-sentence discipline (first sentence says what one row asserts), and the four existing
  prose gates: comment coverage, AsciiDoc renderability, the grain-sentence sweep, and the
  store-prose identifier drift check.
- The rationale content itself. The why-not arguments and the measured figures in
  `meta_materialize.reason` encode decisions future sessions must not silently undo. They move to
  a better home; they do not die.

## Slices

One item, six slices, in dependency order. Each slice is a separately committable increment; the
family rewrites in slice 5 land family by family.

1. **Store-prose doctrine and rewritten file header.** Write the target register down where
   authors meet it: a comment is the grain sentence, then the facts a reader needs to use the
   relation (join guidance, null semantics), then at most one short why pointing at where the full
   argument lives. Acceptance criterion stated as the reader's test: a competent developer who has
   never seen graphitron can read the rendered family page and answer what one row asserts and how
   to join it. The doctrine names non-native English readers as an explicit project decision (no
   existing principle covers it, so it must not pretend to be derived). The 67-line file header is
   rewritten under its own rules as the flagship.

2. **`meta_glossary`.** A VALUES view (term, definition) in the DDL itself, rendered into the
   schema reference and cross-linked from family pages. A `docs/` prose glossary would be the
   drifting shadow enumeration `meta_family` was created to kill; a glossary is a function of the
   file alone, which is exactly the `meta_` residency test. Gate one direction only (every
   rostered term occurs in store prose, so no dead terms) and disclose that the converse is not
   machine-checkable, per the `meta_family_bridge` precedent. Companion rule replacing "define on
   first use", which has no referent in a corpus with no reading order: a load-bearing term is
   either in the glossary or not used, and no comment carries its own gloss. This slice also fixes
   the phantom "store glossary" citation in `meta_family_headline`'s comment.

3. **`meta_relation_note`: rationale moves down the page, not out of the file.** A
   `meta_relation_note (relation_name, ordinal, note)` roster in the same VALUES-view form as
   `meta_family_headline`, resolve-gated against the census, rendered by `SchemaReferencePages`
   below the relation's columns under its own heading. Moving the essays into `docs/` instead
   would strip them of the gates that keep them honest (coverage, renderability, identifier
   drift), which is how the old pipeline overview came to describe a retired architecture as
   current. The roster keeps the rationale a few lines from the DDL it defends (locality for the
   next editor) while the rendered page separates reference from argument (readability for the
   reader). `StoreProse.read` is already total over character-typed `meta_` values, so the new
   prose joins every existing gate automatically.

4. **Gate-or-compress sweep of the defensive paragraphs.** For every "deliberately not X"
   paragraph, first ask: can this be a test? Many are mechanically decidable with machinery that
   already exists (`SchemaIdentifierDriftCheck`'s extractor plus the family prefixes; the
   `meta_family.introduction` "names no relation and no other family" claim is the worked
   example). Those shrink to a grain sentence plus "gated by `<TestName>`". The ones that survive
   the question are honest review-only residue worth one compressed sentence in the relation's
   note. Likely the largest word-count reduction available, and the only one that strengthens the
   store rather than trading enforcement for prose.

5. **Family-by-family rewrite.** Thirteen families, rewritten under the doctrine, acceptance
   judged by reading the rendered family page rather than the diff (the family is both the page
   unit and the gate grain). Suggested order: `store_` and `graphql_` first as the front door,
   `intent_` last as the largest and most essay-laden. Scope is all store prose: `COMMENT ON`
   bodies, the section headers, and the `--` prose inside view bodies, since the reference renders
   from all of it.

6. **Length gate, last.** Only after slices 2 to 4 exist: a cap installed before the overflow has
   a destination selects for the disease, because the cheapest way to compress 400 words is to
   nest clauses harder. Exemption-polarity roster kept in the gate test (nothing renders it, so it
   is not `meta_` material), with a reason column that must argue why the rationale has to bind at
   this cursor rather than render one heading down.

## Out of scope

- Restructuring `meta_materialize.reason`'s measurements into typed columns: the figures are per
  (registration, reader, fixture, tree) and self-describe as unretakeable provenance, so typed
  columns would assert a grain the data does not have. The one narrow lift worth taking, a `basis`
  CHECK column for the closed two-value doctrine each reason currently re-argues in prose, is a
  schema change and gets its own item if wanted.
- `fact-model.adoc` shares the register but is already inside the in-progress architecture-docs
  rework (the item on the docs describing the drained surface); register goals fold into that
  thread rather than being double-booked here.
