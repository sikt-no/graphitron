---
id: R800
title: "Family pages open with an introduction, main grains and bridge roster"
status: Backlog
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
not as prose an author writes about the store, so that the sanctioned paths are at least named in
one queryable place now and mechanically closable later.

## Scope

This is the first delivery, aimed at the documentation. In scope: an authored introduction per
family, an authored main-grains roster per family, an authored bridge roster for the sanctioned
cross-family normalization crossings, the derived cross-family foreign-key edges, and the renderer
sections that put all of it on the family pages. The definition of done is that the result is
browsable on the public site.

Explicitly deferred to a follow-up item: the mechanical closure of the bridge roster. That means
generalizing the `MaterializeDependencies` AST walk into a machine-written census of every view's
read set, and a predicate-level gate that rejects function application over cross-family columns
outside a registered bridge. Until that lands, the bridge roster is resolve-gated but not
crossing-closed, and review covers the gap; the roster existing first is what gives the follow-up
gate something to close against.

## The three authored artifacts

All three live in `graphitron-model`'s DDL (`graphitron-model.sql`), following the standing rule
the renderer stamps on every page: the DDL is the only authored source, so nothing here is a
committed AsciiDoc fragment.

**1. `meta_family.introduction`, a new column on the roster view.** Friendly prose that presents
the family to a first-time reader: what its rows transcribe or derive, in plain language, one
short paragraph. It complements the charter rather than replacing it: the introduction presents,
the charter defends the name. Both render (layout below). Like the other meta prose it is a SQL
string literal in the VALUES view, authored in the accepted inline AsciiDoc subset.

**2. `meta_family_grain`, a new authored roster view: `(prefix, relation_name, ordinal)`.** One
row per headline relation of a family, curation by judgment: the relations a reader should meet
first, typically the family's central grain plus the one or two derivations that make it useful.
Which relations are headline cannot be derived, so the membership is authored; what each one *is*
already exists as that relation's own `COMMENT ON` text, so no second blurb column that could
drift. `ordinal` orders the list within a family.

**3. `meta_family_bridge`, a new authored roster view: `(relation_name, from_prefix, to_prefix,
rule)`.** One row per sanctioned cross-family normalization crossing: a place where two families'
vocabularies meet through a rule rather than through plain equality on a shared key. The row
names the relation that owns the rule and states the rule in one sentence. Direction matters and
the columns say so: `from_prefix` is the family whose spelling is being resolved, `to_prefix` the
family it resolves against. The flagship row is `intent_spelled_table` from `graphitron_` to
`sql_` ("a written table name meets the catalog by spelling normalization"). Implementation
enumerates the rest by sweeping the `intent_` views for resolutions keyed on a written name (the
family charter already names that layer); each found crossing becomes a row.

Crossings by plain column equality on a shared natural key deliberately get no roster. A foreign
key is already a declared, engine-checked join path, and a coordinate-equality join between
capture families is the ordinary case the whole `intent_` stratum exists for. The bridge roster is
only for the rule-mediated meetings, because those are the ones a new view can silently fork.

## The derived edges

The cross-family foreign-key relationships render from a new derived view,
`meta_family_fk_edge (from_prefix, to_prefix, fk_count)` or equivalent, defined over
`INFORMATION_SCHEMA.REFERENTIAL_CONSTRAINTS` joined through `meta_relation_family` on both ends
and filtered to rows whose two families differ. A store view rather than renderer-side
aggregation, on the `meta_relation_family` precedent: the census view exists so that consumers of
different fidelity can never answer the same question differently, and "which families reference
which" is exactly such a question (the reference renders it today; the follow-up gate and any
future MCP/LSP surface will want the same answer). `StoreCatalog` reads it like the other meta
relations.

## Renderer and reader changes

`StoreCatalog`: `Family` gains `introduction`; new records `Grain(prefix, relationName, ordinal)`,
`Bridge(relationName, fromPrefix, toPrefix, rule)` and `FamilyFkEdge(fromPrefix, toPrefix,
count)`, each read from its view in the fixed-order style the reader already uses.

`SchemaReferencePages`, family page layout, in order:

