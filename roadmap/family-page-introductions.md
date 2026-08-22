---
id: R800
title: "Family pages open with an introduction, main grains and bridge roster"
status: Spec
bucket: docs
depends-on: []
created: 2026-08-21
last-updated: 2026-08-21
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
family relationships should therefore live in the store as registered rows the reference renders,
not as prose an author writes about the store, and the register has to close against what the
views actually do, in the exemption polarity the schema gates use throughout: a new cross-family
view fails the gate until an authored row argues it in.

## Scope

First delivery, aimed at the documentation; the definition of done is that the result is
browsable on the public site. In scope: an authored introduction per family, an authored headline
roster per family, the authored bridge roster with its relation-grain closure (a machine-written
view-read census plus the crossing gate), a derived foreign-key reference view, the renderer
sections that put all of it on the family pages, and the drift-check extension that keeps the new
prose honest.

Deferred to the follow-up item (`view-read-census-and-bridge-closure`, retitled at its own Spec
to match): the predicate-level analysis only, that is, walking join and filter conditions to
reject function application over cross-family columns outside a registered bridge. The
relation-grain closure below is coarser (it sees which relations a view reads, not how it
compares them), which is exactly the gap the follow-up closes.

An earlier draft of this spec deferred the whole closure and gated the bridge roster
resolve-only. Withdrawn on principles consult: every sibling `meta_` roster closes against the
observed schema in exemption polarity, and a bridge roster without its closing census has
inclusion polarity, a section that renders as the complete set of sanctioned crossings while
being silently partial. The closure at relation grain is cheap enough to belong here because the
hard half already exists in `MaterializeDependencies.relationsReadBy`.

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
one sentence. The flagship row is `intent_spelled_table`; implementation enumerates the rest by
sweeping the `intent_` views for resolutions keyed on a written name (the family charter already
names that layer).

**4. `meta_keyed_crossing`, the exemption side of the closure:
`(relation_name, reason)`.** One row says: this view's direct read set spans more than one
family, and every cross-family meeting in it is plain equality on shared keys or a read through
a registered bridge; the reason says which. This is the roster that gives the crossing gate its
exemption polarity: the ordinary multi-family readers (the `intent_` stratum combining readings
by coordinate, `diagnostic` unioning arms) get their rows once, and a new cross-family view
fails the gate until it argues itself into exactly one of the two registers. The register is
gate material and does not render in this item.

Crossings by plain column equality on a shared natural key get no bridge row by design. A
foreign key is already a declared, engine-checked join path, and a coordinate-equality join
between families is the ordinary case the whole `intent_` stratum exists for. The bridge roster
is only for the rule-mediated meetings, because those are the ones a new view can silently fork.

## The derived artifacts

**`meta_view_read`, the machine-written read census.** One row says: this view's stored
definition directly reads this relation. Written by a boot-time routine in the
`meta_materialize_dependency` one-writer pattern (a hand edit is a bug), reusing the walk
`MaterializeDependencies.relationsReadBy` already implements: jOOQ's parser over the stored
`VIEW_DEFINITION`, collecting the table parts whose qualified name is `PUBLIC` plus one segment,
so aliases and CTE names cannot mint rows. Generalized from the materialize registrations to
every view in the census; whether `MaterializeDependencies` then re-derives its edges from this
relation instead of re-parsing is an optional simplification, not a requirement.

**`meta_relation_reference`, the declared key edges as a view.** One row per declared foreign
key: child relation, child family, parent relation, parent family, defined over
`INFORMATION_SCHEMA` (`REFERENTIAL_CONSTRAINTS` resolved through `KEY_COLUMN_USAGE` the way
`StoreCatalog.foreignKeysByRelation` already does) joined through `meta_relation_family` on both
ends. At constraint grain, deliberately: cross-family is a predicate a reader applies, not a
population filter baked into the relation, and the name says what a row is rather than where it
renders. A store view rather than renderer-side aggregation because `StoreCatalog`'s contract is
that family assignment is never re-derived outside the census, and because the crossing gate and
the MCP schema surface are second readers of the same answer.

## The crossing gate

