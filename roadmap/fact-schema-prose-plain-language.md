---
id: R836
title: "The fact schema's prose is written for its author, not its reader"
status: Spec
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
across the file's 2,266 `COMMENT ON` bodies: the median relation comment is 94 words, the
90th-percentile relation comment is 550 words in one unbroken paragraph, and the longest
(`intent_input_field_filter_role`) is 1,413 words in a single SQL string literal. Three kinds of
writing are fused into each comment: what a row is (reference), how to read and join it
(guidance), and why every rejected alternative was rejected (design argumentation; "deliberately"
appears 61 times, "which is" 477 times). Load-bearing project words (grain, cadence, census,
stratum, seat, drain) are used without definition, and one comment cites "the store glossary", an
artifact that does not exist anywhere in the tree. Because the model's language infects everything
downstream, this is a project-wide legibility problem, not a local style nit.

## What changes when this lands

A contributor who opens the generated schema reference at
`docs/architecture/reference/schema/` gets a page they can read straight through. Each relation
opens with one sentence saying what one of its rows asserts, then the facts needed to join and
query it; the argument for why the relation is shaped that way sits under its own heading further
down, where a reader can skip it and an editor can find it. Words the project uses in a special
sense resolve on a glossary page instead of being guessed at. Nothing is deleted: the same
rationale and the same measurements are still in `graphitron-model.sql`, still under every gate
that watches store prose today, and easier to find than they are inside a 1,400-word paragraph.

The change is contributor-facing, not consumer-facing: no generated output, directive, or Mojo
goal changes shape, so no user-manual draft is owed. The jOOQ-generated Javadoc inherits the
rewritten comments for free, which is the second surface this fixes without touching.

## The measured shape of the problem

The corpus is not evenly distributed, and the plan below is sliced around that rather than around
the family roster alone.

[cols="1,1,1,1"]
|===
| Corpus | Bodies | Words | Distribution

| Relation comments (`TABLE` + `VIEW`)
| 256
| 51,325
| median 94 words, p90 550, max 1,413

| Column comments
| 2,010
| 43,350
| median 13 words, p90 50, p99 133

| `--` prose (header, section banners, view bodies)
| 374 lines
| 5,152
| 14 section banners
|===

One Backlog-stage claim does not survive re-measurement and is corrected here because it points
the fix in the wrong direction. The corpus's median sentence is 18 words, mean 23, which is inside
the technical-writing norm; relation-comment sentences run longer (median 32, mean 34, p90 57) but
are still not the disease. **Sentence length is not the problem, and a rewrite aimed at it would
spend the whole item's effort and change nothing a reader feels.** The problem is comment *length*
and the fusion of three kinds of writing inside one body: a 1,413-word paragraph of 34-word
sentences is unreadable because the reader cannot tell which sentences they need, not because the
sentences are long. Column comments, at median 13 words, are largely already correct and are not
the work.

Two further facts about the distribution drive the slicing. First, `intent_` alone holds 66,648 of
the 94,675 comment words, across 108 relations and 981 columns; the other twelve families together
hold about 28,000. Second, the long tail is short: 104 relation comments exceed 150 words and 66
exceed 300, and 63 of those 66 are `intent_`. The rewrite is therefore not thirteen comparable
units of work; it is twelve small ones and one large one, and the large one is where the design
question actually is.

The target register is not invented for this item. `meta_family.introduction` already holds
thirteen worked examples of it, authored by the same hands, averaging about 50 words each, and
they read plainly today. The doctrine below writes down what those rows already do.

## What must be preserved