1. Title, prefix line (unchanged).
2. The introduction.
3. "Main grains": a labeled list from the grain roster, each entry the relation name linked to
   its same-page anchor, followed by the first sentence of that relation's own comment. First
   sentence means the text up to the first period followed by whitespace or end-of-text; the
   comments are gated renderable prose, and a blank extraction is a `BuildFailure` like the other
   renderer floors.
4. "How this family meets the others": the bridge rows where the family participates in either
   direction (each rendered as the rule sentence with the owning relation cross-linked to its
   page), then the derived foreign-key edges as one compact line per direction ("rows here
   reference `sql_` through 2 foreign keys; referenced by `intent_`"). Families with no bridges
   and no cross-family keys render an honest one-liner saying so rather than an empty heading.
5. "Why the name is right": the charter, under its own heading now that it no longer serves as
   the page's opening.
6. The relations (unchanged).

The index page's per-family blurb switches from the charter to the introduction; the charter
stays on the family page only. The index's own preamble needs no change.

## Gates

All in the store's own gate suite beside the existing family-roster gates (`FactSchemaGateTest`
holds the roster well-formedness, home-resolution and exemption-resolution gates today;
`CommentRenderabilityGateTest` holds the prose subset):

- `meta_family.introduction` is non-blank for every family, and every new prose value
  (introductions, bridge rules) passes the renderability subset. The renderability gate already
  scans "every meta prose value"; verify the new columns fall inside its sweep and extend it if
  its column enumeration is explicit.
- Every `meta_family_grain` row resolves: its prefix is in the roster, its relation is observed,
  and the relation's census family (`meta_relation_family`) equals the stated prefix, so a grain
  is always a resident of the family that claims it. Ordinals are unique within a prefix. Every
  family has at least one grain row, so no page can render an empty promise.
- Every `meta_family_bridge` row resolves: the relation is observed, both prefixes are in the
  roster, `from_prefix` differs from `to_prefix`, and the rule is non-blank. Resolve-only by
  design in this item; the crossing-closure gate is the follow-up's.
- The new relations themselves carry full `COMMENT ON` coverage and registration, which the
  existing comment-coverage and capture-agreement gates enforce without new code.

The renderer keeps its own floors: blank introduction, empty grain list, or blank first-sentence
extraction fail the docs build loudly rather than rendering a hollow page.

## Authoring work

Thirteen families each need an introduction and a grain roster; the bridge roster needs the
`intent_` sweep. This is the bulk of the item's effort and it is writing, not code. The
introductions are authored in the friendly register the explanation pages use (simple language,
no zealotry), one paragraph each; the grain rosters should stay short (two to four rows per
family) or they stop being curation.

## Deliverables

1. DDL: the `introduction` column, the two authored rosters populated for all families, the
   derived FK-edge view, comments on everything new.
2. Reader: `StoreCatalog` extensions with test coverage in its existing test.
3. Gates: the new assertions in the store's gate suite as listed above.
4. Renderer: the new sections in `SchemaReferencePages`, covered by `SchemaReferencePagesTest`.
5. jOOQ regeneration fallout in `graphitron-model` as the build produces it.

## Risks

- The introductions and the naming-the-row explanation page overlap in register; the mitigation
  is length (one paragraph) and subject (one family) so the page-level prose never restates the
  doctrine, it presents the residents.
- First-sentence extraction assumes relation comments open with a self-contained sentence. They
  are authored under exactly that discipline ("one row per X ..."), and the renderer floor turns
  any counterexample into a loud build failure at the moment it is authored, not a silent bad
  render.
- The bridge roster lands without mechanical closure, so a rogue normalization view is still
  possible until the follow-up gate. Disclosed above; the roster is still strictly better than
  today, where the sanctioned path is not stated anywhere queryable.
- Thirteen authored introductions invite register drift between families. Single-session
  authoring and review of the DDL diff as one piece is the mitigation.

## Done criteria

- `mvn install -Plocal-db` is green, gates included.
- The rendered reference under `docs/target/staging/architecture/reference/schema/` shows every
  family page opening with introduction, main grains and family-relationships sections, and the
  index blurbs read as introductions.
- After the trunk push, the same pages are live on the public site under
  `graphitron.sikt.no/architecture/reference/schema/`, browsable and readable there; that page
  set is the definition of done the item exists for.
- The follow-up item (view-read census plus predicate-level bridge closure) is filed as a Backlog
  stub so the deferral has an address.
