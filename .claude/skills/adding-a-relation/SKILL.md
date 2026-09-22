---
name: adding-a-relation
description: Decide how a new fact gets into the store: which family it belongs to, whether it is captured from a corpus or derived in the anchoring phase, and what a stored relation owes. Use before adding any relation or view to graphitron-model.sql, and before adding a query that re-derives something at read time. Not for diagnosing a slow relation that already exists, which is store-performance.
---

# Adding a relation

Every fact in the store is there because somebody decided it was worth capturing in the model.
Nothing arrives because it was available. The prose version, with worked examples and an honest
account of what is enforced, is `docs/architecture/explanation/modeling-discipline.adoc`.

## 1. Provenance

Either the fact comes from a **corpus** outside the store, or it is **derived** from facts the store
already holds. The corpora are the SDL documents, the classpath, the jOOQ catalog, the configuration
and the Java sources.

Everything below follows from which of the two it is.

## 2. From a corpus: capture into a family shaped by the questions

Not shaped by the corpus. Mirroring the source feels neutral and is not.

A family that describes its source accurately, and says nothing about what any of it is for, pushes
the real work onto every consumer, which then derives the same thing again from the same rows on
every question it asks. The `jvm_` family is that mistake and is being replaced.

**The order that works:** start with a relation per use site, so the fact a consumer wants is a row
rather than a predicate it has to re-derive. Then normalize, against the queries the generator, the
LSP and the MCP server actually make. Normalizing is mostly mechanical once you know which facts you
are holding; deciding which facts the model has to carry is the hard part, and only the questions
tell you.

A family carries **both the base facts and the aggregated facts we know we need**. Both kinds belong
in it, because the questions are what the family is for.

## 3. Otherwise it is derived, in the anchoring phase

Gathering and deriving are kept apart. Put the base facts in the store first; derive afterwards, in
the **anchoring phase**, which is the gatherer's last step rather than a gatherer of its own.

It is last because aggregating needs the base facts for the whole corpus, not for one document.

Two reasons for the separation:

- Base facts and aggregated facts answer different questions, and facts are additive. Neither
  replaces the other; a consumer reads whichever answers its question.
- SQL is very good at deriving tables. Once the base facts are in, the aggregation is a statement
  rather than code.

## 4. Entries and anchors, where a corpus has many documents

Where a corpus is many documents that can say different things about the same thing, entries sit at
the source document grain so the store can hold what each document said, and anchors sit at the
grain a consumer asks at.

**How a collision resolves is the decision that matters, and it differs from family to family.** In
`graphql_` the oldest file wins, because the newer definition is the one a developer changed most
recently, so that is where the error was introduced. Decide this deliberately for whatever you are
adding; do not inherit another family's rule by accident.

## 5. Anchor into a table, never a view

A table carries a primary key, foreign keys, uniques and checks. That is how integrity lives in the
database rather than in the code that fills it, and it makes an illegal state unwritable rather than
merely unexpected. A view carries none of it.

This is not a performance argument. A view can be fast and still cannot be an anchor.

## 6. Reads go upstream only

`meta_gatherer_dependency` names the upstream families a gatherer may reach while anchoring.

Reading a family that runs later does not work: best case the rows are not there yet, worst case
they are there and subtly wrong.

## 7. What a stored relation owes

- **An owner**, which is the code that writes it, declared in `meta_relation`.
- **A grain**, said as a sentence. One row per field, one row per foreign-key hop. If the sentence
  does not finish cleanly the relation is holding two facts. Write the reader's query first: that is
  what exposes a wrong key.
- **A mark and a sweep.** A reading marks what it wrote and sweeps what it did not, so a thing
  removed from a corpus is removed from the store and `ON DELETE CASCADE` collects what hung off it.
  Where an orphan should survive to be noticed, the reference uses `ON DELETE SET NULL` instead.

## 8. What will actually stop you

`MetaDeclarationGateTest` will stop a new relation with no owner and no grain. The undeclared roster
only shrinks, so a new arrival has nowhere to hide. That gate works and you will meet it.

It will not catch a missing sweep, a wrong grain as opposed to a key disagreeing with its
declaration, what a hand-written jOOQ producer reads, or a declared view reading a relation still on
the roster. A green build means the declaration exists and is internally consistent. It does not mean
the relation is right.

## Do not model on `intent_`

That family accumulated before this discipline settled and is dissolving. It is large, so you will
meet it early. Do not take a shape found there as precedent.

## Boundary with `store-performance`

This skill decides **how a fact gets into the store**. `store-performance` diagnoses **an existing
relation that is slow**.