- The grain-sentence discipline (first sentence says what one row asserts), and the four existing
  prose gates: comment coverage (`FactSchemaGateTest`, "every table and every column carries a
  `COMMENT ON`"), AsciiDoc renderability (`CommentRenderabilityGateTest`), the grain-sentence
  sweep (`GrainSentenceTest`), and the store-prose identifier drift check
  (`SchemaIdentifierDriftCheck.scanStoreProse`, run as `check-schema-identifiers`).
- The rationale content itself. The why-not arguments and the measured figures in
  `meta_materialize.reason` and in relation comments like `intent_input_field_filter_role` encode
  decisions future sessions must not silently undo. They move to a better home; they do not die.
- Every existing gate keeps its floor. `StoreProse.read` is total over character-typed `meta_`
  values, so prose moved into a new `meta_` relation joins the renderability and drift sweeps by
  existing, without either sweep being edited.
- The four arguments `fact-model.adoc` assigns to the `COMMENT ON` **by name**, which stay in the
  comment and are carved out of the doctrine explicitly rather than swept along with "rationale":
  the both-columns-are-base defence for two spellings of one value ("the DDL comment owns that
  argument at each of them, because the next reader will otherwise reasonably try to delete one");
  the read-cost warning on a derived view ("Each view owes that warning in its comment, because the
  cost is invisible at the call site"); the materialization basis, which `meta_family`'s `intent_`
  charter places in the table comment or the `meta_materialize` row; and load-bearing silence ("the
  view's comment says which silences it owns"). Each is use-facing, not historical. If a slice does
  move one of them, it owes the matching edit to `fact-model.adoc` in the same commit, which is also
  what keeps `SchemaIdentifierDriftCheck`'s two corpora saying the same thing.

## Where a sentence belongs

The plan adds a second prose home (`meta_relation_charter`, slice 3), so it has to say which home a
given sentence belongs in, or every future edit becomes a coin flip.

The discriminator is not locality for the editor; both homes are a few lines apart in the same
file, so locality cannot tell them apart. It is that **the two homes have different numbers of
rendered consumers.** A relation comment has two: the schema-reference page, and IDE hover on
`Tables.INTENT_...` at the code cursor, because `ModelCodegenDriver` sets `withComments(true)`
under the note that "the generated Javadoc is what makes the model self-describing at the call
site". A charter row has one, the page. So the split follows the reader's posture:

- Prose a reader needs **while holding the relation in a query** goes in the **comment**, where the
  call-site view reaches it. Join guidance, null semantics, the closed value set of a constrained
  column, and the four `fact-model.adoc` obligations above are all this.
- Prose a reader needs **while deciding whether the relation's shape is right** goes in the
  **charter**: what was tried, what was rejected, what was measured, why a fork is ranked, why an
  index was left off.
- If neither, it is neither, and it goes.

Stated as a consequence test for the cases that actually occur: if a reader who never saw the
sentence would *use* the relation wrongly, it is a comment; if they would *edit* it wrongly, it is
a charter. Topic-based rules ("keep performance talk out of comments") cannot decide these: a
measurement is charter, but "this view is correlated per row, so do not call it in a loop" is the
read-cost warning `fact-model.adoc` puts in the comment by name.

A fourth kind of writing the corpus holds, beyond the three the problem statement names, has a
home that already exists and needs no new mechanism: taxonomy exposition. The comment on
`intent_input_field_filter_role` spends its back half enumerating what each role value means, and
`intent_mutation_routine_seat.verdict` runs to 544 words doing the same job in the right place.
The schema declares 29 CHECK constraints; wherever a relation comment enumerates a constrained
column's values, that enumeration belongs in the constrained column's own comment, where the
reference renders it beside the CHECK clause. This is the cheapest compression available and needs
no gate: it is a move, not a rewrite.

## The form the two new relations take

Slices 2 and 3 each add a `meta_` relation, and R751 is open against exactly that decision: the
older `meta_` rosters are `CREATE VIEW ... AS VALUES`, which takes no primary key, no `NOT NULL`
and no declared types, and R751 asks whether the family should convert to constrained tables
populated by `INSERT` in the same file. Adding two more views would grow that item's conversion
backlog while this item is arguing for legibility.

**Decision: both new relations are constrained tables**, following the `meta_materialize`
precedent rather than the `meta_family_headline` one. R742 already established it and stated the
reason this item inherits verbatim: `meta_materialize.reason` had to be `NOT NULL`, there was no
way to require that on a view, and the first draft reached for a build gate instead, which R751
calls "the wrong instrument and a clean symptom of the missing constraint". Both new relations have
that exact shape, a required prose payload (`meta_glossary.definition`,
`meta_relation_charter.charter`), so writing them as views would reproduce the same symptom twice
and hand R751 two more conversions.

Two consequences an implementer should expect. `FactSchemaGateTest.everyRelationLeadsWithItsPartitionDimension`
switches per `meta_` relation and defaults to `graph_name`, so each new keyed table needs its own
case (`meta_glossary` on `term`, `meta_relation_charter` on `relation_name`); this is the arm R751
predicted future keyed `meta_` relations would need. And the non-blank gates in the Tests section
below shrink accordingly: `NOT NULL` is the schema's job, and the gate is left checking only what a
constraint cannot, that the value is not whitespace.

This item does not convert the older three; that stays R751's. It just stops making R751 bigger.

## Implementation

Six slices, in dependency order. Each is a separately committable increment, and slice 5 lands
family by family and then layer by layer, so the numbering marks real seams rather than
bookkeeping. Slice 1 settles the doctrine against the hardest case, slices 2 to 4 build the homes
the overflow needs, slice 5 is the bulk, and slice 6 can only be installed once 2 to 4 exist.

### 1. Store-prose doctrine, proved on the hardest comment

Write the target register down where authors meet it. New page
`docs/architecture/how-to/writing-store-prose.adoc` (how-to rather than explanation: the reader is
a contributor about to write a `COMMENT ON`, not one trying to understand the model), listed in
`docs/architecture/how-to/index.adoc` and registered in `LinkTarget.ARCH_QUADRANT` so
`ArchQuadrantBindingTest` holds the map to the page.

The doctrine states: a comment is the grain sentence, then the facts a reader needs to use the
relation (join guidance, null semantics, the closed value set of a constrained column, and the four
`fact-model.adoc` obligations carved out above), then at most one short why that points at where
the full argument lives. It carries the residency test above and the ~50-word
`meta_family.introduction` rows as the worked target.

**Derive it, do not declare it.** The Backlog body said no existing principle covers this and the
doctrine must not pretend to be derived. That is mostly wrong and worth correcting, because a
derived doctrine is citable in review while a declared one is a preference anyone can reopen. The
schema reference and the jOOQ Javadoc are generated consumer artifacts of this prose, and
`development-principles.adoc`'s "Generated code is a consumer artifact" already says to optimise
emitted output for "developers who have never seen the generator", not for emitter-side brevity,
which is this item's thesis one surface over. `graphitron-principles.adoc` supplies the rest: the
30-year horizon and "generated code should be readable without special tools". Only the
non-native-English-readers clause is genuinely new project policy, and only it gets stated as a
decision rather than derived.

**Acceptance, and the flagship.** The criterion is the reader's test: *a reader who has never seen
graphitron can read the rendered page and answer what one row asserts and how to join it.* Written
under fact-model's own `*Not mechanically enforced:*` label, so a later reader does not mistake it
for a gate, and with a judge who exists in the workflow: the In Review → Done reviewer reading the
rendered page, not a hypothetical competent developer with no referent.

The flagship demonstration is **one hard `intent_` relation**, `intent_input_field_filter_role`
(the 1,413-word worst case), rewritten under the doctrine with its charter split out. Not the file
header, which the Backlog body proposed: the header is the one part of the store's prose that no
gate reads, no page renders, and no consumer sees. `StoreProse.read` takes `REMARKS` from
`INFORMATION_SCHEMA` plus character-typed `meta_` values; `--` lines never become remarks, and
`SchemaReferencePages` only ever touches `StoreCatalog`. Choosing the corpus's one ungated blind
spot as the flagship would demonstrate the doctrine to nobody, and demonstrating it on a
transcription-family comment would not test it at all (see slice 5).

**The header still needs a decision, and this slice makes it.** It is already rotting in the way
its ungated status predicts: line 17 defers the naming conventions to "the roadmap item that
introduced the schema", a citation with no resolvable referent, which is the same defect as the
phantom "store glossary" that slice 2 fixes. Either the header's load-bearing content migrates to a
gated surface (a `meta_` row, a relation comment, or a page inside
`SchemaIdentifierDriftCheck.SCANNED_TREE`) and the header shrinks to navigation, or the item states
plainly that the header is ungated authored prose and accepts that. Pick one and say which; do not
rewrite it as though it were gated.

### 2. `meta_glossary`

A `meta_glossary (term, definition)` table in the DDL itself, rows carried by an `INSERT` in the
same file (see "The form the two new relations take" above), rendered into the schema reference
(its own page or a section of `index.adoc`, the renderer's call) and cross-linked from family
pages. In the DDL rather than in `docs/`, because a `docs/` glossary would be exactly the
drifting shadow enumeration `meta_family` was created to kill; a glossary is a function of the
file alone, which is the `meta_` residency test.

Gated in one direction only: every rostered term occurs in store prose, so no dead terms. **The
gate's corpus is the `RELATION_COMMENT` and `COLUMN_COMMENT` entries only.** Swept over the whole
`StoreProse` corpus the gate would satisfy itself, because `StoreProse.read` is total over
character-typed `meta_` values by design, so `meta_glossary.definition` joins the corpus the moment
the relation exists and a term occurring nowhere but in its own definition passes. The gate also floors
itself against a vacuous pass the way its siblings do, pinning the swept count against the census
rather than asserting `isNotEmpty` (`CommentRenderabilityGateTest`) and failing outright on an
empty corpus (`SchemaIdentifierDriftCheck`).

The converse (every load-bearing term is rostered) is not machine-checkable, and the relation's own
comment says so. The honest precedent for that disclosure is `meta_family.introduction`'s comment,
"No gate can read intent, which is what makes this comment the enforcement", **not**
`meta_family_bridge`. The bridge declines a closure over view bodies that no reading of the
declarations could produce, which is a different species from declining a judgment; and slice 4 of
this same item argues that judgments become tests wherever they are mechanically decidable, so
citing the bridge here would be claiming a stronger warrant than the gate has.

Companion authoring rule, replacing "define on first use" (which has no referent in a corpus with
no reading order): a load-bearing term is either in the glossary or not used, and no comment
carries its own gloss. This slice also fixes the phantom "store glossary" citation in
`meta_family_headline`'s comment, which is where the term `grain` is currently disambiguated
against a glossary that does not exist.

### 3. `meta_relation_charter`: rationale moves down the page, not out of the file

A `meta_relation_charter (relation_name, charter)` table keyed on `relation_name`, so **at most one
row per relation** holds by construction rather than by gate, resolve-gated against the
`meta_relation_family` census and rendered by `SchemaReferencePages.renderRelation` below the
relation's columns under its own heading.

One slot, not a `(relation_name, ordinal, note)` bag. An ordinal-keyed roster's row asserts "note
number 2 about relation X": the key's tie-breaker is a presentation ordinal, the payload is
unconstrained prose, and there is no membership test an author can apply at an edit. That is the
fact model's own smell, a relation named for a consumer's question rather than for what one row
says, and it would leave the choice between the two homes undecidable in exactly the cases that
matter. A 1:1 charter says something a resolve gate can hold: *this relation's shape is defended
here*. It also keeps slice 6's cap meaningful, since a note bag is uncapped by construction and the
overflow would relocate rather than compress.

Moving the essays into `docs/` instead would strip them of the gates that keep them honest
(coverage, renderability, identifier drift), which is how the old pipeline overview came to
describe a retired architecture as current. The roster keeps the rationale a few lines from the
DDL it defends, which is locality for the next editor, while the rendered page separates reference
from argument, which is readability for the reader. `StoreProse.read` is already total over
character-typed `meta_` values, so the new prose joins every existing gate automatically.

This is not a new mechanism standing beside the comment; it is the split the file already made at
the family grain, read one grain down. `meta_family` splits a plain-language `introduction` from a
doctrinal `definition`, `SchemaReferencePages.familyPage` renders the second under its own "Why the
name is right" heading below the first, and the column comment on `introduction` writes the
residency test out loud: "the two are complements, the introduction presenting and the charter
defending the name." Slice 3 does the same thing for relations, which is why the new relation is
named `charter` and not `note`: same word, same job, one grain down.

Reader-facing work in `StoreCatalog` (a `Charter` record and its read) and in `SchemaReferencePages`
(a "Why it is shaped this way" heading after the `.Columns` block, omitted where a relation has no
charter). No floor requiring a charter: most relations will not have one, and a gate demanding one
would manufacture the essays this item exists to compress.

### 4. Gate-or-compress sweep of the defensive paragraphs

For each of the 61 "deliberately" paragraphs and their unmarked siblings, first ask: can this be a
test? Several are mechanically decidable with machinery that already exists,
`SchemaIdentifierDriftCheck`'s bare-identifier extractor plus the family prefixes.

**That machinery has to move first.** `SchemaIdentifierDriftCheck` lives in `roadmap-tool`, which
depends on `graphitron-model`; every store-prose gate a new gate would be a sibling of
(`CommentRenderabilityGateTest`, `GrainSentenceTest`, `FamilyRosterGateTest`) lives in
`graphitron-model` and reads `FactStores.inMemory()`. A gate there cannot call the roadmap-tool
extractor, so as drafted slice 4 lands on either a copy or an inverted dependency, and a copy is
the exact fork that class's javadoc argues against: "two mechanisms of different fidelity would
answer 'what relations exist'". The tree solved this once already, `GrainSentence` sitting beside
`StoreCatalog` rather than in the renderer because the convention is the store's and not the
renderer's. Do the same: move `BARE_IDENTIFIER` and `resolves` down into
`graphitron-model/.../catalog` beside `StoreProse`, and have `SchemaIdentifierDriftCheck` call
them. That move is the first commit of slice 4.

The worked example is in the file already and is worth stating because it also shows the failure
mode. `meta_family.introduction`'s comment says "An introduction deliberately names no relation
and no other family", and then says "No gate can read intent, which is what makes this comment the
enforcement". That second sentence is false for this claim: the drift check's bare-identifier
extractor already finds prefix-anchored relation names and family prefixes in running text, so a
gate asserting that an `introduction` value resolves no identifier other than its own prefix is
about fifteen lines. The paragraph is not review-only residue; it is a test that was never written
because nobody asked the question. Slice 4 is asking it, 61 times.

The discipline that keeps this honest is the tree's own convention, not "gated by `<TestName>`". A
bare test name in a comment reads as "this claim is enforced" and is precisely the shape that
produces a gate asserting less than the paragraph claimed. Instead:

- Attach `(gated)` to the **specific mechanical clause**, not to the paragraph, the way
  `meta_family_headline.ordinal` already does it: "dense from zero within each family (gated),
  which is the schema-gate convention here because uniqueness alone would let a gap through". The
  gated clause and the residue sit in one sentence and cannot be confused.
- Make the gate's own javadoc own what it does not cover, as `CollectionValuedColumnGateTest`,
  `CaptureCorpusIsolationTest` and `FamilyRosterGateTest` all do. The residue stays in the
  relation's charter.

A paragraph disappears entirely only when the new gate's failure message would tell an editor
everything the paragraph would have told them. Paragraphs that survive the question are honest
review-only residue worth one compressed sentence in the charter.

This is likely the largest word-count reduction available, and the only slice that strengthens the
store rather than trading enforcement for prose.

### 5. The rewrite itself, by family and then by layer

Acceptance judged by reading the rendered page rather than the diff. **Scope is `COMMENT ON` bodies
only.** The Backlog body also claimed the section banners and the `--` prose inside view bodies,
"since the reference renders from all of it"; it does not. `--` lines never become `REMARKS`, so
they are outside `StoreProse`, outside all four gates, and outside `SchemaReferencePages`. They are
editor-only prose and belong to slice 1's header decision, not to a rewrite judged by a rendered
page.

**5a, the twelve smaller families** (about 28,000 words), `store_` and `graphql_` first as the front
door, then `graphitron_` and `sql_`, then the rest. These are a register rewrite: the doctrine
applied to prose that is already mostly the right kind of writing.

**5b, `intent_`** (about 66,600 words, 63 of the 66 relation comments over 300 words), sliced on the
layering the DDL already declares rather than on the family. `meta_family`'s `intent_` charter
states that "the stratum has two layers, and a new resident picks one deliberately": the base
derivations (authored-claim views, structural classifier views, the resolutions those classifiers
stand on, the demand and exemption rule views) and the reductions over them. Slice on that, so the
rewrite unit is a taxonomy the store owns. An `intent_` *page* carrying 108 relations and 981
columns is not a unit anyone can read as an acceptance test; a layer is.

Two corrections to the Backlog ordering, both consequences of the measurements above.

First, **`intent_` is not last for doctrine purposes**, only for bulk. `store_` and `graphql_` are
transcription families where "what one row is" genuinely is the whole comment; `intent_` is
derivation, and its comments carry the four `fact-model.adoc` obligations that "grain sentence,
usage facts, at most one short why" has no slot for. A doctrine written and accepted against twelve
transcription families would then govern the rewrite of 70% of the corpus without ever having been
tested on the hard case. That is why slice 1's flagship is an `intent_` relation: the doctrine
meets the hard case before it is settled, and 5b is only bulk after that.

Second, 5b runs charter-first: each of the 63 long comments gets its rationale lifted into
`meta_relation_charter` and its taxonomy exposition moved to the constrained column, and what
remains is rewritten. Do not start 5b until 5a has landed.

An open question 5b must answer rather than assume: whether `intent_`'s length is a prose defect at
all, or the derivation layer telling us that a relation whose comment needs 1,400 words is a
relation whose *shape* is doing too much. Where 5b hits a comment that will not compress because
the relation genuinely fuses several ideas, the finding is a Backlog item against the schema, not a
longer charter. Record those rather than absorbing them.

### 6. Length gate, last

Only after slices 2 to 4 exist. A cap installed before the overflow has a destination selects for
the disease, because the cheapest way to compress 400 words is to nest clauses harder.

The gate caps the **relation comment**, not the relation's total prose: its job is to keep the
reference entry readable and the call-site hover usable, so charters are uncapped by design and
moving words into a charter is the intended escape.

Exemption-polarity roster kept in the gate test, with a reason column that must argue why the
prose has to bind at this cursor rather than render one heading down. The warrant for keeping the
roster in the test is *not* "nothing renders it", which is the wrong discriminator: nothing renders
`meta_materialize.reason` either and it is squarely `meta_` material. The real rule, stated in the
DDL header, is that a roster keyed to a gate's own threshold lives with that gate
(`MaterializeRegistryGateTest`'s index roster is the precedent) while a roster the reference
renders lives in `meta_` (`meta_prefixless_relation`). This roster is keyed to a threshold, so it
lives in the test.

The cap itself is set from what 5a and 5b actually land, not chosen up front.

## Tests

- **New.** `meta_glossary` occurrence gate: every rostered term occurs in a `RELATION_COMMENT` or
  `COLUMN_COMMENT` entry (not the whole `StoreProse` corpus, per slice 2), with the swept count
  floored against the census. Uniqueness and non-null are the primary key's and `NOT NULL`'s job
  under the table form, so the gate is left checking only what a constraint cannot: that a
  definition is not whitespace. Its own test rather than a method on `FamilyRosterGateTest`, which
  is about the family roster.
- **New.** `meta_relation_charter` resolve gate: every `relation_name` resolves to a censused
  relation. One row per relation comes from the key, so the gate does not restate it; non-blank on
  the same terms as above.
- **New.** `SchemaReferencePagesTest` coverage for the charter heading: rendered where a charter
  exists, absent where none does.
- **Moved, slice 4.** `BARE_IDENTIFIER` and `resolves` relocate from `SchemaIdentifierDriftCheck`
  into `graphitron-model/.../catalog`; `SchemaIdentifierDriftCheckTest` must stay green across the
  move, which is what proves it was a relocation and not a second extractor.
- **New, slice 6.** The relation-comment length gate with its exemption roster.
- **New, slice 4 only where the answer is yes.** Whatever gates the sweep converts a paragraph
  into, each with javadoc naming what it does *not* cover.
- **Unchanged and load-bearing.** `FactSchemaGateTest` comment coverage,
  `CommentRenderabilityGateTest`, `GrainSentenceTest`, `check-schema-identifiers`. Every slice runs
  green against all four; slices 2 and 3 additionally prove that the new `meta_` prose is swept by
  the last three without those sweeps being edited, which is the claim that makes the DDL the right
  home.

## Out of scope

- Restructuring `meta_materialize.reason`'s measurements into typed columns: the figures are per
  (registration, reader, fixture, tree) and self-describe as unretakeable provenance, so typed
  columns would assert a grain the data does not have. The one narrow lift worth taking, a `basis`
  CHECK column for the closed two-value doctrine each reason currently re-argues in prose, is a
  schema change and gets its own item if wanted.
- Rewriting `fact-model.adoc` for register. It shares the problem but is already inside R814's
  in-progress architecture-docs rework, so register goals fold into that thread rather than being
  double-booked here. The doctrine page in slice 1 is a new page for a different reader and does
  not collide with it. **One carve-out**: where a slice moves a paragraph that `fact-model.adoc`
  assigns to the `COMMENT ON` by name (the four listed under "What must be preserved"), the
  matching edit to `fact-model.adoc` is in scope and lands in the same commit, because leaving it
  behind is how the two corpora `SchemaIdentifierDriftCheck` watches start disagreeing. Coordinate
  with R814 before touching that file.
- Schema changes suggested by 5b. Those get filed as Backlog items, not absorbed.
