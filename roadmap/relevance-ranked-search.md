---
id: R427
title: "Relevance-ranked free-text search"
status: Backlog
bucket: architecture
priority: 8
theme: pagination
depends-on: []
created: 2026-07-02
last-updated: 2026-07-02
---

# Relevance-ranked free-text search

> Origin: [issue #512](https://github.com/sikt-no/graphitron/issues/512)
> ("annotering av flere felter for å auto-generere søkeindeks"). This item is a
> **Backlog thinking-capture**, not a spec. It records the framing and the
> insights uncovered in early design discussion so they are not relitigated
> later. It is deliberately not Spec-ready; the open forks below must be closed
> first.

## Problem

Consumers need free-text search across several fields of an entity (the
canonical case: match on both a short *code* and a *name/description*), usually
combined with predefined filters, to power frontend comboboxes where a user
types a few characters, gets ranked candidates, and links one to another
entity. Today graphitron's filter model is equality/`@condition`-based only:
there is no declarative multi-column match, and nothing that ranks results by
relevance. The reporter asks for an `@searchable`-style annotation to declare
which fields feed a search surface.

## Hard architectural constraints (non-negotiable)

- **Graphitron never emits DDL.** It consumes the jOOQ catalog; it must not
  create the tsvector column, the GIN/GiST index, or any schema object. The
  physical search index is owned by the consumer's migrations. Graphitron may
  only *reference existing DB objects by name*, exactly as `@order(index:)`
  already references an index for sorting. This is the line the whole design
  must respect.
- **The user-facing schema must be stable across physical strategies.** Whether
  a field is backed by a naive `ILIKE` scan (small dimension table) or an
  index-backed `tsvector`/trigram query (large table), the SDL the frontend
  consumes is identical. Physical strategy is a swappable binding underneath an
  unchanged contract.

## Key insight 1: split the contract from the execution strategy

"Search" is two features, and they must stay separate:

1. **Contract** — "this list field accepts a free-text `search:` string that
   matches across *these* fields." Pure schema + codegen; never touches the DB
   schema. This is what `@searchable` declares.
2. **Execution strategy** — how the match (and the ranking) is physically run:
   `ILIKE`-OR chain, `pg_trgm` similarity, `tsvector @@ ... ` + `ts_rank`. This
   is where "the database is actively involved" and it varies by table size.

The escape hatch for the hard case already exists: a `@condition` pointing at a
Java method returning a jOOQ `Condition` can express any tsvector/trigram
predicate today and composes (AND) with existing filters. So #512 is primarily
an **ergonomics** request (remove the boilerplate for the common multi-column
match shape), not a missing raw capability, *for the filtering half*.

## Key insight 2: relevance ranking is the genuinely missing piece

Finding matches fast is the easy half. **Ranking by relevance is the hard
half, and it is a real capability gap, not a principle violation.** A good
ranking (`ts_rank_cd`, trigram `similarity()`, BM25) is a value the *database
computes*, using the same index large tables need. It cannot be produced
honestly in the Java resolver without pulling candidate rows into memory and
scoring them there, which defeats the "DB does the work" architecture.
Therefore relevance must be a DB expression; graphitron's role is only to
*name and route* it (auto-derived default from weighted `@searchable` columns,
or an explicit named DB expression/function override, same auto-default +
escape-hatch pattern used elsewhere). Per-field weighting (`code` prefix beats
`name` substring) is where combobox relevance quality lives and should be
first-class.

## Key insight 3: keyset is graphitron's *answer*, not a *principle* — ranked search needs a second pagination strategy

`@asConnection` uses keyset/cursor pagination, which *requires* a stable,
stored, seekable, deterministic sort key. Relevance rank violates every part of
that: it is computed per query, non-unique (needs a tiebreak), and not seekable
(paging by keyset would recompute the score in the WHERE for every candidate,
defeating the index). Postgres full-text ranking is inherently
`ORDER BY ts_rank(...) LIMIT n OFFSET m`, i.e. offset pagination.

The correct conclusion is **not** "ranked search is out of architecture" (that
would be cargo-culting keyset). It is: **graphitron has one pagination strategy
and needs a second.** Design thesis:

- **Pagination strategy is *derived*, not configured.** When the ordering key is
  a seekable stored column → keyset (today). When ordering is a query-time
  relevance score → keyset is impossible by construction, so codegen derives a
  **ranked-offset** strategy. The generator can *detect* this from the ordering
  intent; it is a forced consequence, not a knob.
- **Keep the Relay contract; change the cursor's meaning.** Relay cursors are
  opaque, so a cursor may encode `offset + query-fingerprint` instead of a
  keyset tuple. The frontend keeps `edges`/`pageInfo`/opt-in `totalCount`/"load
  more"; comboboxes and stable lists look identical on the wire. This preserves
  the "page through a large ranked result" case that a bare top-N list would
  discard.
- **Declare the cost, don't hide it.** Deep offset scans-and-discards. Bound it
  with a declared `maxDepth` that errors past the limit, rather than let an
  unbounded OFFSET be a silent footgun. Comboboxes never approach the bound.
- **Ordering is always `score DESC, <stable pk> ASC`** so offset paging is at
  least deterministic within a snapshot.

## Physical strategy maps to real Postgres capabilities

- **trigram distance (`<->`, `pg_trgm` + GiST/GIN):** the ordering operator *is*
  index-backed and orderable (`ORDER BY name <-> :q LIMIT n` runs off the
  index). Sweet spot for typo-tolerant comboboxes.
- **full-text `ts_rank`:** the `@@` filter is index-backed, but `ts_rank` is
  *not* orderable-by-index → top-N materialize-then-sort. Fine for bounded
  pages, which is exactly why `maxDepth` belongs here.
- **naive weighted match (small tables):** unindexed scoring expression,
  portable, no migration.

The author's declared strategy selects among these; the SDL is unchanged across
all three.

## Likely shape of the work (not committed)

- **Slice A (tractable, portable):** `@searchable` as a multi-column *filter*
  predicate — synthesizes a `search:` argument, ORs the match across marked
  columns, composes (AND) with existing filters, **keeps keyset pagination**
  because relevance never enters the ORDER BY. No ranking. Shippable on its own.
- **Slice B (the real feature):** ranked-offset connection as a first-class
  second pagination strategy, derived when ordering is relevance-based, Relay
  contract preserved via offset-encoded cursors, depth bounded by declaration,
  score sourced from weighted `@searchable` columns or a named DB expression.
- Sibling to R13 (`@asFacet` / faceted-search): both mark input/columns and grow
  an arm on the synthesized connection.

## Open forks to close before Spec

1. **Derived vs explicit strategy selection.** Infer ranked-offset purely from
   the presence of a relevance order (idiomatic, self-documenting), or require
   an explicit marker on `@asConnection` so the author consciously opts into
   weaker pagination guarantees? Leaning inferred-with-a-loud-generated-note.
2. **Default match mode:** `prefix` (combobox-friendly, btree-usable) vs
   `contains` (`%x%`, not index-usable) vs `fulltext`.
3. **Where the strategy/weight binding lives:** on `@searchable` itself vs on the
   synthesized argument.
4. **Consumption reality check (blocking):** do frontends genuinely page deep
   through ranked results, or is bounded top-N enough for the combobox use case?
   This decides how much of Slice B's offset machinery is actually required.