In the store's gate suite beside the existing family-roster gates: every view whose
`meta_view_read` rows span two or more families appears in exactly one of `meta_family_bridge`
or `meta_keyed_crossing`; every row of either register resolves to an observed view that
actually spans families; and a bridge row's two named families both appear among the families of
its view's read set. Both directions closed, so the registers can neither miss a crossing nor
carry a stale row. The gate is coarse on purpose: it cannot see how a view compares the columns
it reads, only that it reads across families, which is why the keyed-crossing register exists
and why the predicate-level refinement is the follow-up item.

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
  family has at least one headline row; bridge and keyed-crossing rows resolve as the crossing
  gate above requires, `spelled_prefix` and `census_prefix` rostered and distinct, `rule` and
  `reason` non-blank.
- The renderability subset needs no new work and none is planned: the meta-prose sweep is
  already total over every character-typed value of every `meta_` relation, so the new columns
  join it by existing.
- Comment coverage and registration of the new relations are enforced by the existing
  comment-coverage and capture-agreement gates without new code.
- `SchemaIdentifierDriftCheck` gains the store's own prose as a second corpus: the new columns
  exist to cite relation names (`rule` inherently, introductions and reasons naturally despite
  the discriminator), and today the check scans only `docs/architecture`, so a rename would
  leave gated name columns correct and the prose beside them silently wrong. The check already
  boots the store and resolves spans; the store corpus reuses both. If the sweep surfaces
  pre-existing rot in old comments, fixing those citations is in scope.

## Authoring work

Thirteen families each need an introduction and a headline roster; the bridge roster needs the
`intent_` sweep; the keyed-crossing register needs a row per existing multi-family view, which
the crossing gate itself enumerates on first run. This is the bulk of the item's effort and most
of it is writing. The introductions are authored in the friendly register the explanation pages
use, one paragraph each; headline rosters stay short (two to four rows) or they stop being
curation; keyed-crossing reasons are one sentence each.

## Deliverables

1. DDL: the `introduction` column, `meta_family_headline`, `meta_family_bridge`,
   `meta_keyed_crossing`, `meta_view_read`, `meta_relation_reference`, comments on everything
   new, rosters populated for all families.
2. Boot: the `meta_view_read` writer reusing the existing parse walk.
3. Reader: `StoreCatalog` extensions and the grain-sentence extractor with its pinned
   acceptance-line test.
4. Gates: the roster and crossing gates above; the drift-check corpus extension.
5. Renderer: the new sections in `SchemaReferencePages`, covered by `SchemaReferencePagesTest`.
6. jOOQ regeneration fallout in `graphitron-model` as the build produces it.

## Risks

- The keyed-crossing register's size is unknown until the census runs; if the `intent_` stratum
  yields dozens of rows, the one-sentence reasons are still bounded work, but the Spec reviewer
  should weigh whether a stratum-level exemption (one row arguing a family's whole derivation
  layer) is a better shape before authoring begins. The per-view register is the default because
  it matches the gate's grain.
- The introductions and the naming-the-row explanation page overlap in register; mitigation is
  length (one paragraph) and subject (one family), and the discriminator comment keeps rosters
  out of the prose.
- The bridge roster still cannot see predicates; a view could read through a bridge and add its
  own sloppy comparison beside it. Disclosed; the follow-up item owns it, and until then that
  narrower gap is review's.
- Sixteen or more authored prose pieces invite register drift; single-session authoring and
  review of the DDL diff as one piece is the mitigation.
- Store boot gains a parse walk over every view at creation; the walk is per-created-store like
  the dependency walk today, and the store-performance rubric applies if it shows up in build
  wall-clock.

## Done criteria

- `mvn install -Plocal-db` is green, gates included, the crossing gate closing both directions
  with zero grandfathered views.
- The rendered reference under `docs/target/staging/architecture/reference/schema/` shows every
  family page opening with introduction, "Where to start" and "How this family meets the
  others", the charter under its own heading, and index blurbs reading as introductions.
- After the trunk push, the same pages are live and readable under
  `graphitron.sikt.no/architecture/reference/schema/`; that browsable page set is the definition
  of done the item exists for.
- The follow-up item's stub reflects the narrowed remit (predicate-level analysis over a census
  that now exists).

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
