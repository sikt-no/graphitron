---
name: nested-jooq
description: Author a nested jOOQ query at one grain: name what one row of the answer means, drive the statement from the relation that owns that key, nest child grains as correlated MULTISETs on their own keys, and pin the result with a seeded test. Use when writing or reviewing hand-written store reads (MCP, LSP, model derivations), @condition fixtures, or any query that returns duplicates, drops rows, puts rows under the wrong parent, or needs "how do I nest this".
---

# nested-jooq

One discipline for every nested jOOQ read in this tree. Pick the grain of the answer first, drive the
statement from the relation that owns that grain, and let every child list be a correlated `MULTISET`
hanging off the key its relation already declares.

The doctrine is `docs/architecture/explanation/fact-model.adoc`, under "One base, many views": the
one-projection-per-grain thesis and the authoring rule beside it. That page is the source; this skill is
the procedure that gets you there in the right order. Read the page when you want the argument, follow
the steps when you are writing the query.

**The failure this prevents** is reaching for Java instincts at a relational problem: several statements
at several grains folded back together with an invented grouping key, joins that fan out and get papered
over with `DISTINCT`, aggregates over the wrong grain, a predicate bound to the wrong alias, a deep
derivation correlated once per driving row.

## 1. Name the grain first

Before any SQL: state in one sentence what one row of the answer means, and name the relation that owns
that sentence's natural key. If you cannot say it, you are not ready to write the query, and every later
step is a guess.

The register for this already exists on the store side. Every relation's `COMMENT ON` opens by naming what
one of its rows asserts, and those comments are surfaced through `StoreCatalog` and the rendered schema
reference pages. Read the comment of every relation you are about to touch; the grain is the first thing
it tells you.

`fact-model.adoc`'s "Name the row, not the question" states the test your own sentence has to pass, and it
is the same test whether you are naming a new relation or naming the grain of a read: say what a single
row asserts, without naming a consumer, a generator pass, or an existing class. A sentence that can only
be phrased as "the thing the caller wanted" is the tell that you have taken the question's grain instead
of a fact's.

Its standing, stated honestly: `FactSchemaGateTest`'s comment-coverage check asserts that every relation
and every column carries a comment, and nothing more. Presence is gated; content is not. So a missing or
vague grain sentence on a relation you are reading is work to do, not licence to guess what the rows
mean.

## 2. Drive from the grain's owner

The relation owning the answer's grain goes first in the `FROM` clause. Everything else attaches to it
through keys the relations already declare.

Never fold a second grain into that projection. A child grain has exactly two honest shapes:

- a correlated `MULTISET` on the key the child's own relation declares, nested a second level where the
  child has children of its own; or
- its own statement, paired on a real key that the store states.

A grouping key you invented in Java is neither. `SchemaQueries` in `graphitron-mcp` is the exemplar to
imitate: two statements at two grains, paired on the type's own key rather than a grouping invented
there, and within a grain nothing folded.

Where the grain's owner is a derivation carrying a window function or a recursive term rather than a base
relation, the cost half of this decision is already settled on `fact-model.adoc` under "Derived reads are
views, not stored facts": such a view wants to be first in the `FROM` clause with its witnesses joined in
as arity-preserving left joins, where a base relation correlated per row is an index seek that nests
freely. Go read that rule before you pick, because it is narrower than "avoid correlated subqueries" and
the page states what actually decides. Do not copy its measurements into your own comment.

## 3. Cardinality is declared, not discovered

SQL joins produce bags. A join whose cardinality you have not proven from a declared key either fans out
or silently deduplicates, and both are wrong answers that look like right ones.

- A path that fans out returns duplicate rows *correctly*. The bug is upstream, in the shape you chose.
- Boolean question over a non-unique path: `EXISTS`. It asks what you meant and cannot fan out.
- Witness columns off a to-one path: an arity-preserving left join.
- Not-unique and not boolean: re-anchor the projection at the grain that makes it unique.

`DISTINCT` is never the repair. It changes semantics, hides which side fanned out, and breaks under
pagination, where a page of deduplicated rows is not a page of anything.

## 4. Per-surface guidance

**Hand-written store reads** (`graphitron-mcp`, `graphitron-lsp`'s `facts` package, `graphitron-model`
derivations). Imitate `SchemaQueries`. Verify against `SeededStore`: seed the rows the answer depends on,
call `SeededStore.derive` before reading any derived relation, and assert what the read returns. The
"Where a store-backed test gets its store" table in `docs/architecture/how-to/testing.adoc` is the
authority on which harness a given subject wants; that table's first row is this case.

