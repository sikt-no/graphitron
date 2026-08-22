---
id: R800
title: "Family pages open with an introduction, main grains and bridge roster"
status: Ready
bucket: docs
depends-on: []
created: 2026-08-21
last-updated: 2026-08-22
---

# Family pages open with an introduction, main grains and bridge roster

## Problem

A family page in the generated schema reference opens with the prefix and the family's charter
(`meta_family.definition`) and then lists every relation. The charter is a dense argument for why
the name is right under the naming discipline; it defends the family, it does not present it. A
reader arriving at `intent.html` gets no orientation: which relations are the headline of the
family, and how this family's rows meet the other families' rows, are both answerable only by
reading all of the relation entries and reconstructing the joins in their head.

The second half of that gap is not just a documentation problem. How two families meet is itself a
fact about the schema. The sanctioned meeting between written names and the catalog is the
spelling-normalization rule that `intent_spelled_table` owns, and nothing today states that this
view is the *only* place that rule lives: a new view can re-derive its own normalization with
function calls in a join predicate, produce plausible rows, and quietly fork the mapping. The
family relationships should therefore live in the store as declared rows the reference renders,
not as prose an author writes about the store. Declared like the rest of the schema: the bridges
are base facts, authored in the DDL the way the family roster and the materialize register are,
and everything that checks or extends them later is a derivation seeded by these declarations.
The base facts must exist before any derivation can close against them.

## Scope

First delivery, aimed at the documentation; the definition of done is that the result is
browsable on the public site. In scope: an authored introduction per family, an authored headline
roster per family, the declared bridge roster with resolve gates, a derived foreign-key reference
view, the renderer sections that put all of it on the family pages, and the drift-check extension
that keeps the new prose honest. The population itself (every introduction, every headline row,
every bridge row) is drafted in this spec's Population section, so the gate reviews the content
and not only the mechanism.

Deferred to the follow-up item (`view-read-census-and-bridge-closure`): all derivation over the
declarations. That is the machine-written view-read census, the crossing gate that closes the
bridge roster against what the views actually read, the keyed-crossing exemption register that
gate needs, and eventually the predicate-level analysis. The follow-up derives; this item
declares the base facts it derives from.

This spec has been at both poles. The first draft deferred the whole closure; a principles
consult pushed it in, on the argument that a roster without its census has inclusion polarity;
the review of that version showed the closure dragging the whole register design into knots
(which relation of a materialized pair a row names, a 46-row exemption register). Settled by the
item's owner at review: declaration precedes derivation. The declared roster is honest about its
polarity while the follow-up is open: the rendered section presents the declared crossings as
declarations, and nothing in the renderer's wording claims the set is exhaustive until the
follow-up's gate makes it so.

## The authored artifacts

All live in `graphitron-model`'s DDL (`graphitron-model.sql`), following the standing rule the
renderer stamps on every page: the DDL is the only authored source, so nothing here is a
committed AsciiDoc fragment.

**1. `meta_family.introduction`, a new column on the roster view.** Friendly prose that presents
the family to a first-time reader: what its rows transcribe or derive, in plain language, one
short paragraph. It complements the charter rather than replacing it: the introduction presents,
the charter defends the name. The column comment carries the discriminator and is load-bearing
the way `meta_materialize.reason`'s comment is: an introduction says what the family is for and
deliberately names no relation and no other family, because the headline roster and the meeting
section carry those and are gated, while prose naming them would go stale with nothing failing.
No gate can read intent, so the comment is the enforcement.

**2. `meta_family_headline`, a new authored roster view: `(relation_name, ordinal)`.** One row
per headline relation of a family: the relations a reader should meet first, typically the
family's central grain plus the derivations that make it useful. Curation is judgment, so
membership is authored; everything else is derived. The family comes from the
`meta_relation_family` census by join, never stored beside the name (a stored copy of a derivable
fact, and its agreement gate, would both be the smell the fact model names). What each headline
*is* comes from the relation's own `COMMENT ON` text (extraction below), so there is no second
authored blurb to drift. Not `meta_family_grain`: *grain* is one of the store glossary's two
load-bearing words and this roster does not mean it; a reader of the reference must never have to
disambiguate.

**3. `meta_family_bridge`, the sanctioned normalization crossings:
`(relation_name, spelled_prefix, census_prefix, rule)`.** One row says: this relation owns the
rule by which a name written in `spelled_prefix`'s vocabulary is matched against
`census_prefix`'s census. The columns are named for their roles, not `from`/`to`: a
normalization always has a spelled side and a census side, so the role names are always true,
where a direction pair would assert something the population only usually carries and would be
ambiguous against the bridge's own residence family (`intent_spelled_table` lives in `intent_`
and bridges `graphitron_` spellings to the `sql_` census). The `rule` column states the rule in
one sentence. A row names the relation a consumer reads. For a registered reduction that is the
canonically named relation (`intent_spelled_table`), never its `_live` source view, whose own
comment tells readers not to spell it; the reference cross-links what it names, so the register
carries reader-facing names by construction. The follow-up's crossing gate owns the consequence:
to check a registered reduction's row against the view-read census it must resolve through
`meta_materialize` to the source view first, and its spec inherits that requirement.

Membership is two tests applied together, and the population below is the sweep with both
applied, so implementation transcribes it rather than re-sweeping. A relation is on the roster
when it owns a normalization rule of its own, spelled in its own body, not when it reads a
relation that states one; and when that rule mediates a meeting between two families'
vocabularies, not a normalization inside one. Where the DDL assigns a rule's ownership in its
own comments, the roster follows that assignment: a coordinate-shaped sibling whose comment says
the rule is argued at the other site restates a body, not a rule, and gets a disclosure rather
than a second row. The flagship row is `intent_spelled_table`.

Crossings by plain column equality on a shared natural key get no bridge row by design. A
foreign key is already a declared, engine-checked join path, and a coordinate-equality join
between families is the ordinary case the whole `intent_` stratum exists for. The same exclusion
covers a verbatim match on a name two families carry: an identity comparison has no rule to
re-derive differently, so there is nothing a new view could silently fork. The bridge roster is
only for the rule-mediated meetings, because those are the ones a fork can corrupt.

