---
id: R427
title: "Free-text search backed by native database search indexes"
status: Spec
bucket: architecture
priority: 8
theme: search
depends-on: []
created: 2026-07-02
last-updated: 2026-07-15
---

# Free-text search backed by native database search indexes

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
4. **Supported backings are native database mechanisms, one per platform to
   start**: PostgreSQL (`tsvector` + `@@` matching + `ts_rank`, GIN-backed)
   and Oracle (Oracle Text: `CONTAINS()` + `SCORE()` over a `CTXSYS.CONTEXT`
   domain index). Specialized engines external to the database
   (Elasticsearch, OpenSearch, etc.) are **out of scope** unless real demand
   appears. We are opinionated about what we support; unsupported backing
   shapes are rejected at build time (the R13 `rejectFacetMisuse` pattern).
5. **Weights and analyzers are index-time facts, not SDL.** Per-field
   weighting lives in the consumer's `setweight(...)` expression (or Oracle
   Text preferences), authored in their migrations. An SDL weight argument
   would be either dead text (backed case) or a private scoring model we
   invented; both are wrong.
6. **The Slice A idea (a `search:` filter argument on existing
   filter/connection fields) is dead**, both in its naive-`ILIKE` form
   (decision 3) and as a backed filter arm: search lives exclusively on the
   dedicated search field. One concept, one surface, simpler to teach.
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
  Oracle Text `CONTEXT` indexes are stale-by-default between `SYNC` runs.
  Consequence: hits may reference dead rows, so the hit-to-entity join-back
  is an inner join that drops them, and "asked for 20, got 18" is documented
  behaviour, not a bug.

## Design

### Authored SDL surface

The author writes a root list field returning the entity type and binds the
backing with one directive application, mirroring `@asConnection`'s
author-writes-field / generator-owns-type pattern:

```graphql
type Query {
  filmSearch: [Film!]! @search(column: "fulltext", kind: TSVECTOR, config: "english")
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
Declares a ranked free-text search surface on a root list field. Matching and
relevance ranking run in the database against a consumer-owned search
backing; graphitron synthesizes the search arguments and the per-field Hit
wrapper type and generates a bounded top-N ranked query. Requires a database
migration owned by the consumer; see the search how-to for the per-platform
recipe.
"""
directive @search(
  """SQL name of the backing column on the entity's table: a tsvector column (TSVECTOR) or the text column carrying the Oracle Text CONTEXT index (ORACLE_TEXT)."""
  column: String!
  """Native search mechanism backing the column; selects the generated SQL shape at build time."""
  kind: SearchKind!
  """Text-search configuration used to parse the query string (TSVECTOR only, where it is required). Must match the configuration baked into the vector column's expression; the catalog cannot reveal it, so it is asserted here."""
  config: String
  """Hits returned when the client omits 'first'."""
  defaultFirst: Int = 20
  """Upper bound on 'first'. A request exceeding it is a client-visible error."""
  maxFirst: Int = 100
) on FIELD_DEFINITION

enum SearchKind { TSVECTOR ORACLE_TEXT }
```

`kind` is the authored capability assertion the meta probe forced: the
generated SQL shape is selected at *build* time from the directive, never
switched at runtime, which keeps "strategy is derived from the schema at
codegen time" intact. `config` is required for `TSVECTOR` (build-time
rejection when absent) and rejected for `ORACLE_TEXT`; a query-side
configuration that disagrees with the vector column's index-time
configuration breaks matching silently, so the assertion is load-bearing,
not decoration.

### Synthesis (rides the R13/connection promoter path)