**`@condition` fixtures.** Two contracts, both from the user manual, because here you are writing what a
consumer writes:

- Keep the predicate bound to the calling fetcher's `FROM` clause. Resolve columns through
  `table.field(Film.FILM.FILM_ID)`, never the static handle, so jOOQ renders the `WHERE` against the
  alias the caller is selecting from. See `docs/manual/how-to/add-custom-conditions.adoc`.
- On a `@lookupKey` field, honour the N x M contract: each `(parent, lookup-value)` pair is kept in full
  or dropped in full, and no condition may introduce rows or filter non-uniformly across parents. See
  `docs/manual/explanation/batching-model.adoc`. Stated with its gap: this contract is developer
  discipline, not build-enforced. A violation surfaces as rows under the wrong parent at runtime, not as
  a red build, so the fixture's own test is the only thing standing between you and that symptom.

**The emitter surface, which inverts.** On the emit side the plan already decided. A command carries its
own driving relation (`LaunchSource.AnchorTable` carries the table; `ParentCorrelation.parentKeyColumns`
is the batch grain, and it is a pure projection off the correlation arm so grain and topology cannot
drift apart). So when you are in `ProjectionUnitRenderer` or `BatchedRowsFragments`, read the
`ParentCorrelation` arm to learn what the grain already is. Finding yourself *choosing* a grain or a
driving table inside a renderer is the smell of an incomplete command, not a query-authoring question:
fix the plan.

## 5. Close with the pin

A query is not done when it returns the right rows once.

- **State the grain** in the relation's `COMMENT ON`, as the sentence step 1's test accepts. This is what
  makes step 1 possible for the next author, and it is the only part of the pin that no test can supply.
- **Pin what the view returns given rows** with a seeded test in `graphitron-model`'s test tree, over
  `SeededStore`. Include the case where *no* row appears: an empty answer is a fact about the population,
  and a read that cannot distinguish "wrong name" from "nothing captured yet" is a different bug wearing
  the same clothes.
- **A deep derivation owes a cost warning in its own comment**, because the cost is invisible at the call
  site. Say which shape is expensive and why; point at the page for the general rule rather than
  restating its measurements.
- **On the emitter surface**, the sakila SQL baseline tests in `graphitron-sakila-example` pin the
  rendered shape. They are pins, not the specification: a baseline is never the oracle for what shape is
  *correct*, and a re-baseline must preserve what its shape demonstrates. A baseline that moved because
  you changed the shape is a question to answer, not a string to update.

## 6. Smells, with a live exemplar each

- **An invented grouping key.** Several statements at several grains reassembled with accumulators and a
  synthetic key is a relational join written in Java. `fact-model.adoc`'s thesis names the three ways it
  fails: the key can be invented wrong, consistency has to be argued rather than held, and the row count
  crossing JDBC becomes the product rather than the sum.
- **Grains folded in Java.** `SchemaQueries` is the counter-exemplar: it pairs on the type's own key
  rather than a grouping invented there, so a mis-paired child cannot arise from a projection that never
  joins siblings together.
- **A deep derivation correlated per driving row.** `intent_column_match_claim`'s own `COMMENT ON` carries
  the measurement and the rule that a relation joining a derivation this deep wants the derivation first
  in the `FROM` clause. Read the number there; do not copy it here.
- **A predicate bound to the wrong alias.** `ReferencePathConditionFixtures.splitFilterParentIncluded` is
  the grain-proof fixture, and it exists because an emitter once bound the hop-0 *target* alias as both
  parameters. Its concrete parameter types make the argument order a compile error rather than a wrong
  answer at runtime.

## Citation policy

This document cites doctrine pages and class names, never `file:line`, and it restates no measured
number. Line numbers are protected by nothing. Class names are at least partly protected: the javadoc
reference gate checks `{@link}` targets, and the tests bearing these names fail or fail to compile when
one moves, so a rename leaves a grep hit rather than silence. Nothing scans this file, so keep it to
names that a reader can find and a rename would disturb.

Manual pages are cited only in the `@condition` section, where the reader is playing the consumer's role.
Everywhere else the sources are contributor-side: `fact-model.adoc` for the doctrine,
`docs/architecture/reference/emitter-conventions.adoc` for the emit surface's own conventions,
`docs/architecture/how-to/testing.adoc` for harness choice.