## The derived artifact

**`meta_relation_reference`, the declared key edges as a view.** One row per declared foreign
key: child relation, child family, parent relation, parent family, defined over
`INFORMATION_SCHEMA` (`REFERENTIAL_CONSTRAINTS` resolved through `KEY_COLUMN_USAGE` the way
`StoreCatalog.foreignKeysByRelation` already does) joined through `meta_relation_family` on both
ends. At constraint grain, deliberately: cross-family is a predicate a reader applies, not a
population filter baked into the relation, and the name says what a row is rather than where it
renders. A store view rather than renderer-side aggregation because `StoreCatalog`'s contract is
that family assignment is never re-derived outside the census, and because the follow-up's
crossing gate and the MCP schema surface are second readers of the same answer. This is also the
pattern the whole item leans on: a derivation seeded by declarations, here the declared foreign
keys, which is exactly the relationship the follow-up will have to the declared bridges.

## Renderer and reader changes

`StoreCatalog`: `Family` gains `introduction`; new records for headline, bridge and reference
rows, each read from its view in the fixed-order style the reader already uses, data verbatim.

The grain-sentence extraction lives in `graphitron-model` beside the catalog reader, not in the
renderer: "the first sentence of a relation comment is its grain statement" is a store
convention, and the renderer's charter is presentation vocabulary only. `StoreCatalog`'s records
stay verbatim-from-engine, so the extractor is a sibling helper the renderer calls. Its
acceptance line is pinned in both directions in its own test, the way the renderability gate
pins its subset: accepted shapes plus the corpus's real hazards as handled cases (dotted
coordinates like `Type.field`, package-qualified class names, version strings), and a sweep
asserting every censused relation yields a plausible first sentence: non-blank, strictly shorter
than the comment, terminated. A naive period-and-whitespace split with only a blank-result floor
is rejected: the blank case cannot happen, the mis-split case will.

`SchemaReferencePages`, family page layout, in order:

1. Title, prefix line (unchanged).
2. The introduction.
3. "Where to start": the headline roster, each entry the relation name linked to its same-page
   anchor plus its extracted grain sentence.
4. "How this family meets the others", two labeled parts so the provenance the relations keep is
   not flattened at the render: the authored bridge rows where the family participates on either
   side (the rule sentence, the owning relation cross-linked), then the declared key edges
   aggregated from `meta_relation_reference` ("rows here reference `sql_` through 2 foreign
   keys; referenced by `intent_`"). Families with neither render an honest one-liner saying so
   rather than an empty heading.
5. "Why the name is right": the charter, under its own heading now that it no longer opens the
   page.
6. The relations (unchanged).

The index page's per-family blurb switches from the charter to the introduction; the charter
stays on the family page only. Renderer floors extend to the new sections: a family with a blank
introduction or no headline rows fails the render loudly.

## Gates and checks

- Roster gates in the store's suite: `introduction` non-blank for every family; every headline
  row resolves to an observed relation with a census family, ordinals dense from zero within
  each family (density is the schema-gate convention, uniqueness alone hides gaps), and every
  family has at least one headline row; every bridge row resolves, meaning its relation is
  observed, `spelled_prefix` and `census_prefix` are rostered and distinct, and `rule` is
  non-blank. Resolve gates only: whether the declared bridges cover every crossing the views
  perform is the follow-up's derivation, not checkable from declarations alone.
- The renderability subset needs no new work and none is planned: the meta-prose sweep is
  already total over every character-typed value of every `meta_` relation, so the new columns
  join it by existing.
- Comment coverage of the new relations and columns, and their placement on a family page, are
  enforced by `FactSchemaGateTest`'s existing comment-coverage gate and its census-closure gate
  ("every relation resolves to one family page or carries an exemption row") without new code.
- `SchemaIdentifierDriftCheck` gains the store's own prose as a second corpus: the new columns
  exist to cite relation names (`rule` inherently, introductions naturally despite the
  discriminator), and today the check scans only `docs/architecture`, so a rename would leave
  gated name columns correct and the prose beside them silently wrong. The check's store boot
  and its `resolves`/`universeOf` halves are reused, but not its span extractor: the docs corpus
  extracts backtick spans, while store comments cite relations in bare prose, so the store
  corpus needs its own extractor over prefix-anchored bare tokens. The family charters' rejected
  names (`jooq_`, `extension_`, `validator_`) fall outside the observed-prefix filter and are
  not a hazard. If the sweep surfaces pre-existing rot in old comments, fixing those citations
  is in scope.

## Authoring work

The population is the judgment-heavy half of the item, so it is drafted in this spec and
reviewed at this gate rather than appearing first in an implementation diff. The section below
is the draft. Implementation transcribes it into the DDL's string literals (single physical
lines, the accepted inline AsciiDoc subset, so no emphasis pairs and backticks only where a
symbol is named), corrects any row the view bodies contradict, and any correction that changes a
row's meaning returns through the spec rather than shipping silently.

## Population (drafted for this gate)

**Introductions.** One per family, honouring the discriminator: no relation names, no other
family's name.

- `store_`: Every run of the generator leaves a record of itself here: which module it captured,
  which files it read, what configuration it held in hand, and where it wrote. The rows answer
  bookkeeping questions rather than schema questions: what was this store built from, and which
  graph do these facts belong to. When two runs disagree, this family is where you look up what
  each of them actually saw.
- `graphql_`: A complete transcription of the GraphQL schema documents, as any SDL reader would
  see them. Every type, field, argument and directive application lands here exactly as written,
  with no judgment about what any of it means. The two judgment rows it does hold are the SDL
  toolchain's own verdicts on whether the documents parse and validate at all.
- `graphitron_`: What the generator understands the author to have asked for. Each row is the
  decoded reading of one directive application: the same information that stands in the schema
  documents, restated in the generator's own vocabulary so later questions can be asked with
  plain joins instead of re-parsing strings. Decoding is tolerant: a value that does not fit the
  declared shape is quarantined rather than lost.
