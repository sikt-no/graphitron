---
id: R427
title: "Type-ahead search backed by native database indexes"
status: Ready
bucket: architecture
priority: 8
theme: search
depends-on: []
created: 2026-07-02
last-updated: 2026-07-16
---

# Type-ahead search backed by native database indexes

> Origin: [issue #512](https://github.com/sikt-no/graphitron/issues/512)
> ("annotering av flere felter for å auto-generere søkeindeks"). Filed
> 2026-07-02 as a thinking-capture; rewritten 2026-07-15 after a design
> discussion closed the forks and an empirical jOOQ-meta probe (see *What
> the generated jOOQ meta can and cannot see*) resolved the last gating
> question; expanded to a full Spec the same day. The framing sections
> (demand/supply, decisions, supply facts, meta findings) are retained as
> the design's rationale; the *Design* section onward is the plan. The
> dead-ends section records the roads not taken so they are not
> relitigated.

## Problem

Consumers need free-text search across several fields of an entity (the
canonical case: match on both a short *code* and a *name/description*),
usually combined with predefined filters, to power frontend comboboxes where
a user types a few characters, gets ranked candidates, and links one to
another entity. The requester confirmed
([comment, 2026-07-14](https://github.com/sikt-no/graphitron/issues/512#issuecomment-4968282258))
that **bounded top-N (~20 hits) is sufficient**: comboboxes are used by
searching, not by scrolling, and complete-dataset browsing happens on
separate table pages served by ordinary keyset connections. Deep paging
through ranked results is not a requirement.

## The settled framing: demand side, supply side

**The search index is a pre-existing, consumer-owned schema object, and it is
a bearer of facts.** It is not something graphitron defines; it is something
graphitron *reads*, exactly like the jOOQ catalog (of which it is a region:
tsvector columns, GIN/GiST indexes, and search tables are all catalog
objects). The consumer's migrations own the index, its coverage, its
weighting, and its analyzer configuration. The SDL maps onto this
predetermined internal schema, and the index's facts flow in and constrain
what graphitron can generate.

- **The SDL is the demand side**: "a search surface exists here and returns
  hits of these types."
- **The index is the supply side**: what matching and ranking the backing can
  honestly deliver.
- **Graphitron is the type-checker between them**, which is already its job
  between SDL and the jOOQ catalog. The shipped precedent in miniature is
  `@order(index:)`: the SDL names an index it does not define,
  `catalog.findIndexColumns` supplies the facts (its columns), and a failed
  lookup is a rejection.

**The flow-in-only law.** Supply facts flow *in* as validation and strategy
selection; they never flow *out* as inferred SDL shape. The author writes the
demand; the generator checks the backing can serve it (relevance ordering
demanded against a backing that cannot rank is an `AuthorError` at build
time, surfaced by the LSP as the author types); the frontend never sees which
supply won. Any capability that would change SDL shape (e.g. highlighting via
`ts_headline`, if ever wanted) must be an authored opt-in validated against
the supply, never something generated because the backing happens to support
it. This is how "internal facts affect us" coexists with the stable-SDL
principle: the SDL is stable across *backed* strategies and across backing
migrations, because supply facts gate generation but never mutate the
contract.

## Decisions (closed forks)

1. **Dedicated generated search field, not a mode on existing connections.**
   The earlier alternative (an `orderBy: RELEVANCE` enum value on
   `@asConnection` fields) is rejected: it encodes the mode in argument
   *values* rather than schema *shape*, which GraphQL cannot validate
   (argument correlation), forces a dishonest hardcoded
   `pageInfo.hasNextPage: false`, and puts two pagination strategies behind
   one field selected per request, against the "strategy is derived from the
   schema at codegen time" discipline. The search surface is a separate
   field, synthesized by graphitron (the R13 `@asFacet` synthesis machinery
   is the shipped precedent), so authoring cost stays one directive.
   **Re-affirmed 2026-07-16 (split revisit)**, with three arguments that
   did not exist when the fork closed: the pinned 0.4 threshold makes
   match-without-rank defective under our own recipe (the noise it admits
   is only acceptable because ranking sinks it and the top-N bound cuts
   it; a filter-only fuzzy match returns that noise interleaved in keyset
   order); the Oracle compiler makes the search string a *compiled input*,
   categorically not a `@field`-mapped filter value; and the hit wrapper's
   forward bet (score, highlighting as sibling fields of `node`) has no
   landing zone on a connection's edges. The one real cost of the split,
   the admin-table quick-filter case, is a recognized third demand in
   Non-goals, not a merge argument.
2. **Result shape: bounded top-N hit list.** A hit wrapper type
   (structurally an edge: `node` plus room for future hit-level metadata),
   no `pageInfo`, no cursors, a capped `first:`-style limit. **No relevance
   score field in v1**: raw scores (`ts_rank`, trigram similarity, Oracle
   `SCORE`) are strategy-dependent magnitudes only comparable within one
   query; exposing them invites clients to threshold on values that change
   scale when the backing migrates, which would break the stable-contract
   promise. Ordering is the contract; the score is not.
3. **Backed-only: graphitron does not shim search.** No naive `ILIKE`-OR
   fallback arm. A shim would not scale, and as the zero-effort default it
   would be an enticing dead end that consumers adopt and then outgrow
   painfully (a design smell). Requiring real backend/database work is the
   opinionated choice: the feature *requires* a native search backing, and
   the developer documentation carries canonical, copy-pasteable migration
   recipes per platform so the required DB work is cheap and unambiguous.
4. **One backing mechanism per dialect, both native to the database**
   (revised 2026-07-16, grain round): `pg_trgm` string similarity on
   PostgreSQL (partial words, typos, index-ordered KNN top-N; available
   on Amazon RDS, the deployment constraint, see *The Postgres extension
   landscape and RDS*) and Oracle Text on Oracle (`CONTAINS()` +
   `SCORE()` over a `CTXSYS.CONTEXT` domain index, with the
   generator-owned compiled query). Type-ahead demand plus build dialect
   determine the mechanism uniquely, which is why the directive carries
   no mechanism argument (see the SDL section). Core `tsvector` full text
   is *not* a type-ahead mechanism (it cannot match partial words,
   verified 2026-07-16) and belongs to the future prose-search feature.
   **The supported PostgreSQL floor for this feature is 17** (stated by
   the item owner 2026-07-16); the repo's CI and Testcontainers already
   pin PostgreSQL 18 (`postgres:18` service, `jdbc:tc:postgresql:18`),
   and the 17/18 release notes contain no `pg_trgm` or core-FTS
   behaviour changes that touch the claims verified during drafting (the
   drafting sandbox's native Postgres is 16; the execution tier re-pins
   every claim on the repo's pinned image, see Tests). Specialized
   engines external to the database (Elasticsearch, OpenSearch, etc.)
   are **out of scope** unless real demand appears. We are opinionated
   about what we support; unsupported backing shapes are rejected at
   build time (the R13 `rejectFacetMisuse` pattern).
5. **Weights and analyzers are index-time facts, not SDL.** Per-field
   weighting lives in the consumer's `setweight(...)` expression (or Oracle
   Text preferences), authored in their migrations. An SDL weight argument
   would be either dead text (backed case) or a private scoring model we
   invented; both are wrong.
6. **The Slice A idea (a `search:` filter argument on existing
   filter/connection fields) is dead**, both in its naive-`ILIKE` form
   (decision 3) and as a backed filter arm: search lives exclusively on
   the dedicated type-ahead field. One concept, one surface, simpler to
   teach. Re-affirmed 2026-07-16 together with decision 1; the
   quick-filter demand it might someday serve is named in Non-goals.
7. **Ranked-offset pagination is descoped** (see the archived design note
   below). The requester's top-N answer removes the only driver for a second
   pagination strategy.
8. **Multi-type search is a backing shape, not a feature we design.** A
   consumer-owned search table with an `entity_type` discriminator column
   *is* the multi-type case; recognizing it means mapping discriminator
   values to GraphQL types (the existing row-domain discrimination
   machinery) and joining hits back to entity tables (the node-key
   machinery). **Descoped for v1** (no requester), but the binding grain
   below is chosen so it lands additively later.
9. **V1 serves exactly one demand: type-ahead.** (Grain round,
   2026-07-16.) The vocabulary is pinned: **type-ahead** is combobox
   entity lookup (raw keystrokes in, ranked entity candidates out; the
   confirmed #512 demand), **prose search** is weighted document
   relevance over longer text (a query expression in, ranked documents
   out). These are different use cases with different input contracts,
   different result affordances, and different requesters, and the design
   stops pretending otherwise: the directive is demand-named
   (`@typeahead`), the synthesized `search:` argument is raw keystrokes
   on every platform (never a query language), and prose search is
   requester-gated future work expected to land as a *sibling directive*
   (the name `@search` is deliberately left unclaimed for it), designed
   from information-retrieval demand rather than inheriting the combobox
   contract. See Non-goals.

## Supply facts the backing bears

The facts that flow in, and what each gates:

- **Coverage**: which columns feed the vector, hence what a match honestly
  means. Extending coverage is a consumer migration; the SDL reflects it.
- **Capability set**: whether the backing can boolean-match, rank, rank *in
  the index* (trigram `<->`) versus materialize-then-sort (`ts_rank`),
  tolerate typos. Gates what the demand side may ask for.
- **Analyzer semantics**: language configuration, stemming. Opaque to
  graphitron by design; it changes what matches, not the contract.
- **Entity multiplicity**: single-entity backing (a tsvector column on one
  table) versus multi-entity backing (a search table with a discriminator).
- **Freshness**: trigger-fed or materialized backings lag their sources, and
  Oracle Text `CONTEXT` indexes are stale-by-default between `SYNC` runs
  (the canonical Oracle migration pins `SYNC (ON COMMIT)`, approximating
  the Postgres transactional story; consumers who choose `SYNC (EVERY ...)`
  accept the window). Consequence: hits may reference dead rows, so the
  hit-to-entity join-back
  is an inner join that drops them, and "asked for 20, got 18" is documented
  behaviour, not a bug.

## Design

### Authored SDL surface

The author writes a root list field returning the entity type and binds the
backing with one directive application, mirroring `@asConnection`'s
author-writes-field / generator-owns-type pattern:

```graphql
type Query {
  # combobox type-ahead over short code/name text:
  filmSearch: [Film!]! @typeahead(column: "title")
}
```

Graphitron then synthesizes the rest of the surface (next section), so the
wire shape the frontend sees is:

```graphql
type Query {
  filmSearch(search: String!, first: Int): [QueryFilmSearchHit!]!
}

type QueryFilmSearchHit {
  node: Film!
}
```

The directive, in house style:

```graphql
"""
Declares a type-ahead search surface on a root list field: the client sends
raw keystrokes, the database returns a ranked, bounded top-N of entity
candidates. Matching and ranking run against a consumer-owned backing
(pg_trgm on PostgreSQL, Oracle Text on Oracle; the mechanism follows from
the build dialect); graphitron synthesizes the search arguments and the
per-field Hit wrapper type. Requires a database migration owned by the
consumer; see the type-ahead how-to for the per-platform recipe.
"""
directive @typeahead(
  """SQL name of the backing text column on the entity's table: the searched column itself or a stored generated concatenation (PostgreSQL), or the column carrying the Oracle Text CONTEXT index (Oracle)."""
  column: String!
  """Hits returned when the client omits 'first'."""
  defaultFirst: Int = 20
  """Upper bound on 'first'. A request exceeding it is a client-visible error."""
  maxFirst: Int = 100
) on FIELD_DEFINITION
```

**There is no mechanism argument, because demand plus dialect determine
the mechanism uniquely** (grain round + principles-architect consult,
2026-07-16). On PostgreSQL, type-ahead means `pg_trgm` string similarity:
partial words and typos match (`mat` finds "Matrix"), matching is
case-insensitive by construction (trigrams extract lowercased), and the
top-N is index-ordered (KNN off the GiST index); core FTS cannot serve
type-ahead (`websearch_to_tsquery` has no prefix operator, verified
empirically 2026-07-16). On Oracle, type-ahead means Oracle Text with the
generator-owned compiled query. A `kind:`-style argument would restate the
dialect the generator already builds against: noise with a contradiction
surface (`kind: TRIGRAM` on an Oracle build), not an assertion. The one
fact such an argument nominally asserted, "I ran the canonical migration",
never had a build-time enforcer anyway (the index is invisible to the
catalog); it is enforced where it always really was, the boot-time supply
probe. If a dialect ever gains a *second* type-ahead-capable mechanism,
the disambiguator lands then as an optional `mechanism:` argument
**defaulting to the incumbent**, so existing SDL classifies unchanged
(additive, not a cutover).

The generated SQL shape is still selected at *build* time, never switched
at runtime: the classifier reads the build dialect once, through the
`JooqCatalog` boundary (never a fresh `SQLDialect` read downstream), and
carries the decision as the sealed `TypeaheadBacking` arm that the emitter
and validator switch on; neither re-branches on dialect. "Strategy is
derived from the schema at codegen time" stays intact; the build's dialect
is a parse-boundary input exactly as the catalog itself is.

### Synthesis (rides the R13/connection promoter path)

`ConnectionPromoter`'s field-first walk (`synthesiseForField`,
`ConnectionPromoter.java:140`) gains a `@typeahead` arm beside the
`@asConnection` arm:

- Synthesize the **hit wrapper type** `<ParentType><FieldNameUcFirst>Hit`
  (naming helper beside `ConnectionNaming` / `FacetNaming`) as a
  `GraphQLObjectType` with the single field `node: <Entity>!`, registered
  through the existing `registerSynthesised` path
  (`ConnectionPromoter.java:217`) and pinned as an `additionalType` by
  `rebuildAssembledForConnections` (`:239`) exactly as Connection / Facets
  types are. One hit type per field, never shared, per the
  `connectionName:`-deprecation lesson.
- Rewrite the carrier field (the `rewriteCarrierField` precedent, `:322`):
  return type becomes `[<Hit>!]!` (non-null list, non-null elements; no
  matches is an empty list, never null) and the two synthesized arguments
  are appended: `search: String!` and `first: Int`.
- A new `GraphitronType.TypeaheadHitType` model record (beside
  `ConnectionType` / `FacetsType` / `FacetValueType`,
  `GraphitronType.java:552-630`) carries the schema form;
  `ObjectTypeGenerator.graphqlTypeFor` (`ObjectTypeGenerator.java:120`) and
  the SDL emitter gain the corresponding arm.

The hit wrapper exists for forward evolution (hit-level metadata such as an
opt-in score or highlight can land as new fields without a breaking change,
where `[Film!]!` → `[Hit!]!` later would be one); in v1 it carries only
`node`, resolved by a passthrough fetcher mirroring
`ConnectionHelper.edgeNode`. That forward bet is the only thing holding the
wrapper up: if score and highlighting are ever firmly discarded rather than
deferred, revisit the wrapper before more consumers depend on the shape.

### Classification (facts, not a new leaf)

Per R333's additive-facts discipline, no new field leaf is minted:

- A **`TypeaheadSpec` model record** is the carrier, mirroring how R13's
  `FacetSpec` rides `ConnectionType` as a denormalized view carrier:

  ```java
  public record TypeaheadSpec(
      ColumnRef column,           // the backing column on the resolved table
      TypeaheadBacking backing,   // sealed supply arm, selected by dialect
      List<ColumnRef> tiebreak,   // the entity table's PK columns, in key order
      int defaultFirst,
      int maxFirst
  ) {}

  public sealed interface TypeaheadBacking {
      record Trigram() implements TypeaheadBacking {}
      record OracleText() implements TypeaheadBacking {}
      // Both arms are deliberately empty records: the taxonomy earns
      // sealed-over-enum through per-arm SQL-template dispatch and
      // exhaustive Deferred-gate coverage, not through carried data.
      // A second type-ahead mechanism on one dialect would join as a
      // new arm; prose-search backings (tsvector, BM25) and multi-type
      // search tables belong to that future feature's own model.
  }
  ```

  Compact constructors pin the invariants the emitter consumes without
  re-checking: `0 < defaultFirst <= maxFirst`, non-empty `tiebreak`.
- The spec rides the classified root leaf (`QueryTableField`,
  `QueryField.java:108`) as a 0..1 slot (an `Optional` component; the idiom
  exists on other leaves, e.g. `QueryTableMethodTableField`'s
  `Optional<ErrorChannel>`, though `QueryTableField` itself carries none
  today). `operation()` stays `Fetch`: the match predicate is a filter
  contribution and the ranked order + limit are read from the `TypeaheadSpec`
  by the emitter. No `Operation.Typeahead` arm is minted in v1; the normalized
  operation-fact home lands with R314's re-platforming, and the quarantine
  comment says so. One honesty note on the precedent: `Operation.Facet`
  (`Operation.java:89-93`) is a *modeled-but-unpopulated placeholder* with
  no emitter behind it, while `@typeahead` is live emitting behaviour from
  its first commit. Borrowing the placeholder's stays-`Fetch` shape for a
  live concern makes "is this a type-ahead carrier?" a predicate over
  `TypeaheadSpec` presence, which is why the single-sourcing rule in the
  Validation section is an obligation of this deferral, not a stylistic
  preference.
- The two synthesized arguments classify as a new **`ArgumentRef.TypeaheadArgRef`**
  arm with a nested `enum Role { QUERY, FIRST }`, structurally identical to
  `PaginationArgRef` (`ArgumentRef.java:347`), skipped by `projectFilters`
  exactly as pagination args are, so authored arguments continue to classify
  as on any list field. **Name-space overlap to pin**: `first` is also a
  reserved pagination name (`isPaginationArg`, `FieldBuilder.java:1126`).
  On a `@typeahead` carrier the classifier must consult the carrier
  context *before* the name-based pagination router, or `first` routes to
  `PaginationArgRef.FIRST`; the `@asConnection`-co-occurrence rejection is
  therefore load-bearing for classifier soundness (it keeps both routers
  from being live on one field), not just author hygiene. The classifier
  ordering and that rejection ship with a pipeline test pinning them
  together.

Authored filter arguments therefore compose for free: they project into
`WhereFilter`s as today and AND with the match predicate. `@condition` on
the field composes the same way.

### Generated query (one ranked SELECT, per-backing SQL template)

The emitter contributes three query parts to the standard root rows method,
selected at build time by the `TypeaheadBacking` arm:

| Part | `Trigram` (PostgreSQL) | `OracleText` (Oracle) |
|---|---|---|
| match predicate | `{q} <% {column}` (word-similarity threshold) | `CONTAINS({column}, {compiledQ}, 1) > 0` |
| rank ordering | `{q} <<-> {column} ASC` (distance, KNN off the GiST index) | `SCORE(1) DESC` |
| tiebreak + bound | `, {pk...} ASC LIMIT {first}` appended in both arms (jOOQ renders the Oracle fetch form) | |

Both templates are plain-SQL `DSL.condition(...)` / `DSL.field(...)`
templates over the open-source jOOQ artifact; no licensed dependency enters
graphitron's own build (the license boundary sits at executing against a
real Oracle, per the resolved edition question). Rationale for the
opinionated picks:

- The trigram template combines the `<%` threshold filter with the KNN
  ordering deliberately: KNN alone would fill the top-N with garbage matches
  on a nonsense query ("xyzzy"), where threshold + KNN returns `[]`.
  **The `pg_trgm` default threshold (0.6) is too strict and is not the v1
  recommendation** (revised 2026-07-16 while writing the how-to): a single
  transposition ("matirx" for "matrix") scores ~0.43 `word_similarity` and
  is dropped at 0.6, killing the typo-tolerance story out of the box. The
  canonical migration therefore pins
  `ALTER DATABASE ... SET pg_trgm.word_similarity_threshold = 0.4`
  (verified: recovers single-typo matches; the extra noise it admits stays
  rank-sorted below better matches and is cut by the top-N bound; word-start
  anchoring still keeps interior matches like "Amadeus"-for-"ma" out). The
  setting is consumer-owned supply, same law as the index itself; the
  boot-time probe reports the effective threshold so environment drift is
  visible at deploy time. No threshold surface on the directive in v1.
  Exact-word matches produce frequent distance ties (verified empirically),
  which is what the PK tiebreak is for.
- **Oracle needs a query compiler, not an escaper** (revised 2026-07-16,
  Oracle docs-first round; the earlier "conservative escaper" was
  falsified). In the `CONTEXT` grammar whitespace between words means
  *phrase*, not conjunction, and a brace-escaped literal token gets no
  prefix expansion and no fuzzy matching, so wrap-everything-in-braces
  compiles the combobox demand away: typing "mat" would match nothing
  until "matrix" is complete, with no typo tolerance. The emitted runtime
  therefore owns `{compiledQ}`, a compiler over raw keystrokes: tokenize
  to maximal letter/digit runs (all operator characters become
  separators), completed tokens compile to fuzzy terms (`?tok`), the last
  token compiles to prefix-or-fuzzy (`(tok% | ?tok)`), terms join with
  `&`, grammar reserved words (`and`, `or`, `near`, ...) compile to
  brace-escaped exact terms, and tokenless input short-circuits to `[]`
  without querying. Compiler and canonical migration are **mutually
  load-bearing**: the prefix arm assumes a `PREFIX_INDEX` wordlist
  preference, the fuzzy-and-stopword behaviour assumes
  `STOPLIST CTXSYS.EMPTY_STOPLIST` (the default stoplist makes a
  stopword-only query a `DRG-50901` parse error, where
  `websearch_to_tsquery` never throws), and accent folding is the
  migration's `BASE_LETTER` lexer attribute. The alignment's enforcers
  are named in the Tests section (the compiler's syntactic half is
  unit-pinned; the semantic half is owed to the internal pipeline).
- The PK tiebreak makes the top-N deterministic within a snapshot; a
  `@typeahead` carrier whose entity table lacks a primary key is a
  build-time rejection.

Runtime semantics:

- `first` omitted → `defaultFirst`; `first > maxFirst` or `first < 1` →
  `GraphitronClientException`, surfaced as a client-visible error through
  the existing `ErrorRouter.surfaceClientErrorOrRedact` disposition (which
  also redacts any backing failure, the same firewall every fetcher gets).
- An empty `search` string returns `[]` on both arms (nothing clears the
  similarity threshold on Postgres; the Oracle compiler short-circuits
  tokenless input without touching the database). Documented behaviour;
  combobox clients gate on input length anyway.
- Freshness follows the backing: a stored generated column is
  transactionally fresh on Postgres; on Oracle the canonical migration pins
  `SYNC (ON COMMIT)` (plus `OPTIMIZE (AUTO_DAILY)` against the
  fragmentation per-commit syncing causes), which the how-to documents
  together with the staleness window consumers accept if they choose
  `SYNC (EVERY ...)` instead.
- **Boot-time supply probe**: the generated runtime validates each
  type-ahead binding once at startup, extension presence (`pg_extension`
  for the `Trigram` arm) plus one dummy-term execution of the ranked
  query, and fails fast with a named error; the `Trigram` arm also reports
  the effective `pg_trgm.word_similarity_threshold` in the startup log
  (report, not hard-fail: the pinned 0.4 is the recipe's recommendation,
  not a contract the consumer cannot deviate from). The `OracleText` arm
  probes the
  domain index's existence on the bound column, executes one dummy
  compiled query, and reports the index's sync policy and the load-bearing
  preference attributes (`PREFIX_INDEX`, stoplist, `BASE_LETTER`) read
  from `CTX_USER_INDEX_VALUES`, the Oracle analog of the threshold report;
  the probe code ships in the consumer's generated runtime and runs
  against their Oracle at their deploy time, so it exists even though this
  repo cannot execute it (its own verification is owed to the internal
  pipeline). This is the runtime's answer to the facts the
  catalog cannot carry: a missing or drifted migration (extension never
  installed, index dropped, preference lost) surfaces at deploy time with
  a cause, not as redacted per-request errors when the first user types.
  Default-on with an opt-out through the `GraphitronContext` seam (reviewer
  question 5).

### Validation (`rejectTypeaheadMisuse`, the R13 template)

Diagnostics via `ctx.addDiagnostic(ValidationError.forField(...))` in
`GraphitronSchemaBuilder`, message shape mirroring `rejectFacetMisuse`
(`GraphitronSchemaBuilder.java:972`), with one structural improvement over
that template: **the carrier fact is asserted once and single-sourced**.
Every `@typeahead` directive application is exhaustively resolved by the
classifier arm into either a populated `TypeaheadSpec` or a typed `Rejection`;
the misuse pass drains those rejections rather than re-deriving
well-formedness from the raw SDL surface, and the emitter keys off the same
`TypeaheadSpec` presence. No application can fall through both passes silently
(the implicit-coordination smell the `rejectFacetMisuse` shape tolerates
only because facets are an unpopulated placeholder today). The v1 rejection
matrix:

- **Carrier shape**: `@typeahead` only on a root `Query` field returning a bare
  non-null list of a `@table`-backed object type. Everything else rejects
  (child fields, interfaces/unions, `@service` / `@tableMethod` /
  `@routine` / `@reference` / `@asConnection` co-occurrence, `@orderBy`
  arguments and `@defaultOrder` on the carrier: ordering is the search's
  contract).
- **Binding**: `column:` must resolve on the entity's resolved table
  (`Rejection.unknownColumn`), with the type check read from
  `JooqCatalog.columnFactsOf` → `ColumnFacts.sqlType`
  (`JooqCatalog.java:1032/1094`, the surface the meta probe validated) and
  keyed on the same dialect-selected backing arm the classifier produced
  (validator mirrors classifier; never a second dialect read). For the
  `Trigram` arm the column must be a text type (`text` / `character
  varying`); the trigram index and its opclass are invisible to the meta,
  so the GiST recipe is trusted and the boot probe is the runtime check.
  For the `OracleText` arm the check is membership-only (the domain index
  is invisible).
- **Synthesis collisions**: an authored argument named `search` or `first`
  on the carrier, or an authored type colliding with the derived hit type
  name, rejects with a rename hint.
- **Bounds**: `defaultFirst`/`maxFirst` violating
  `0 < defaultFirst <= maxFirst` rejects at build time; missing PK on the
  entity table rejects (no deterministic tiebreak).

### Documentation (first-class deliverable)

A how-to per platform under `docs/manual/how-to/`, each carrying: the
canonical migration, the SDL binding, the query semantics, and the
operational notes (freshness, the asked-for-20-got-18 join-back property
when a backing lags). Being opinionated only works if the blessed path is
excellently documented; the fixture migration doubles as the Postgres
recipe and is exercised by our own suite.

**The trigram (combobox) how-to is already drafted** as
`roadmap/relevance-ranked-search-howto.adoc`, written docs-first
(2026-07-16) to force the user-experience decisions before implementation;
it carries a draft banner and moves to `docs/manual/how-to/` (gaining real
xrefs) when phase 2 lands. Its wire-behaviour tables are empirically
verified and double as phase 2's acceptance behaviour. Writing it forced
two spec revisions recorded in this document: the threshold pin (Generated
query section) and the two-shape fixture (below).

**The Oracle how-to is also drafted**, as
`roadmap/relevance-ranked-search-oracle-howto.adoc` (2026-07-16, same
docs-first method), moving to `docs/manual/how-to/` when phase 3 lands.
One honesty difference from the trigram sibling: its claims are pinned
against Oracle Text documentation, not a live instance, and it says so in
its banner; its closing "What still needs live verification" checklist
doubles as the internal pipeline's execution-tier test plan (Tests
section). Writing it falsified the escaper design and forced the compiled
query, the canonical-migration preferences, and the demand-pinning
decision recorded above. Prose search, when a requester appears, authors
its own documentation against its own contract.

## Implementation sites

- `directives.graphqls`: `@typeahead` (definition above; no enum, the
  mechanism is dialect-derived).
- New `model/TypeaheadSpec.java`, `model/TypeaheadBacking.java`; a 0..1 slot on
  `QueryTableField`; `GraphitronType.SearchHitType` + arms in
  `ObjectTypeGenerator.graphqlTypeFor` and the SDL emitter.
- `ArgumentRef.java`: new `TypeaheadArgRef` arm; `FieldBuilder.classifyArguments`
  recognizes the synthesized names on `@typeahead` carriers.
- `ConnectionPromoter.java`: `@typeahead` arm in `synthesiseForField` (hit-type
  synthesis + carrier rewrite); `rebuildAssembledForConnections` pins the
  hit types (the walk is shared; whether the class gets a broader name is
  the implementer's call).
- `GraphitronSchemaBuilder.java`: `rejectTypeaheadMisuse` (matrix above);
  R333's directive-coverage table gains a `@typeahead` row in the same commit.
- Emitters: the ranked query parts in the root rows-method path
  (`TypeFetcherGenerator` + the query-part emitters), per-kind templates;
  hit `node` passthrough registration in `FetcherRegistrationsEmitter`;
  the Oracle query compiler in the emitted runtime (beside
  `ConnectionHelper`'s generator).
- `graphitron-sakila-db/init.sql` (phase 2): `CREATE EXTENSION pg_trgm`;
  the threshold pin (`ALTER DATABASE ... SET
  pg_trgm.word_similarity_threshold = 0.4`, via a `format(...,
  current_database())` DO block); **both authored binding shapes**, so the
  fixtures exercise what the how-to teaches: `film.title` bound directly
  with a GiST trigram index (the zero-new-column simple case), and an
  `actor.search_name` stored generated column (`first_name || ' ' ||
  last_name`) + GiST index (the concatenation case). The earlier
  `film.search_text (title || description)` sketch is dropped: folding long
  description text into a trigram column is exactly what the how-to warns
  against, and the fixture must not model the anti-pattern (long prose is
  prose search's demand, out of v1). Bump
  `<jooq.codegen.schema.version>` per landing.
- The boot-time supply probe in the emitted runtime (beside the
  `ConnectionHelper` generator), with its `GraphitronContext` opt-out
  seam.
- `graphitron-sakila-example`: a `filmSearch` field in the example schema
  (phase 2, when the emitter exists; see Phasing).
- `docs/`: the Postgres and Oracle how-tos (phase-gated below).

## Tests

- **Unit-tier** (invariants the type system can't pin): `TypeaheadSpec` /
  `TypeaheadBacking` compact-constructor rules; the Oracle query compiler's
  *syntactic* invariants, pinned as exact input-to-compiled-string cases
  (a pure `String → String` runtime helper, so this is legitimate
  unit-tier I/O pinning, not a code-string assertion on emitted
  generator output): tokenization drops every operator character,
  reserved words compile brace-escaped, the last token gets the
  prefix-or-fuzzy arm, tokenless input compiles to no query, and no
  input can produce a `DRG-` parse error by construction.
- **Pipeline-tier** (primary behavioural tier): classification test
  (`TypeaheadSpec` populated with the backing arm the build dialect
  selects, hit type synthesized and registered, arguments appended); the
  rejection matrix, one case per arm above; emit-surface test for the
  generated fetcher (method-surface assertions, no code-string assertions,
  per the R13 rework lesson); the exact `ColumnFacts.sqlType` literals the
  text-type check reads pinned against the real fixture catalog.
- **Compilation-tier**: the sakila example compiles with the search field
  (consumer javac covers the generated hit type, fetcher, and escaper).
- **Execution-tier** (the proof, Postgres). Trigram: a partial word
  matches (`mat` finds "Matrix"), a typo surfaces the intended row in the
  top hits (not necessarily first: "matirx" can rank a same-prefix title
  ahead, verified 2026-07-16, so the assertion is membership-in-top-N, not
  rank-1), a nonsense query returns `[]` (the threshold), a word-interior
  match stays out (`ma` does not find "Amadeus"), a single-accent mismatch
  matches and ranks below the exact spelling ("flaklypa" finds "Flåklypa";
  the accent dividend of the 0.4 threshold, verified 2026-07-16), distance
  ties order deterministically by PK, and the boot probe fails fast with a
  named error against a database missing `pg_trgm`. Shared:
  authored filter argument ANDs with the match; `defaultFirst` applies;
  `first` over `maxFirst` errors client-visibly; empty search string
  returns `[]`; the DML pinning test (insert/update on `film` never writes
  the backing columns, which stay consumer-invisible). The execution tier
  runs against the repo's pinned PostgreSQL image (18 today), which is
  what makes the supported floor (>= 17) enforced rather than asserted:
  every wire-behaviour claim drafted against the sandbox's PostgreSQL 16
  gets re-pinned on the supported family the moment phase 2 lands.
- **Oracle**: emit shape pinned at pipeline tier (the SQL templates render
  without an Oracle connection); execution-tier runs in Sikt's internal
  GitLab pipelines with the licensed jOOQ, or in this repo's CI if the
  license question resolves that way. Stated plainly for the reviewer,
  per invariant (the compiler makes a wider behavioural claim than the
  escaper it replaced, so the honesty note is enumerated, not singular):
  *never-throws on user input* is unit-pinned in this repo (the one
  invariant that lives without an Oracle); *prefix matching from two
  characters, fuzzy typo rescue, conjunctive narrowing, empty-input `[]`,
  the compiler-to-`PREFIX_INDEX`/empty-stoplist migration alignment, and
  the boot probe's attribute report* have **no public-CI enforcer** and
  are owed to the internal pipeline as the named execution list in the
  Oracle how-to's "What still needs live verification" section, which
  mirrors the Postgres trigram execution list item for item. The arm
  ships without the Postgres-grade execution proof until then.

## Phasing

Three landings, each through the canonical flow:

1. **Model + classification + synthesis + validation.** Directive, model
   records, `TypeaheadArgRef`, promoter arm, `rejectTypeaheadMisuse`, unit-
   and pipeline-tier tests. No emitted-code change beyond the schema
   surface, and, because every commit ships to trunk, **`@typeahead` is a
   `Rejection.Deferred` landing in this phase**: a classified type-ahead
   carrier fails the build with "not yet emitted" (the stubbed-leaf
   mechanism `ValidateMojo` already enforces) rather than silently
   classifying and then emitting an ordinary unranked `Fetch` that ignores
   the `search` argument. The `Deferred` gate is **keyed on the
   dialect-selected backing arm**, not on an authored value: in this phase
   both arms are deferred. The sakila-example `filmSearch` field is
   phase-gated to landing 2 for the same reason. Acceptance: pipeline
   fixtures classify, the hit type appears in the emitted schema, the
   rejection matrix fires, and a live `@typeahead` field is `Deferred`,
   never quietly wrong.
2. **Trigram emit + runtime + fixture + docs (serves the requester).** The
   `Trigram` templates, fetcher emit, the boot-time supply probe, the
   `Deferred` landing lifted for the `Trigram` arm (PostgreSQL builds;
   Oracle builds stay `Deferred` until landing 3), the sakila fixture
   migration (extension + generated text column + GiST index) plus the
   example `filmSearch` field, execution-tier tests, the trigram how-to
   promoted from its draft. Acceptance: the sakila example serves
   partial-word, typo-tolerant ranked type-ahead end to end under
   `-Plocal-db`.
3. **Oracle emit + docs.** The `OracleText` templates, the query
   compiler with its unit-pinned syntactic invariants, pipeline-tier
   shape pinning, the `Deferred` landing lifted for the `OracleText` arm
   (Oracle builds), the boot probe's Oracle arm, the Oracle how-to
   promoted from its draft (`relevance-ranked-search-oracle-howto.adoc`)
   into `docs/manual/how-to/`; execution deferred to licensed
   infrastructure. Acceptance: emitted Oracle SQL shape pinned; the
   compiler unit-pinned; the how-to's "What still needs live
   verification" checklist adopted as the internal-pipeline execution
   plan.

## Non-goals (v1)

- **Prose search** (the second demand type; vocabulary pinned in decision
  9): weighted document relevance over longer text: `tsvector` +
  `setweight` + `ts_rank` on PostgreSQL, section weighting / `ABOUT` /
  query-relaxation templates on Oracle Text. **Requester-gated**: nobody
  has asked for it (#512 is type-ahead), and building it now would mean
  inheriting the combobox contract instead of designing from
  information-retrieval demand (paging? highlighting? surfaced weights? a
  documented query language for the search string?). When a requester
  appears it lands as a *sibling directive*, expected name `@search`
  (deliberately left unclaimed), with its own synthesized surface and its
  own backing model; the tsvector supply research in this spec, the
  meta-probe findings, and the extension-landscape section carry over as
  its groundwork. It is **not** a mode argument on `@typeahead`: a mode
  that reinterprets which other arguments are meaningful is the
  enum-with-shared-field-set smell at the directive layer
  (principles-architect consult, 2026-07-16, superseding the earlier
  demand-axis-argument sketch from the Oracle round).
- **Quick-filter** (the third demand, named 2026-07-16 during the split
  revisit): a free-text input over a paginated table that narrows rows
  while *preserving* the connection's declared order and keyset cursors;
  match-only, rank-free. Recognized here so the future "why can't I just
  search my connection?" question has a recorded answer: it is not
  type-ahead (no rank, no bound, no hit wrapper, different quality
  contract) and merging it into `@typeahead` or into connections would
  re-fuse demands the grain round separated. Zero requesters today
  (#512's requester explicitly serves table pages with ordinary keyset
  connections), and its contract questions are open: the pinned 0.4
  threshold is tuned for rank-and-bound and admits noise a rank-free
  filter would surface, typo tolerance may not belong in a filter at all,
  and unranked fuzzy filtering may not be honest UX. If a requester
  appears it is designed as its own surface (plausibly an argument-level
  directive, since it genuinely is a filter contribution), never by
  bending `@typeahead` into connections.
- **BM25-class relevance** (`pg_search`/ParadeDB, `pg_textsearch`/
  TigerData): genuinely better ranking than `ts_rank` on long-text corpora
  (corpus statistics, length normalization); a prose-search concern, so it
  belongs to that future feature's backing model, not to
  `TypeaheadBacking`. Neither extension is available on Amazon RDS today
  anyway, so there is nothing to bind; see *The Postgres extension
  landscape and RDS*.
- **Multi-type search** (a consumer-owned search table with an entity-type
  discriminator): a recognized backing shape, deferred until demanded; a
  future backing model grows a `SearchTable` arm (on `@typeahead` or the
  prose sibling, whichever the demand names) and reuses the row-domain
  discrimination and node-key machinery.
- **Child-field search**, relevance **score exposure**, **highlighting**
  (`ts_headline`), deep **ranked paging**, and **external engines**
  (Elasticsearch and kin): all recorded in the decisions and dead-ends
  sections above with their rationale.
- **LSP completion for `column:`** (offering the entity table's text
  columns as candidates): a natural follow-up on the `CompletionData`
  surface, not part of this item.

## What the generated jOOQ meta can and cannot see (resolved 2026-07-15)

Probed empirically: a scratch PostgreSQL 16 schema carrying a stored
generated tsvector column (weighted `setweight(to_tsvector(...))`
expression), a GIN index over it, a GiST trigram index (`gist_trgm_ops`), a
plain btree, an expression GIN index (`to_tsvector` directly, no stored
column), and a partial index; jOOQ 3.20.11 open-source codegen
(`PostgresDatabase` + `JavaGenerator`, `includeIndexes: true`) run against
it and the output inspected, alongside `javap` of `org.jooq.Index` and
`org.jooq.meta.IndexDefinition`. The probe was then repeated on jOOQ
**3.21.6** (latest release at the time): the generated `Indexes` class is
byte-identical, the tsvector column renders identically (setter for the
generated column included), `org.jooq.Index` is unchanged, and
`IndexDefinition` gains only a `getColumns()` convenience. The findings
below hold for both lines; upgrading jOOQ buys no additional validation
depth.

**Visible in the generated meta:**

- **tsvector columns**, as `TableField<R, Object>` with the qualified type
  name `"pg_catalog"."tsvector"` preserved verbatim. A vector binding can
  therefore be *type-checked*: the named column exists and is a tsvector.
- **Column-based indexes of every access method**, by name, with ordered
  fields and the unique flag. The GIN-over-tsvector and the trigram index
  both appear; membership validation works for both.

**Not visible, at any layer** (not in the generated code, not in the
`org.jooq.Index` runtime API, and not even in jooq-meta's
`IndexDefinition`, which models only columns + uniqueness, so this is not a
generator-output limitation but a hole in jOOQ's own model):

- The **access method** (GIN vs GiST vs btree) and the **operator class**
  (`gist_trgm_ops`).
- **Expression indexes are dropped entirely**: the `to_tsvector(...)`
  expression GIN index simply does not exist in the generated meta, so it
  cannot even be membership-validated.
- The **partial-index predicate** (the index appears, its WHERE does not).
- **Generated-column-ness and its expression**: the stored tsvector column
  looks like a plain writable column (the generated record even carries a
  setter for it).

**Consequences baked into the design:**

- Validation splits cleanly: **membership and column-type checks are
  promised** (binding names resolve, a vector binding is really a
  tsvector); **capability is authored-asserted** (the binding's declared
  kind, e.g. fulltext vs trigram, is trusted for semantics, the same trust
  boundary as `@condition` Java methods).
- The blessed Postgres recipe is *forced* to be the **stored generated
  column**, not an expression index: the stored column is visible and
  type-checkable in the catalog, the expression index is invisible. This
  independently confirms the recipe choice made for other reasons
  (trigger-free, deterministic).
- **DML caveat to pin in fixture work**: open-source jOOQ does not mark
  generated columns readonly, so a graphitron write path must never touch
  the vector column. Expected safe (tsvector columns are never SDL-mapped,
  and DML writes only mapped fields), but it needs a pinning test on a
  searchable-and-mutable table.
- **Follow-up option, not v1**: a graphitron-provided jOOQ generator
  extension could recover full capability validation by querying
  `pg_catalog` (access method, opclass, index expressions) during codegen,
  where the live JDBC connection exists, and embedding the facts as a
  side-car the graphitron catalog reads. Opt-in for consumers who want
  strict validation; the authored assertion remains the baseline.
- The same probe should be repeated against Oracle (does jOOQ meta list
  `CTXSYS.CONTEXT` domain indexes at all?) once licensed test
  infrastructure exists; until then the Oracle binding is asserted-only by
  assumption.

## The Postgres extension landscape and RDS (researched 2026-07-16)

Sikt deploys on Amazon RDS, so a backing mechanism must exist there. The
menu: core tsvector FTS (no extension) and `pg_trgm` (trusted extension,
`CREATE EXTENSION` needs no superuser) are available, as are the helpers
`unaccent` / `fuzzystrmatch` and the CJK-oriented `pg_bigm`. Not available
on RDS: `rum` (index-accelerated `ts_rank`), PGroonga, ZomboDB, and,
decisively, both BM25-class extensions: `pg_search` (ParadeDB,
Tantivy-based) and `pg_textsearch` (TigerData, recently open-sourced under
the PostgreSQL license). BM25 brings corpus statistics and document-length
normalization that beat `ts_rank` on long-text corpora, but on RDS it is
reachable only via a logical-replication side-car to a self-managed
instance, which reintroduces exactly the second system this feature exists
to avoid; out of scope.

Two design consequences:

- **Trigram + tsvector is the complete RDS menu for index-backed
  ranking**, and the two split cleanly along the demand vocabulary:
  trigram is the type-ahead mechanism, tsvector is the waiting
  prose-search mechanism. An empirical demo (2026-07-16)
  pinned the fork: `websearch_to_tsquery('mat')` finds nothing in
  "Matrix" (no prefix matching in core FTS), while trigram
  `word_similarity` ranks it first, and `EXPLAIN` confirms the trigram
  KNN ordering runs off the GiST index (`Index Scan ... Order By`).
- **A `Bm25` arm is a pure addition** (new sealed arm, two SQL templates, a
  how-to) if RDS ever adopts one of the extensions or the deployment
  constraint changes; the SDL contract would not move.

The landscape round also sharpened the assertion problem: whether an
extension is installed, an index exists, or a trigger still maintains a
column is invisible at build time, so the migration assertion is
undetectable until runtime. The boot-time supply probe (Runtime semantics)
is the mitigation.

## Open questions for the reviewer

1. **`first` over `maxFirst`: error or clamp?** The spec says error
   (`GraphitronClientException`), on the grounds that a silently clamped
   result misleads a client that asked for 200 and believes it got them
   all. The counterargument is combobox friendliness: clamping is more
   forgiving of sloppy clients. Leaning error; flip if you disagree.
2. **Dissolved 2026-07-16 (grain round).** This was "`config:` required
   for `TSVECTOR`"; `config:` left the directive surface with the
   prose-search descope, so the question is moot until the prose sibling
   designs its own configuration assertion. (Kept numbered so later
   references hold.)
3. **Root-only carrier gate in v1.** `@typeahead` on child fields (nested
   search under a parent row) is structurally possible later (the
   `TableField` leaf carries the same slots) but rejected in v1 to keep the
   surface small. Confirm the deferral is acceptable.
4. **Oracle test placement (logistics, does not gate).** Sikt holds a jOOQ
   license, so Oracle-dialect codegen is a committed requirement; the open
   logistics question is where licensed execution tests run (worst case
   Sikt's internal GitLab pipelines; optionally ask Lukas whether a CI
   license for this repo is possible). Phase 3's acceptance is written to
   be satisfiable without public-CI Oracle.
5. **Boot-time supply probe default.** The probe turns a missing or
   drifted migration into a deploy-time failure with a named cause instead of
   redacted per-request errors, at the cost of the generated runtime
   issuing a couple of queries at startup. Default-on with a
   `GraphitronContext` opt-out is the spec's position; flip to opt-in if
   startup side effects feel wrong for consumers.

## Dead ends (recorded so they are not relitigated)

- **`orderBy: RELEVANCE` on existing connections**: argument-correlation
  validation GraphQL cannot express, a hardcoded-false `pageInfo` that
  violates the Relay spec, dual-strategy resolvers. Rejected 2026-07-15;
  re-affirmed with stronger arguments 2026-07-16 (decision 1).
- **SDL-authored index definitions** (`@searchable` field tags with weights,
  a logical index namespace owned by graphitron): authoring a schema object
  we neither create nor enforce is DDL in spirit; weights are index-time
  facts. Rejected 2026-07-15.
- **Naive `ILIKE` shim as a zero-migration on-ramp**: does not scale, and as
  the default path it is an enticing dead end. The on-ramp is documentation
  of the real migration instead. Rejected 2026-07-15.
- **Deep ranked paging (ranked-offset cursors)**: no consumer demand
  (top-N confirmed sufficient). Archived below, not deleted, in case demand
  appears.

## Archived: ranked-offset pagination (kept for future demand)

Keyset pagination requires a stable, stored, seekable sort key; a relevance
score is computed per query, non-unique, and not seekable, so keyset is
impossible by construction for ranked results. If deep ranked paging is ever
demanded, the design is: keep the Relay contract, let the (opaque) cursor
encode `offset + query-fingerprint`, derive this strategy whenever ordering
is a query-time score (never a knob), bound depth with a declared limit that
errors past it, and always order `score DESC, <pk> ASC` so offset paging is
deterministic within a snapshot. Postgres reality check: trigram `<->` is
index-ordered, `ts_rank` is materialize-then-sort, so the depth bound is
load-bearing for the fulltext case.

## History

- 2026-07-02: filed from the initial issue #512 discussion (contract vs
  execution split, no-DDL constraint, keyset-vs-rank analysis).
- 2026-07-14: requester confirmed bounded top-N suffices for the combobox
  case; deep ranked paging has no driver.
- 2026-07-16 (Spec revision, the split revisit): does the dedicated-field
  decision still hold after the reshape? Yes, stronger. All three
  original arguments survive (argument-correlation validation,
  structural keyset-impossibility of score ordering, two demands behind
  one field), and three new ones arrived with this week's rounds: the
  0.4 threshold pin makes match-without-rank defective under our own
  recipe; the Oracle compiler makes the search string a compiled input
  rather than a filter value; the hit wrapper's forward bet has no
  landing zone on connection edges. The honest cost was named and
  recorded: the admin-table quick-filter case is a distinct third demand
  (match-only, pagination-preserving), now in Non-goals as recognized
  and requesterless with its open contract questions listed. Decisions
  1 and 6 carry the re-affirmation; both how-tos gained the
  quick-filter distinction.
- 2026-07-16 (Spec revision, the grain round): are we at the correct
  grain, or mixing use cases? Two mixings found and corrected. (1) The
  `SearchKind` enum spliced mechanism and demand everywhere, not just on
  Oracle: `TRIGRAM` and `TSVECTOR` each carried an implicit demand. (2)
  Type-ahead and prose search shared one wire contract by
  construction-history: different input semantics (raw keystrokes vs the
  websearch query language), different result affordances, only one
  requester between them. Resolution (owner decision +
  principles-architect consult): vocabulary pinned (**type-ahead** vs
  **prose search**, decision 9); **v1 serves type-ahead only**; the
  directive is demand-named and sheds `kind:` and `config:`
  (`@typeahead(column:, defaultFirst:, maxFirst:)`), since demand plus
  dialect determine the mechanism uniquely and the migration fact's only
  real enforcer is the boot probe; the classifier reads the dialect once
  through the `JooqCatalog` boundary and carries the sealed
  `TypeaheadBacking` arm (`Trigram`/`OracleText`, both legitimately empty
  records; sealed earned by template dispatch plus Deferred coverage);
  the `Deferred` gate is dialect-keyed (Oracle builds stay `Deferred`
  until phase 3); a future second mechanism on one dialect lands as an
  optional `mechanism:` argument defaulting to the incumbent; prose
  search moves to Non-goals as a requester-gated sibling directive with
  `@search` left unclaimed for it, superseding the Oracle round's
  demand-axis-argument sketch. Reviewer question 2 dissolved; phasing is
  three landings (the tsvector phase removed); item retitled.
- 2026-07-16 (Spec revision, floor statement): the supported PostgreSQL
  floor for the feature is **>= 17** (item owner). No design change
  follows: 17/18 release notes carry no `pg_trgm`/core-FTS behaviour
  changes touching the verified claims (17.9's `strict_word_similarity`
  crash fix is in a function we don't use), and the repo's CI and
  Testcontainers already pin PostgreSQL 18. The drafting-sandbox empirics
  ran on its native 16 (PGDG is unreachable from the sandbox, so no local
  17 re-run); the honesty anchor moves to the execution tier, which runs
  on the repo's pinned image and re-pins every claim on the supported
  family when phase 2 lands. One version-scoped correction: the collation
  round's LIKE-rejection side-note holds for <= 17 only (18 supports LIKE
  on nondeterministic collations), which does not touch the round's
  conclusion.
- 2026-07-16 (Spec revision, Oracle docs-first round): drafted the Oracle
  how-to (`relevance-ranked-search-oracle-howto.adoc`) by the same
  docs-first method, against Oracle Text documentation (no live Oracle in
  this environment; the how-to's banner says so and its closing
  verification checklist becomes the internal pipeline's execution plan).
  Writing it falsified the "conservative escaper" design: in the
  `CONTEXT` grammar whitespace means phrase and brace-escaped literals
  get no prefix or fuzzy expansion, so the escaper as specced compiled
  the combobox demand away entirely. Replaced by a query compiler
  (tokenize, fuzzy completed tokens, prefix-or-fuzzy last token, `&`
  conjunction, braces only for reserved words) that is mutually
  load-bearing with a canonical migration pinning `PREFIX_INDEX`,
  `STOPLIST CTXSYS.EMPTY_STOPLIST` (a stopword-only query is otherwise a
  `DRG-50901` error, breaking never-throws parity), `BASE_LETTER` accent
  folding (full accent insensitivity, stronger than the Postgres arm's
  threshold dividend), `SYNC (ON COMMIT)` and `OPTIMIZE (AUTO_DAILY)`.
  Principles-architect consult resolved the one-mechanism-two-demands
  fork: `kind` names the mechanism, v1 pins `ORACLE_TEXT` to type-ahead,
  future Oracle prose relevance is a demand-axis directive argument (see
  Non-goals), not a new kind and not a `config:` overload. The Tests
  section's Oracle honesty note is now enumerated per invariant.
- 2026-07-16 (Spec revision, collation question): can collations serve
  accent-insensitive search? No: trigram similarity ignores the column's
  collation entirely (verified: identical `word_similarity` on a plain
  column and on an accent-insensitive nondeterministic ICU collation;
  PostgreSQL 17 and older additionally reject LIKE on nondeterministic
  collations, 18 lifts that, irrelevant to the conclusion either way).
  Collations govern `=`/`ORDER BY`, never `<%`. The round did surface a
  dividend: at the pinned 0.4 threshold, one accented character behaves as
  a substitution typo, so "flaklypa" finds "Flåklypa" with no unaccent
  machinery (accent-dense words still miss, ~0.29 for "blabar" vs
  "Blåbærsyltetøy"). The how-to's earlier accent-significant claim was
  measured at the 0.6 default and is corrected; the accent-dividend case
  joins the trigram execution tests.
- 2026-07-16 (Spec revision, later the same day): the docs-first round.
  Drafted the trigram combobox how-to
  (`relevance-ranked-search-howto.adoc`) ahead of implementation to force
  the user-experience decisions; its typing-behaviour tables are
  empirically verified (PostgreSQL 16 + pg_trgm) and double as phase 2
  acceptance behaviour. Writing it falsified two comfortable assumptions:
  (1) the default `word_similarity_threshold` (0.6) drops single-typo
  matches (~0.43), so "stays at the pg_trgm default" was replaced by the
  pinned 0.4 in the canonical migration, boot-probe-reported; (2) the
  `film.search_text (title || description)` fixture sketch modeled the
  long-text-in-trigram anti-pattern the how-to itself warns against, so
  the fixture became two shapes (direct `film.title` bind, concatenated
  `actor.search_name`). Also honesty-tightened the typo execution
  assertion to membership-in-top-N (a same-prefix title can outrank the
  intended row).
- 2026-07-16 (Spec revision): the extension round. RDS reality researched
  and recorded (BM25 extensions `pg_search`/`pg_textsearch` named as
  anticipated future `SearchBacking` arms, unavailable on RDS today;
  `rum`/PGroonga excluded); `TRIGRAM` promoted into v1 as the combobox
  default after an empirical demo showed core FTS fails partial-word
  type-ahead while `pg_trgm` serves it with index-ordered KNN ranking; the
  string-similarity vs document-relevance distinction documented, with the
  threshold + KNN combination; phasing reordered (trigram end-to-end before
  tsvector, Oracle now phase 4); boot-time supply probe added to the
  runtime design (reviewer question 5).
- 2026-07-15 (Spec revision, same session): folded in the
  principles-architect consult. Load-bearing changes: phase 1 lands
  `@search` as `Rejection.Deferred` so a classified-but-unemitted carrier
  fails the build instead of serving unranked rows (validator-mirror gap);
  the carrier fact is single-sourced (classifier resolves every application
  to `SearchSpec` or `Rejection`, misuse pass drains rejections, emitter
  keys off the same spec). Precision: the `Operation.Facet` precedent is a
  dead placeholder while search is live (noted as the obligation behind
  single-sourcing); the `first`/pagination name-space overlap is pinned
  with the `@asConnection` co-occurrence rejection named load-bearing; the
  Oracle arm's weaker in-repo enforcement floor is stated plainly.
- 2026-07-15 (Spec): expanded to a full plan (authored `@search` surface,
  promoter-ridden hit-type synthesis, `SearchSpec`/`SearchBacking` model,
  per-kind SQL templates, rejection matrix, three-phase landing) and
  transitioned Backlog → Spec.
- 2026-07-15 (later): resolved the jOOQ-meta feasibility question
  empirically (see the dedicated section); no open question gates the Spec
  transition any longer. Also recorded why we stay on our authored fixture
  schema instead of switching to pagila: the sakila core is a minority of
  the fixture corpus and is itself customized load-bearingly
  (`category.parent_category_id` self-FK pinned by constraint name,
  `film.text_rating` in the example schema); we import pagila's *idea* (a
  `film.fulltext` column) built the modern way (stored generated column)
  rather than its trigger-based implementation.
- 2026-07-15: design discussion closed the major forks: dedicated generated
  field over a connection order mode; hit wrapper without score; the
  demand/supply inversion (the index is a consumer-owned bearer of facts,
  graphitron maps and type-checks); backed-only with no shim; native
  mechanisms for Postgres and Oracle in scope, external engines out;
  documentation as a first-class deliverable. Later the same day: Sikt holds
  a jOOQ license, so Oracle codegen support is committed; test placement
  (internal GitLab pipelines worst case, possibly a CI license via Lukas)
  is a logistics question inside the Spec, not a gate on it.