`ConnectionPromoter`'s field-first walk (`synthesiseForField`,
`ConnectionPromoter.java:140`) gains a `@search` arm beside the
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
- A new `GraphitronType.SearchHitType` model record (beside
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

- A **`SearchSpec` model record** is the carrier, mirroring how R13's
  `FacetSpec` rides `ConnectionType` as a denormalized view carrier:

  ```java
  public record SearchSpec(
      ColumnRef column,          // the backing column on the resolved table
      SearchBacking backing,     // sealed supply arm
      List<ColumnRef> tiebreak,  // the entity table's PK columns, in key order
      int defaultFirst,
      int maxFirst
  ) {}

  public sealed interface SearchBacking {
      record Tsvector(String config) implements SearchBacking {}
      record OracleText() implements SearchBacking {}
      // future arms, not v1: NamedIndex (pg_trgm), SearchTable (multi-type)
  }
  ```

  Compact constructors pin the invariants the emitter consumes without
  re-checking: `0 < defaultFirst <= maxFirst`, non-blank `Tsvector.config`,
  non-empty `tiebreak`.
- The spec rides the classified root leaf (`QueryTableField`,
  `QueryField.java:108`) as a 0..1 slot (an `Optional` component; the idiom
  exists on other leaves, e.g. `QueryTableMethodTableField`'s
  `Optional<ErrorChannel>`, though `QueryTableField` itself carries none
  today). `operation()` stays `Fetch`: the match predicate is a filter
  contribution and the ranked order + limit are read from the `SearchSpec`
  by the emitter. No `Operation.Search` arm is minted in v1; the normalized
  operation-fact home lands with R314's re-platforming, and the quarantine
  comment says so. One honesty note on the precedent: `Operation.Facet`
  (`Operation.java:89-93`) is a *modeled-but-unpopulated placeholder* with
  no emitter behind it, while `@search` is live emitting behaviour from its
  first commit. Borrowing the placeholder's stays-`Fetch` shape for a live
  concern makes "is this a search carrier?" a predicate over `SearchSpec`
  presence, which is why the single-sourcing rule in the Validation section
  is an obligation of this deferral, not a stylistic preference.
- The two synthesized arguments classify as a new **`ArgumentRef.SearchArgRef`**
  arm with a nested `enum Role { QUERY, FIRST }`, structurally identical to
  `PaginationArgRef` (`ArgumentRef.java:347`), skipped by `projectFilters`
  exactly as pagination args are, so authored arguments continue to classify
  as on any list field. **Name-space overlap to pin**: `first` is also a
  reserved pagination name (`isPaginationArg`, `FieldBuilder.java:1126`).
  On a `@search` carrier the classifier must consult the search-carrier
  context *before* the name-based pagination router, or `first` routes to
  `PaginationArgRef.FIRST`; the `@asConnection`-co-occurrence rejection is
  therefore load-bearing for classifier soundness (it keeps both routers
  from being live on one field), not just author hygiene. The classifier
  ordering and that rejection ship with a pipeline test pinning them
  together.

Authored filter arguments therefore compose for free: they project into
`WhereFilter`s as today and AND with the match predicate. `@condition` on
the field composes the same way.

### Generated query (one ranked SELECT, per-kind SQL template)

The emitter contributes three query parts to the standard root rows method,
selected at build time by the `SearchBacking` arm:

| Part | `TSVECTOR` | `ORACLE_TEXT` |
|---|---|---|
| match predicate | `{vector} @@ websearch_to_tsquery({config}, {q})` | `CONTAINS({column}, {escapedQ}, 1) > 0` |
| rank expression | `ts_rank({vector}, websearch_to_tsquery({config}, {q}))` | `SCORE(1)` |
| order + bound | `ORDER BY {rank} DESC, {pk...} ASC LIMIT {first}` | same (jOOQ renders the Oracle fetch form) |

Both templates are plain-SQL `DSL.condition(...)` / `DSL.field(...)`
templates over the open-source jOOQ artifact; no licensed dependency enters
graphitron's own build (the license boundary sits at executing against a
real Oracle, per the resolved edition question). Rationale for the
opinionated picks:

- `websearch_to_tsquery` over `to_tsquery` / `plainto_tsquery`: it never
  throws on user input and supports quoted phrases and `-`negation, which is
  the right default for a combobox fed raw keystrokes.
- `ts_rank` over `ts_rank_cd` in v1: honors the `setweight` labels baked
  into the vector, no cover-density surprises; revisiting is a one-line
  template change that does not touch the contract.
- Oracle `CONTAINS` query syntax *does* throw on raw operators (`AND`, `&`,
  trailing `-`), so the emitted runtime includes a conservative escaper
  (each whitespace-separated token wrapped in `{ }`), keeping the two
  platforms behaviorally aligned: user input never causes a syntax error.
- The PK tiebreak makes the top-N deterministic within a snapshot; a
  `@search` carrier whose entity table lacks a primary key is a build-time
  rejection.

Runtime semantics:

- `first` omitted → `defaultFirst`; `first > maxFirst` or `first < 1` →
  `GraphitronClientException`, surfaced as a client-visible error through
  the existing `ErrorRouter.surfaceClientErrorOrRedact` disposition (which
  also redacts any backing failure, the same firewall every fetcher gets).
- An empty or all-stopword `search` string parses to an empty tsquery and
  matches nothing: the field returns `[]`. Documented behaviour; combobox
  clients gate on input length anyway.
- Freshness follows the backing: a stored generated column is
  transactionally fresh on Postgres; Oracle Text CONTEXT indexes are stale
  between `SYNC` runs, which the how-to documents as an operational
  property, not a bug.

### Validation (`rejectSearchMisuse`, the R13 template)

Diagnostics via `ctx.addDiagnostic(ValidationError.forField(...))` in
`GraphitronSchemaBuilder`, message shape mirroring `rejectFacetMisuse`
(`GraphitronSchemaBuilder.java:972`), with one structural improvement over
that template: **the carrier fact is asserted once and single-sourced**.
Every `@search` directive application is exhaustively resolved by the
classifier arm into either a populated `SearchSpec` or a typed `Rejection`;
the misuse pass drains those rejections rather than re-deriving
well-formedness from the raw SDL surface, and the emitter keys off the same
`SearchSpec` presence. No application can fall through both passes silently
(the implicit-coordination smell the `rejectFacetMisuse` shape tolerates
only because facets are an unpopulated placeholder today). The v1 rejection
matrix:

- **Carrier shape**: `@search` only on a root `Query` field returning a bare
  non-null list of a `@table`-backed object type. Everything else rejects
  (child fields, interfaces/unions, `@service` / `@tableMethod` /
  `@routine` / `@reference` / `@asConnection` co-occurrence, `@orderBy`
  arguments and `@defaultOrder` on the carrier: ordering is the search's
  contract).
- **Binding**: `column:` must resolve on the entity's resolved table
  (`Rejection.unknownColumn`). For `TSVECTOR` the column's SQL type name
  (via `JooqCatalog.columnFactsOf` → `ColumnFacts.sqlType`,
  `JooqCatalog.java:1032/1094`, the surface the meta probe validated) must
  be `tsvector`, and `config:` must be present. For `ORACLE_TEXT` the check
  is membership-only (the domain index is invisible to the meta; the kind
  is the trust boundary) and `config:` must be absent.
- **Synthesis collisions**: an authored argument named `search` or `first`
  on the carrier, or an authored type colliding with the derived hit type
  name, rejects with a rename hint.
- **Bounds**: `defaultFirst`/`maxFirst` violating
  `0 < defaultFirst <= maxFirst` rejects at build time; missing PK on the
  entity table rejects (no deterministic tiebreak).

### Documentation (first-class deliverable)