- `sql_`: What the consumer's database declares, read through the generated jOOQ model: schemas,
  tables, columns, keys, constraints, indexes and callables. These rows are the ground that
  written table and column references are checked against. A few rows also transcribe what the
  generated model states about itself, because that model ships as one unit with the catalog it
  was generated from.
- `jvm_`: A census of the classes available on the declared compile classpath: the classes
  themselves, their public methods and parameters, their record components and supertypes. When
  the schema names Java code, this census is what the name is checked against. Presence says
  nothing about purpose; a class earns its row by being on the classpath, not by being used.
- `java_`: Where things are written in the consumer's own Java sources: the position and
  documentation comment of each class, method and field declaration, from a plain parse of the
  source files. It exists so tools can point at a line in a file the author owns. It reads the
  sources rather than the compiled output deliberately, because the two answer different
  questions and may legitimately disagree.
- `javac_`: What the JDK compiler reported when the emitted sources were last compiled: one row
  per diagnostic, in the compiler's own words. Each compile round replaces the previous one
  wholesale, so the family always describes the latest round and nothing older.
- `walk_`: What the legacy classification walk concluded, kept while that walk is being retired.
  The rows exist so new derivations can be checked against the old code's answers during the
  migration; when the walk is gone, the family goes with it.
- `intent_`: The derivation layer: what follows once the schema's readings, the database catalog
  and the classpath census are put side by side. Rows here are computed, never captured; each
  view states one rule, and taller derivations are built by reading shorter ones. This is the
  family the generator and the editor tooling actually plan from.
- `rejection_`: The legacy walk's error verdicts, in the sealed rejection hierarchy's own
  vocabulary. Transitional by construction: each kind of verdict moves out as its detection is
  rebuilt store-native, and the family empties as that migration completes.
- `lint_`: The linter's findings: one row per finding, plus the corrections a rule can compute
  for its own findings. A correction here is a suggestion an editor may offer, never a rewrite
  the build performs. The family speaks the linter's vocabulary, where severity follows from the
  rule.
- `build_warning_`: The advisory arm of build feedback: warnings that point at nothing
  rule-shaped, just a message and a location worth a human's attention. Small on purpose.
- `meta_`: The schema describing itself: which families exist, where the exceptions live, and
  how the roster closes against the relations actually observed. These rows are versioned with
  the schema definition and never change at run time. The reference pages you are reading are
  rendered from this family's rows and the comments beside them.

**Headline rosters.** Ordinals follow list order. The single-relation families list their one
resident, which keeps the every-family-has-a-headline gate uniform.

- `store_`: `store_graph`, `store_graph_source`, `store_stamp`.
- `graphql_`: `graphql_type_coordinate`, `graphql_field`, `graphql_directive_site`.
- `graphitron_`: `graphitron_table`, `graphitron_field_reference`,
  `graphitron_undecoded_argument`.
- `sql_`: `sql_table`, `sql_column`, `sql_referential_constraint`.
- `jvm_`: `jvm_class`, `jvm_method`, `jvm_record_component`.
- `java_`: `java_file`, `java_class_declaration`, `java_method_declaration`.
- `javac_`: `javac_diagnostic`.
- `walk_`: `walk_type_backing_class`.
- `intent_`: `intent_spelled_table`, `intent_bound_table`, `intent_resolved_field_claim`,
  `intent_node_type`.
- `rejection_`: `rejection_validation_error`.
- `lint_`: `lint_finding`, `lint_finding_fix`.
- `build_warning_`: `build_warning_no_rule`.
- `meta_`: `meta_family`, `meta_relation_family`, `meta_materialize`.

The selection rule applied: the family's anchor or central grain first, then the resident that
shows how the family is read, then at most one resident that shows the family's character (an
overflow, a reduction, a register). Every named relation exists in the DDL today.

**Bridge rows.** The sweep over the DDL's function-mediated match sites: every `UPPER`, `LOWER`,
`REPLACE`, `SUBSTRING`, `LEFT`, `TRIM`, `REGEXP_REPLACE` and every `*_upper` column reference in
a view body, comments stripped. Seventeen relations hold one, and every one of them is accounted
for below: four rostered, the rest reconciled in the disclosure list, so implementation
transcribes rather than re-sweeping and the follow-up inherits a complete list. Each row's rule
is one sentence. All four carry their spelled and census families in the direct read set (the
flagship's through its registered source view, which is the materialize resolution already
recorded for the follow-up), so no row needs a transitive-reach note.

| relation_name | spelled_prefix | census_prefix | rule |
|---|---|---|---|
| `intent_spelled_table` | `graphitron_` | `sql_` | A written table reference meets the catalog census by case-insensitive match on pre-normalized spelling columns, one row per candidate. |
| `intent_field_reference_step_hop` | `graphitron_` | `sql_` | A written key reference meets the constraint census case-insensitively under the resolver's namespace precedence: a qualifier binds hard, the SQL constraint name answers first, and the generated Keys-class constant is eligible only where no SQL constraint answers the name. |
| `intent_column_match_claim` | `graphql_` | `sql_` | A field's own name meets the columns of the table its site navigates to, case-insensitively, with no directive involved. |
| `intent_argmapping_key_column_candidate` | `graphitron_` | `sql_` | An argMapping path segment meets column names case-insensitively along the binding walk. |

Three relations an earlier draft rostered fail the tests against their own bodies and are out.
`intent_field_routine_method` reads the flagship's rule (it joins through `intent_spelled_table`
and then onto `sql_routine` by plain equality), so a row for it would state the flagship's rule
twice under two names. `intent_field_producer_method` matches verbatim
(`class_name`/`method_name` equality, its own column comment stating the consequence), which the
exclusion paragraph removes: no rule, nothing to fork; its spelled side is also only transitive,
through `intent_field_producer_reference`. `intent_class_member_slot` owns a real rule, the
bean-prefix strip, but reads only `jvm_` relations: it mints the member vocabulary that later
relations meet, and a `(spelled_prefix, census_prefix)` row describes a crossing, which a
normalizer that crosses nothing is not.

