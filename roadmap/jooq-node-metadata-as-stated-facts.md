---
id: R710
title: "The jOOQ crawler records node metadata as stated, not as validated"
status: Backlog
bucket: architecture
priority: 4
theme: classification-model
depends-on: []
created: 2026-08-18
last-updated: 2026-08-18
---

# The jOOQ crawler records node metadata as stated, not as validated

The store holds no record of the `__NODE_TYPE_ID` / `__NODE_KEY_COLUMNS` constants a jOOQ-generated
table class publishes. `CatalogFactCapture` does not mention them; the only reader is
`JooqCatalog.nodeIdMetadata`, which reflects on the class at pipeline time and answers a validated
question (`Optional.empty()` for malformed, with the reason available separately through
`nodeIdMetadataDiagnostic`). So the metadata exists only as a live reflection result, never as a
fact, and the one consumer that needs it during capture reaches across corpora to get it.

This item records it as a fact, and records it *as stated*.

## Why as-stated matters here

Capture transcribes what a corpus says; it does not decide whether what the corpus says is
admissible. The two are separable for this metadata, and keeping them separate is what lets the
jOOQ crawler own its corpus without knowing anything about the SDL that will eventually claim
against it. A malformed constant is a fact about the consumer's generated code, and a fact worth
having: it is exactly the state an author needs a diagnostic about, and today it is indistinguishable
in the store from a table that publishes nothing at all.

So the rows go in whether or not the metadata is well-formed, and well-formedness becomes a
question asked of the rows afterwards: the type id non-empty, the key-column list non-empty, and
every entry resolving to a `sql_column` of that `sql_table`. All three are joins inside the jOOQ
corpus, so the check is a legal derivation over one corpus rather than a validation smuggled into a
crawler.

## Shape

- A child relation under `sql_table` recording the stated metadata: the type-id value as found, and
  one row per `__NODE_KEY_COLUMNS` entry with its ordinal and the column name as found. Keyed the
  way `sql_table` is keyed, since the metadata is a property of the table rather than of the class.
  Written by the crawler that already reads the catalog, from the catalog it already holds, so no
  new source and no new coupling.
- The ordinal is load-bearing and must be recorded, not reconstructed: `JooqCatalog` documents that
  the encoded identity depends on the declared column order, so a reader that recovered the order
  from anywhere else would encode different IDs.
- The well-formedness derivation, plus the reasons `nodeIdMetadataDiagnostic` composes today, so the
  diagnostic text has a fact base to read from instead of a reflection call.

Deliberately additive. Nothing reads the new rows when this ships, `JooqCatalog` keeps its live
reflection path, and no behaviour changes. The reader arrives with the sibling item that makes
nodehood a derivation.

## Out of scope

- Nodehood itself, and the capture-time cross-corpus read that decides it today.
- Retiring `JooqCatalog.nodeIdMetadata`'s reflection path in favour of the rows.
- Any other jOOQ metadata convention the generator publishes.