A how-to per platform under `docs/`, each carrying: the canonical migration
(Postgres: stored generated tsvector column with `setweight` labels + GIN
index, the same migration the sakila fixture ships; Oracle: `CTXSYS.CONTEXT`
index + sync policy), the SDL binding, the query semantics
(`websearch_to_tsquery` syntax; escaping on Oracle), and the operational
notes (freshness, the asked-for-20-got-18 join-back property when a backing
lags). Being opinionated only works if the blessed path is excellently
documented; the fixture migration doubles as the Postgres recipe and is
exercised by our own suite.

## Implementation sites

- `directives.graphqls`: `@search` + `SearchKind` (definitions above).
- New `model/SearchSpec.java`, `model/SearchBacking.java`; a 0..1 slot on
  `QueryTableField`; `GraphitronType.SearchHitType` + arms in
  `ObjectTypeGenerator.graphqlTypeFor` and the SDL emitter.
- `ArgumentRef.java`: new `SearchArgRef` arm; `FieldBuilder.classifyArguments`
  recognizes the synthesized names on `@search` carriers.
- `ConnectionPromoter.java`: `@search` arm in `synthesiseForField` (hit-type
  synthesis + carrier rewrite); `rebuildAssembledForConnections` pins the
  hit types (the walk is shared; whether the class gets a broader name is
  the implementer's call).
- `GraphitronSchemaBuilder.java`: `rejectSearchMisuse` (matrix above);
  R333's directive-coverage table gains a `@search` row in the same commit.
- Emitters: the ranked query parts in the root rows-method path
  (`TypeFetcherGenerator` + the query-part emitters), per-kind templates;
  hit `node` passthrough registration in `FetcherRegistrationsEmitter`;
  the Oracle escaper in the emitted runtime (beside `ConnectionHelper`'s
  generator).
- `graphitron-sakila-db/init.sql`: `film.fulltext` stored generated column
  (`setweight(title, 'A') || setweight(description, 'B')`, config
  `english`) + GIN index; bump `<jooq.codegen.schema.version>`.
- `graphitron-sakila-example`: a `filmSearch` field in the example schema
  (phase 2, when the emitter exists; see Phasing).
- `docs/`: the Postgres and Oracle how-tos (phase-gated below).

## Tests

- **Unit-tier** (invariants the type system can't pin): `SearchSpec` /
  `SearchBacking` compact-constructor rules; the Oracle escaper (token
  wrapping, embedded braces, blank input).
- **Pipeline-tier** (primary behavioural tier): classification test
  (`SearchSpec` populated with the expected backing arm, hit type
  synthesized and registered, arguments appended); the rejection matrix,
  one case per arm above; emit-surface test for the generated fetcher
  (method-surface assertions, no code-string assertions, per the R13
  rework lesson); the exact `ColumnFacts.sqlType` literal for a tsvector
  column pinned against the real fixture catalog.
- **Compilation-tier**: the sakila example compiles with the search field
  (consumer javac covers the generated hit type, fetcher, and escaper).
- **Execution-tier** (the proof, Postgres): ranking honors weights (a
  title match outranks a description match); authored filter argument ANDs
  with the match; `defaultFirst` applies; `first` over `maxFirst` errors
  client-visibly; empty search string returns `[]`; the DML pinning test
  (insert/update on `film` never writes `fulltext`, the generated column
  stays consumer-invisible).
- **Oracle**: emit shape pinned at pipeline tier (the SQL templates render
  without an Oracle connection); execution-tier runs in Sikt's internal
  GitLab pipelines with the licensed jOOQ, or in this repo's CI if the
  license question resolves that way. Stated plainly for the reviewer: the
  `OracleText` arm's behavioural floor *in this repo* is pipeline-tier
  shape plus the unit-pinned escaper (the one runtime invariant with no
  public-CI execution backstop is the one with its own unit enforcer);
  execution parity is owed to the internal pipeline and the arm ships
  without the Postgres-grade execution proof until then.

## Phasing

Three landings, each through the canonical flow:

1. **Model + classification + synthesis + validation.** Directive, model
   records, `SearchArgRef`, promoter arm, `rejectSearchMisuse`, unit- and
   pipeline-tier tests. No emitted-code change beyond the schema surface,
   and, because every commit ships to trunk, **`@search` is a
   `Rejection.Deferred` landing in this phase**: a classified search carrier
   fails the build with "not yet emitted" (the stubbed-leaf mechanism
   `ValidateMojo` already enforces) rather than silently classifying and
   then emitting an ordinary unranked `Fetch` that ignores the `search`
   argument. The sakila-example `filmSearch` field is phase-gated to
   landing 2 for the same reason. Acceptance: pipeline fixtures classify,
   the hit type appears in the emitted schema, the rejection matrix fires,
   and a live `@search` field is `Deferred`, never quietly wrong.
2. **Postgres emit + runtime + fixture + docs.** The tsvector templates,
   fetcher emit, the `Deferred` landing lifted for `TSVECTOR`, the sakila
   fixture migration plus the example `filmSearch` field, execution-tier
   tests, the Postgres how-to. Acceptance: the sakila example serves ranked
   search end to end under `-Plocal-db`.
3. **Oracle emit + docs.** The `ORACLE_TEXT` templates, escaper,
   pipeline-tier shape pinning, the Oracle how-to with the sync-policy
   note; execution deferred to licensed infrastructure. Acceptance: emitted
   Oracle SQL shape pinned; the internal-pipeline execution plan written
   down.

## Non-goals (v1)

- **`pg_trgm` trigram backing.** The natural typo-tolerant combobox fit,
  and its index-ordered `<->` makes it cheap once the binding exists, but
  one mechanism per platform is the v1 scope. It lands later as a new
  `SearchBacking.NamedIndex` arm (membership-validated against the catalog's
  index list; kind asserted, since the opclass is invisible to the meta).
- **Multi-type search** (a consumer-owned search table with an entity-type
  discriminator): a recognized backing shape, deferred until demanded; the
  sealed `SearchBacking` grows a `SearchTable` arm and reuses the row-domain
  discrimination and node-key machinery.
- **Child-field search**, relevance **score exposure**, **highlighting**
  (`ts_headline`), deep **ranked paging**, and **external engines**
  (Elasticsearch and kin): all recorded in the decisions and dead-ends
  sections above with their rationale.
- **LSP completion for `column:`** (offering the entity table's tsvector
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

## Open questions for the reviewer

1. **`first` over `maxFirst`: error or clamp?** The spec says error
   (`GraphitronClientException`), on the grounds that a silently clamped
   result misleads a client that asked for 200 and believes it got them
   all. The counterargument is combobox friendliness: clamping is more
   forgiving of sloppy clients. Leaning error; flip if you disagree.
2. **`config:` required for `TSVECTOR`.** Requiring it forces the author to
   state the text-search configuration and makes the silent-mismatch
   failure mode impossible to hit by omission. The alternative (default
   `simple` or `english`) is friendlier but reintroduces exactly the silent
   mismatch. Leaning required.
3. **Root-only carrier gate in v1.** `@search` on child fields (nested
   search under a parent row) is structurally possible later (the
   `TableField` leaf carries the same slots) but rejected in v1 to keep the
   surface small. Confirm the deferral is acceptable.
4. **Oracle test placement (logistics, does not gate).** Sikt holds a jOOQ
   license, so Oracle-dialect codegen is a committed requirement; the open
   logistics question is where licensed execution tests run (worst case
   Sikt's internal GitLab pipelines; optionally ask Lukas whether a CI
   license for this repo is possible). Phase 3's acceptance is written to
   be satisfiable without public-CI Oracle.

## Dead ends (recorded so they are not relitigated)

- **`orderBy: RELEVANCE` on existing connections**: argument-correlation
  validation GraphQL cannot express, a hardcoded-false `pageInfo` that
  violates the Relay spec, dual-strategy resolvers. Rejected 2026-07-15.
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