The remaining sites stay off the roster, reconciled here so the follow-up's predicate analysis
inherits every site the sweep found, not only the ones the roster rejected. Five carry a
normalization the analysis must classify:

- The SDL type-expression peel (bracket and bang stripping to reach a bare type name)
  normalizes SDL syntax into an SDL name, one vocabulary, so there is no family crossing to
  declare, even though the expression is carried on a `graphitron_` row where a macro rewrote
  it. It is deliberately spelled at three sites (`intent_field_column_scope_live`,
  `intent_argument_scope_table_live`, `intent_routine_return_binding`, whose comments
  cross-reference the rule), so the analysis meets it three times.
- The node-metadata column match (stated key-column strings against declared columns,
  `intent_node_metadata_defect`) normalizes within `sql_`.
- The bean-prefix strip (`intent_class_member_slot`, above): a real, forkable rule with no
  crossing. Whether a normalizer that is not a crossing gets its own register, gets declared on
  the relation that performs the eventual meeting (`intent_field_accessor_hop`, two `intent_`
  hops on), or stays a disclosure is the follow-up's design question.
- The settled case-fold convention: column names match case-insensitively wherever a reader
  does it, stated as convention on `intent_resolved_node_key_column.column_name` rather than
  owned by any relation, and applied by readers at their own crossings
  (`intent_resolved_node_key_shape`, `intent_node_id_decode_column`). No relation can own a
  bridge row for a rule the schema deliberately states as nobody's; whether the follow-up
  reifies the convention as a declarable fact is its call.
