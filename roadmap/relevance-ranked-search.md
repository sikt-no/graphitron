---
id: R427
title: "Free-text search backed by native database search indexes"
status: Backlog
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
> 2026-07-02 as a thinking-capture; substantially rewritten 2026-07-15 after a
> design discussion closed most of the original forks. Still **Backlog**: the
> direction is settled, but the open questions at the end (notably the jOOQ
> metadata feasibility check) gate the Spec transition. The dead-ends section
> records the roads not taken so they are not relitigated.

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

## Likely shape of the work (not committed)

- A binding directive (name TBD; the working shape is `@search` with a
  sealed supply reference: vector column | index | search table, resolved
  by name into the jOOQ catalog namespace) authored at the search surface.
  The grain is **one directive application per search surface, binding it to
  one backing**; there are no per-field `@searchable` tags, since membership
  is a fact of the backing.
- The synthesized search field and hit wrapper types, riding the same
  promoter/synthesis path R13 shipped.
- Validation: membership (the named object exists) plus capability checks
  (the demand is servable), as `AuthorError`s with LSP surfacing; the
  catalog's search-relevant region becomes completable/hoverable (the six
  reads of R333's referenced-namespace model apply unchanged).
- Runtime: one ranked query per search invocation, `ORDER BY <rank> DESC,
  <pk> ASC LIMIT n`, filters composing (AND) with the match predicate in the
  single-type case.
- **Developer-facing documentation is a first-class deliverable**: a how-to
  per platform with the canonical migration (Postgres: generated tsvector
  column + GIN index; Oracle: `CTXSYS.CONTEXT` index + sync policy), the
  binding SDL, and the freshness/operational notes. Being opinionated only
  works if the blessed path is excellently documented.
- Test fixtures: our sakila port does not carry pagila's `fulltext` column,
  so the execution tier needs a generated tsvector column + GIN index added
  to `graphitron-sakila-db/init.sql`. That fixture migration doubles as the
  documented Postgres recipe, exercised by our own suite.

## Open questions gating Spec

1. **jOOQ metadata visibility (first concrete task).** Index columns are
   readable today (`findIndexColumns`), but can the generated jOOQ 3.20 meta
   tell a GIN from a GiST from a btree, see operator classes
   (`gin_trgm_ops`), or expose a generated column's expression? If the meta
   is thin, the backing's *kind* becomes an authored assertion on the
   binding (validated for membership, trusted for semantics, the same trust
   boundary as `@condition` Java methods); if rich, we can validate
   capability too. This decides how much validation the Spec can promise.
2. **Oracle test infrastructure (resolved in principle, logistics open).**
   jOOQ's open-source edition does not support Oracle, but **Sikt holds a
   jOOQ license, so generating the Oracle-dialect code is a committed
   requirement**, not a conditional (resolved 2026-07-15). What remains is
   where the licensed tests run: this repo's public CI is Postgres-only, so
   worst case the Oracle execution tests run in Sikt's internal GitLab
   pipelines, which can access the licensed dependencies. Optionally ask
   Lukas (jOOQ) whether a license for this project's CI is possible. This
   no longer gates Spec; it shapes the test plan inside it.
3. **Directive surface details**: directive and argument naming, where the
   synthesized field attaches and what it is called (derived name with an
   override is the lean), collision rules.
4. **Filter composition on the search field**: which of the parent entity's
   filter arguments the search field inherits in the single-type case.
5. **Second Postgres mechanism**: whether `pg_trgm` (typo-tolerant,
   index-ordered ranking, the natural combobox fit) joins `tsvector` in v1
   or follows later. Starting with one mechanism per platform says later,
   but trigram's index-ordered `<->` is cheap once the binding exists.

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
- 2026-07-15: design discussion closed the major forks: dedicated generated
  field over a connection order mode; hit wrapper without score; the
  demand/supply inversion (the index is a consumer-owned bearer of facts,
  graphitron maps and type-checks); backed-only with no shim; native
  mechanisms for Postgres and Oracle in scope, external engines out;
  documentation as a first-class deliverable. Later the same day: Sikt holds
  a jOOQ license, so Oracle codegen support is committed; test placement
  (internal GitLab pipelines worst case, possibly a CI license via Lukas)
  is a logistics question inside the Spec, not a gate on it.