- The rostered key-reference rule's coordinate-shaped restatement:
  `intent_argument_reference_step_hop` spells `intent_field_reference_step_hop`'s comparison arm
  for arm in its own body, and its comment assigns the rule to the field-site view ("the
  duplication is the SQL body and not the rule") with an anchor test asserting the two agree.
  The roster follows that assignment, so the sibling is a recognized restatement site, not a
  second row.

Five more sites fail a test outright, one clause each. `intent_name_matched_key_pair` matches
column names case-insensitively but its own comment calls it "the schema's rule for a comparison
inside one family": no crossing. `intent_field_reference_discovery` compares two `sql_table`
names for inequality: intra-family, and not a resolution. `meta_relation_family` owns the real,
forkable prefix-match rule, but between the engine catalog and `meta_family`: no family
crossing. `intent_authored_claim_conflict`'s `REPLACE` formats a directive list into a message:
matches nothing. `diagnostic`'s seven `REGEXP_REPLACE` calls render a directory from a stored
file path: matches nothing, and worth naming precisely because a predicate walker meets them in
a view spanning six families, the most conspicuous function-over-cross-family-columns sites in
the schema that mediate no meeting at all.

The roster declares owned cross-family rules only; a normalization that fails either test is
real but a different fact, and the follow-up inherits the full reconciliation above with the
register questions.

The introductions above run one paragraph each in the friendly register the explanation pages
use; headline rosters stay at two to four rows or they stop being curation; bridge rules are one
sentence each.

## Deliverables

1. DDL: the `introduction` column, `meta_family_headline`, `meta_family_bridge`,
   `meta_relation_reference`, comments on everything new, populated per the Population section.
2. Reader: `StoreCatalog` extensions and the grain-sentence extractor with its pinned
   acceptance-line test.
3. Gates: the roster gates above; the drift-check corpus extension.
4. Renderer: the new sections in `SchemaReferencePages`, covered by `SchemaReferencePagesTest`.
5. jOOQ regeneration fallout in `graphitron-model` as the build produces it.
6. The follow-up item's stub updated to carry its inherited remit: the view-read census, the
   crossing gate with its materialize-aware resolution, the keyed-crossing register (the
   reviewer's closed-vocabulary recommendation recorded as its starting shape), the
   predicate-level analysis with the Population section's complete seventeen-site
   reconciliation (the sites it must classify and the sites that match nothing), and the
   bean-strip register question, all seeded by the declared bridges.

## Risks

- The declared bridge roster is not closed in this item: a crossing the sweep misses stays
  missing until the follow-up's gate, and nothing mechanical catches it here. Accepted
  deliberately (declaration precedes derivation) and mitigated at the render: the section
  presents declarations and never claims exhaustiveness.
- The introductions and the naming-the-row explanation page overlap in register; mitigation is
  length (one paragraph) and subject (one family), and the discriminator comment keeps rosters
  out of the prose.
- Twenty-six or more authored prose pieces invite register drift; drafting the whole population
  in this spec, reviewed as one piece at this gate, is the mitigation, with the DDL diff a
  transcription of what was approved.

## Done criteria

- `mvn install -Plocal-db` is green, gates included.
- The rendered reference under `docs/target/staging/architecture/reference/schema/` shows every
  family page opening with introduction, "Where to start" and "How this family meets the
  others", the charter under its own heading, and index blurbs reading as introductions.
- After the trunk push, the same pages are live and readable under
  `graphitron.sikt.no/architecture/reference/schema/`; that browsable page set is the definition
  of done the item exists for.
- The follow-up item's stub carries the inherited remit per deliverable 6, so nothing the
  closure needs is recorded only in this item's history.

## Reviewer findings

### Round 1, Spec → Ready, revisions requested (session_01VZWrUh1e8MWgV9E1SV7nZB, 2026-08-22)

Question 1 is answered, and answered well. What lands for a consumer is legible without reading
the deliverables: today `graphitron.sikt.no/architecture/reference/schema/intent.html` opens with
a dense naming-discipline argument for why the family is called `intent_` and then dumps every
relation in it alphabetically; after this ships the page opens with a plain-language paragraph
saying what the family holds, then a short "start here" list naming two to four relations with
one sentence each on what a row means, then a section saying how this family's rows meet other
families' rows, with the charter demoted under its own heading and the index blurbs reading as
introductions rather than charters. The viability claims hold: every symbol the spec names exists
as named and does what the spec says it does.

The two findings below are both question 2, and both are about the same seam: the registers'
grain. Neither is about naming or phrasing.

**Finding 1 (question 2). The flagship bridge row names a base table, and the crossing gate can
only see views, so the register's own worked example fails the gate it ships with.** The spec
says the flagship `meta_family_bridge` row is `intent_spelled_table`, and that the crossing gate
requires "every row of either register resolves to an observed view that actually spans families"
and that "a bridge row's two named families both appear among the families of its view's read
set". But `intent_spelled_table` is a `CREATE TABLE` (graphitron-model.sql:3284), the
materialization target of the registration `('intent_spelled_table_live', 'intent_spelled_table',
...)` in `meta_materialize`. It has no stored definition, so `meta_view_read` (defined as "this
view's stored definition directly reads this relation") holds no rows for it, and both gate
clauses reject the row on day one. The view that actually spans families is
`intent_spelled_table_live`, which reads `graphitron_table` and its four siblings,
`store_graph_source` and `sql_table`: three families.

This is not a slip in one row, because the resolution is a design choice with visible
consequences and the spec does not name it:

- If the register keys on `intent_spelled_table_live`, the gate closes uniformly, but the
  rendered "How this family meets the others" section then cross-links a relation whose own
  comment tells readers not to name it ("a reader naming this relation instead is asking for
  on-demand evaluation and will get it"). The reader-facing section would point at the half of a
  registration that consumers are meant never to spell.
- If it keys on `intent_spelled_table`, the reader-facing name is right, but the gate needs a
  materialize-aware indirection the spec does not describe: resolve a register row's relation
  through `meta_materialize` to its source view before consulting `meta_view_read`.
- If instead `meta_view_read` mints rows for a materialized target copied from its source view,
  the census's stated meaning changes from "this view's stored definition directly reads this
  relation" to something else, and the follow-up item inherits that redefinition.

The same fork reaches the keyed-crossing register and the rest of the bridge sweep, so it cannot
be settled one row at a time. There are six registrations, all in `intent_`, and the spec's own
method for finding further bridge rows is "sweeping the `intent_` views for resolutions keyed on
a written name", which is exactly the layer the registrations live in. Four of the six `_live`
source views span families today. What would satisfy this: the spec pins which relation of a
registered pair a register row names, and states how the crossing gate resolves it, in one place
that both registers and the renderer read.

**Finding 2 (question 2). The register-size question the spec hands to this gate has fired, and
the answer changes the DDL and the bulk of the authoring, so it has to be settled before Ready.**
The first risk bullet asks the Spec reviewer to weigh a stratum-level exemption "if the `intent_`
stratum yields dozens of rows". It does. Counting `FROM`/`JOIN` targets in the 72 view bodies of
graphitron-model.sql against the family prefixes gives 47 multi-family views: 46 in `intent_`,
plus `diagnostic`. That is a textual approximation rather than the AST walk, so treat it as the
order of magnitude and not the exact figure, but it is decisively "dozens" and not "a handful".

At that size the per-view register with a bespoke one-sentence `reason` per row has a failure
mode the spec's own withdrawn-draft note is alert to in the other direction: 46 near-identical
sentences saying "combines readings by coordinate on shared keys", which nobody re-reads, and
which the next author satisfies by copying a neighbour. An exemption register whose rows are
copied is a checkbox wearing an argument's clothes. The flat stratum-level exemption is worse,
though, and for the reason the withdrawn-draft note already gives: one row absolving the whole
`intent_` derivation layer auto-absolves every future resident, which is inclusion polarity
again.

My recommendation, offered as a recommendation and not as the answer: keep one row per view, so
the register keeps the gate's grain and its per-view force, but give the `reason` column a small
closed vocabulary of crossing kinds (a coordinate-equality join, a union of arms across
vocabularies, and whatever third the sweep turns up), each kind's sentence stated once on the
column comment, with the row carrying the kind plus a short note only where it differs from the
kind's sentence. Forty-six enum values that a gate can check against a closed set beat forty-six
paraphrases that it cannot. Whatever shape you pick, it belongs in the plan body before authoring
starts, because it decides `meta_keyed_crossing`'s columns and most of the item's effort.

**Non-blocking, no response needed.**

- The drift-check corpus extension overstates its reuse. "The check already boots the store and
  resolves spans; the store corpus reuses both" is right about booting and about `resolves` /
  `universeOf`, but `SchemaIdentifierDriftCheck.scan` extracts backtick spans under an AsciiDoc
  block context, and the DDL has backticks on two lines out of 7022: store comments cite
  relations in bare prose. The store corpus needs its own extractor, prefix-anchored bare tokens
  rather than delimited spans. Tractable, and the family charters' own rejected names (`jooq_`,
  `extension_`, `validator_`) fall outside the observed-prefix filter, so they are not the
  hazard they look like. It is just more than a corpus swap.
- `meta_view_read` as a keyed `meta_` base table will trip
  `FactSchemaGateTest.everyRelationLeadsWithItsPartitionDimension`, whose `meta_` arm is a switch
  hard-coding `meta_materialize` and `meta_materialize_dependency` and defaulting everything else
  to `graph_name`. One case, but the deliverables do not mention it and "without new code" in the
  Gates section reads as though nothing there moves.
- `MaterializeDependencies.relationsReadBy` is `private static`. Generalizing it means widening
  or lifting it, which the spec plainly intends; noted only so it is not read as already shared.
- "the existing comment-coverage and capture-agreement gates" names nothing in the tree under the
  second half. The closest thing is `FactSchemaGateTest`'s census-closure test, "every relation
  resolves to one family page or carries an exemption row".

### Round 1 response (author, 2026-08-22)

Revised on the item owner's direction, which cuts under both findings: the bridges are base
facts, declared like the rest of the schema; every derivation over them (census, crossing gate,
exemption register, predicate analysis) moves to the follow-up item and is seeded by the
declarations. Finding 1 is settled in the bridge section as declaration semantics: a row names
the relation a consumer reads, for a registered reduction the canonically named relation, and
the materialize-aware resolution the gate then needs is recorded as the follow-up's inherited
requirement. Finding 2 leaves this item with the register itself; the closed-vocabulary
recommendation is recorded in the follow-up's stub as its starting shape (deliverable 6). Of the
non-blocking notes, the drift-check extractor and gate-naming corrections are folded into the
body; the partition-dimension switch case and the `relationsReadBy` visibility note travel with
`meta_view_read` to the follow-up.

### Round 2, Spec → Ready, revisions requested (session_01VZWrUh1e8MWgV9E1SV7nZB, 2026-08-22)

Round 1's findings are both settled, and settled better than either of my suggestions would have
managed. Declaration precedes derivation dissolves finding 1 rather than answering it: with the
census and the crossing gate out of this item, a bridge row is a declaration about the schema and
the only question left is which relation a consumer reads, which the bridge section now answers
directly and pushes the materialize-aware resolution to the follow-up as an inherited
requirement. Finding 2 goes with it, and recording the closed-vocabulary suggestion as the
follow-up's starting shape rather than acting on it here is the right disposal. The item is
smaller and its boundary is now stated in one sentence.

Pulling the population into the spec was the right call, and it is what this round is about.

**Finding 3 (question 2). Three of the six drafted bridge rows are contradicted by the view
bodies they claim to describe, and each fails a rule this spec itself states.** The resolve gates
would pass all six: every relation is observed, every prefix pair is rostered and distinct, every
rule is non-blank. So nothing mechanical catches these, which is the argument for reviewing the
population here and the reason they have to be settled before Ready.

`intent_field_routine_method` does not own a rule. Its body joins `graphitron_routine` to
`intent_spelled_table` on the spelling and then onto `sql_routine` by plain equality on
`source_name`, `table_schema` and `routine_name`, and its own comment says so in as many words:
"How a written name meets the catalog is the spelling view's rule, stated once there". It is a
consumer of the flagship bridge, not a second bridge. Declaring it states the flagship's rule
twice under two names, which is the fork the roster exists to prevent, arriving through the
roster instead of around it.

`intent_class_member_slot` reads only `jvm_` relations (`jvm_record_component`, `jvm_class`,
`jvm_method`, `jvm_method_parameter`). Its `spelled_prefix` of `graphitron_` names a family the
relation never touches. The row's rule sentence contains two different facts welded together: the
bean-prefix strip, which is what the relation does, and "a written member name meets a backing
class", which is what some later relation does with the strip's output. The strip is a real rule
worth declaring, and a new view could genuinely fork it by respelling the `LOWER(SUBSTRING(...))`
in its own join, so I am not saying the fact is imaginary. I am saying the row shape cannot hold
it: `(spelled_prefix, census_prefix)` describes a crossing, and this is a normalization that
produces a matchable vocabulary without crossing anything. The relation where the SDL name
actually meets that vocabulary is `intent_field_accessor_hop` (`graphql_field` and
`graphitron_field_binding` against `intent_class_member_element`, which reads the slot view), and
its census side arrives through two `intent_` hops rather than directly. So this is a design
question, not a transcription slip: does the roster admit a normalizer that is not itself a
crossing, or does the bean strip get declared on the relation that performs the meeting, or does
it join the two off-roster normalizations already recorded for the follow-up?

`intent_field_producer_method` matches verbatim. Its body is
`m.class_name = r.class_name AND m.method_name = r.method_name`, and its column comment states
the consequence: "matched against the reference exactly. Java names are case-sensitive, so a
misspelling resolves to nothing rather than to a near match". The row's own rule sentence says
"verbatim", which is the admission. This spec's exclusion paragraph removes plain column equality
from the roster on the ground that the roster is "only for the rule-mediated meetings, because
those are the ones a new view can silently fork", and an identity comparison is precisely what
cannot be forked: there is no rule to re-derive differently. Separately, its spelled side is
transitive, reaching `graphitron_service` through `intent_field_producer_reference`, so
`graphitron_` is not in its direct read set either.

The three that survive are sound and I checked them the same way: `intent_spelled_table` (the
genuine spelling normalization, pre-folded `*_upper` columns with the qualified and unqualified
arms), `intent_column_match_claim` (`graphql_field` against `sql_column`, case-insensitive, both
families read directly) and `intent_argmapping_key_column_candidate`
(`graphitron_argument_path_segment` against `sql_column`, likewise).

What would satisfy this: re-run the sweep with the spec's own two exclusions applied as the test,
which is what separates the surviving three from the failing three. A relation is on the roster
when it owns a normalization rule of its own, not when it reads one; and when that rule mediates
a meeting between two families, not a normalization inside one. Then say what the roster's
honest population is. Three rows is a perfectly good roster and would not weaken the item; a
roster of six where three are wrong is worse than a roster of three, because the rendered section
would state rules the schema does not contain. If the re-sweep turns up further crossings, all
the better, but the count is not the thing to protect. Where a row's spelled or census side is
reachable only transitively, note it: the follow-up's gate at direct-read grain will not confirm
it, and that belongs in deliverable 6 beside the materialize resolution already recorded there.

**Not findings, recorded because they were checked.**

- All thirty-six relations named across the headline rosters and the bridge table exist in the
  DDL under exactly those names, so "Every named relation exists in the DDL today" holds.
- Every family has a headline roster and the ordinals are dense by list order, so the gate the
  spec proposes passes on the drafted population. `javac_`, `walk_` and `build_warning_` really
  are single-relation families, so "list their one resident" is accurate for those three;
  `rejection_` has two relations and lists one, which reads as ordinary curation rather than the
  sentence being wrong.
- The introductions honour the discriminator: none of the thirteen names a relation or another
  family, including `intent_`, which describes three other families by role without naming them.
  This is the one part of the population no gate can check, so it is worth saying that it holds.
- The two normalizations pushed off the roster (the SDL type-expression peel, the node-metadata
  column match) are correctly excluded on the distinct-family rule. That rule was applied
  carefully there, which is what makes its non-application to `intent_class_member_slot` look
  like an oversight rather than a disagreement.

### Round 2 response (author, 2026-08-22)

Finding 3 is verified against all three view bodies and accepted in full; the re-sweep is done
and the population revised. The roster is three rows, the three the finding confirms, and the
membership tests now stand in the bridge section as the rule (owns, not reads; crosses, not
normalizes), with the sweep recorded as applied so implementation transcribes rather than
re-sweeps. The three failed rows are named in the population with why each fails, so the next
draft cannot re-admit one by forgetting. The exclusion paragraph gains the verbatim-match case
`intent_field_producer_method` teaches: an identity comparison has no rule to fork.

On the `intent_class_member_slot` design question, the third disposal: the bean strip joins the
off-roster disclosures. The row shape cannot hold it, as the finding says, and the relation that
performs the eventual meeting reads the strip rather than owning it, so declaring it there would
fail the owns-not-reads test the same way `intent_field_routine_method` does. Whether a
normalizer without a crossing deserves its own register is recorded as the follow-up's question.

The re-sweep also corrected one disclosure and added one. The SDL peel's exclusion ground moves
from "within one family" to "one vocabulary": the peeled expression is carried on a
`graphitron_` row, but the rule turns SDL syntax into an SDL name, and it is spelled at three
sites the follow-up's analysis will meet. New is the settled case-fold convention, stated on
`intent_resolved_node_key_column.column_name` as nobody's rule and applied at two readers'
crossings; it fails the owns test by the schema's own words, and whether to reify it is the
follow-up's. All four disclosures and the register questions are in the follow-up's stub
(deliverable 6), not only in this item's history.

### Round 3, Spec → Ready, revisions requested (session_01VZWrUh1e8MWgV9E1SV7nZB, 2026-08-22)

Finding 3 is discharged well, and the two membership tests are a better rule than the finding
asked for: "owns, spelled in its own body, not reads" and "crosses two vocabularies, not
normalizes inside one" are sharp enough to apply mechanically, and naming the three rejected
relations with their reasons does stop a later draft re-admitting one. The two new disclosures
check out against the DDL. The case-fold convention is stated on
`intent_resolved_node_key_column.column_name` in almost the spec's words ("Matching against a
table's columns is case-insensitive wherever a reader does it, which is settled convention
rather than this relation's rule"), and `intent_node_metadata_defect` does read only `sql_`
relations, so "normalizes within `sql_`" is exact. Every symbol the round 2 response introduces
exists under the name it is given.

**Finding 4 (question 2). The sweep is presented as complete and as the thing implementation
transcribes rather than re-runs, and it misses a relation that passes both membership tests.**
The bridge section now says the population "is the sweep with both applied, so implementation
transcribes it rather than re-sweeping", which makes the sweep's coverage load-bearing in a way
it was not in round 2. I ran the sweep the spec describes, over view bodies with comments
stripped: every `UPPER`, `LOWER`, `REPLACE`, `SUBSTRING`, `LEFT`, `TRIM`, `REGEXP_REPLACE` and
every `*_upper` column reference. Seventeen views hold one. Ten are accounted for in the spec
(three rostered, one rejected, and the four disclosures across their six sites). Seven are not
mentioned anywhere, and one of them belongs on the roster.

`intent_field_reference_step_hop` reads `graphitron_field_reference_step` on the written side and
`sql_constraint`, `sql_referential_constraint` and `sql_table` on the census side, and matches
them on the pre-normalized columns in its own body: `c.constraint_name_upper =
s.key_ref_name_part_upper`, with `c.table_schema_upper = s.key_ref_namespace_part_upper` when a
qualifier was written, and a fallback onto `c.jooq_name_upper`. Its own comment states the rule
and its precedence: "a leading qualifier, split off by capture and stored beside the value, binds
hard, an unqualified name matches the SQL constraint name, and only where no SQL constraint in
this graph's sources answers that name does the generated Keys-class constant become eligible,
which is the resolver's namespace precedence rather than a looser match on either". That owns a
rule, spelled in its own body, mediating `graphitron_` spellings against the `sql_` census, with
both families in the direct read set. It passes both tests, and it is exactly the fork hazard the
roster exists for: a new view that tried `jooq_name_upper` first, or dropped the precedence, would
produce plausible rows against a different rule. It also resolves a different name against a
different census than the flagship does, so it is not the flagship's rule under a second name.

Its argument-site sibling `intent_argument_reference_step_hop` spells the same comparison in its
own body, which is a fork surface, but its comment says the rule is argued at the field-site view
and "the duplication is the SQL body and not the rule". Whether that is two rows or one row with
the sibling noted is yours; I flag it rather than settle it, since the tests as written point one
way and the DDL's own framing points the other.

The other six unaccounted sites all look correctly out to me, and I list them so the disclosure
list can be completed without re-deriving the sweep. The classification is yours; this is my
read. `intent_name_matched_key_pair` matches column names case-insensitively but its comment
already calls it "the schema's rule for a comparison inside one family", so it fails the crossing
test on the schema's own words. `intent_field_reference_discovery` compares
`arriving.table_name_upper <> departing.table_name_upper`, two `sql_table` rows, intra-family and
an inequality rather than a resolution. `meta_relation_family` matches on
`LEFT(LOWER(t.table_name), CHAR_LENGTH(f.prefix)) = f.prefix`, a real and forkable rule, but
between the engine catalog and `meta_family`, so no family crossing. `intent_authored_claim_conflict`'s
`REPLACE(g.declared, ',', ', @')` formats a directive list into a message and matches nothing.
`diagnostic`'s `REGEXP_REPLACE(x, '/[^/]*$', '')` strips a filename to a directory, seven times,
also matching nothing.

That last one is the part of this finding that is not just about the roster. The disclosure list
is framed as what the follow-up's predicate analysis "must classify", and deliverable 6 hands it
over on that basis. A predicate walker will meet `diagnostic`'s seven `REGEXP_REPLACE` calls in a
view spanning six families, which is the single most conspicuous function-over-cross-family-columns
site in the schema, and it is not in the list. `meta_relation_family`'s prefix match is the same
shape. Whatever the roster decision, the handoff is under-specified until the disclosure list
covers what the sweep actually found rather than what the roster rejected.

What would satisfy this: reconcile the sweep against all seventeen sites, put the missed row or
rows on the roster with a rule sentence, and extend the disclosures to the remainder so
deliverable 6 hands over a complete list. If a site is out, one clause saying which test it
fails is enough; the four existing disclosures are the right length.

**Not a finding.** I have now checked the roster twice from opposite directions, forward from the
three rostered rows and backward from every function site in the file, and the three rows
themselves have held both times. The re-sweep should confirm rather than disturb them.

### Round 3 response (author, 2026-08-22)

Finding 4 accepted. I re-ran the sweep with the reviewer's wider function list over the
comment-stripped DDL and reproduced exactly seventeen relations, then verified
`intent_field_reference_step_hop`'s KEY arm against its body: the qualifier-binds-hard,
SQL-name-first, Keys-constant-only-on-absence precedence is spelled there with its own
anti-exists, `graphitron_field_reference_step` and the constraint census both in the direct read
set, and `key_matched_by` making the precedence visible data. It is the fourth roster row, and
it resolves a different name against a different census than the flagship, so it is not the
flagship's rule renamed.

The sibling question is settled by the DDL's own words: `intent_argument_reference_step_hop`'s
comment assigns every arm and every precedence to the field-site view, calls its own body a
duplication of SQL and not of rule, and an anchor test already asserts the two agree. The roster
follows the schema's stated ownership, so one row plus a disclosed restatement site, and the
membership tests gain the clause that says so, since the owns test as previously written would
have read the sibling's textual body as ownership.

The disclosure list is now the complete reconciliation of all seventeen sites: the five
disclosed normalizations (the restatement joins the four from round 2) and the five sites that
fail a test or match nothing, each with its one clause, `diagnostic`'s seven directory renders
named as the conspicuous case the follow-up's walker must classify as matching nothing.
Deliverable 6 and the follow-up's stub now hand over that full reconciliation rather than only
what the roster rejected. The three previously rostered rows are untouched, as the round's
closing note expects.

### Round 4, Spec → Ready, signed off (session_01VZWrUh1e8MWgV9E1SV7nZB, 2026-08-22)

Finding 4 is discharged and the spec is Ready. Everything the round 3 response asserts checks out
against the DDL:

- `intent_field_reference_step_hop`'s KEY arm is spelled as described. The qualifier arm binds
  both `table_schema_upper` and `constraint_name_upper`; the unqualified arm answers on
  `constraint_name_upper` first and admits `jooq_name_upper` only under a `NOT EXISTS` over
  `sql_constraint` for the same name in the graph's sources. The roster's rule sentence is an
  accurate one-sentence statement of exactly that.
- `key_matched_by` exists, carries `SQL_NAME`/`JOOQ_NAME`, and its comment says it "Makes the
  resolver's namespace precedence visible data instead of a hidden pick".
- The anchor test is real and stronger than the response claims for it.
  `ArgumentReferenceStepTargetTest` "additionally binds the two views to each other: the arms are
  textually parallel, so one case seeds one path shape at both sites and asserts the shared
  columns come back equal". That is what makes the one-row disposal sound rather than merely
  consistent with the comment: the sibling's body cannot fork silently, because a fork fails that
  test. The new membership clause rests on a mechanism, not on prose.
- The seventeen-site reconciliation is arithmetically complete: four rostered, eight across the
  five disclosed normalizations, five failing a test outright, seventeen exactly, with no site
  named twice and none left over.

I also re-ran the roster question against a deliberately wider net than the sweep's own, since
the completeness claim is one I helped set the boundary for: every `LIKE`, string concatenation,
`POSITION`, `COALESCE`, `CAST`, `NULLIF` and length function appearing in any view's join or
filter predicate, not only the name-matching set. Eleven further views hit that net and none is a
crossing. The `LIKE`s are population filters (an Apollo spec URL prefix, the `\_%` introspection-name
exclusion); `intent_authored_field_claim`'s `||` and `POSITION` build and test a path string for
cycle detection inside one vocabulary; the `COALESCE`s are null-fallbacks choosing which authored
value to look up. `intent_bound_table` is the closest call and still out: it joins
`intent_spelled_table` on `COALESCE(t.table_ref, t.type_name)`, which reads the flagship's rule
rather than owning one, the same way `intent_field_routine_method` does. So the roster of four is
complete under the two membership tests, checked forward from the rows and backward from every
function site in the file.

**Non-blocking, for deliverable 6's execution rather than for this spec.** Those eleven views are
outside the seventeen because the sweep is scoped to function-mediated *match* sites, which is the
right scope for the roster and the claim the spec actually makes. But deliverable 6 hands the
reconciliation over as what the predicate analysis "must classify", and a predicate walker's net
is the wider one. At least one of the eleven meets the follow-up's remit as its stub currently
words it: `intent_bound_table` applies `COALESCE` to a `graphitron_table` column and compares it
against an `intent_spelled_table` column, which is a function over columns tracing to two
families' relations, outside a bridge-registered view. Its disposition is easy, and the follow-up
owns defining its own predicate boundary by its stub's own words ("The Spec must pin what counts
as a crossing predicate"), so this is not a gap in R800. It is worth one line when the stub is
written, so the follow-up's author knows the inherited list was drawn at match sites and their
own sweep will be wider.

Signing off. Question 1: a reader who opens a family page will get a plain-language introduction,
a short curated "start here" list with a grain sentence per entry, and a statement of how the
family's rows meet other families' rows, with the charter demoted and the index blurbs
rewritten. Question 2: every artifact extends a shape already in the tree, the population is
drafted and now verified against the schema it describes, and the boundary with the follow-up is
stated in one sentence and honoured in both directions.
