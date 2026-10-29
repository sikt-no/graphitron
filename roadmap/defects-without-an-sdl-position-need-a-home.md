---
id: R1125
title: "A defect that has no written SDL position has a relation to live in, beside the SDL-positioned one"
status: Backlog
bucket: architecture
priority: 4
theme: diagnostics
depends-on: []
created: 2026-10-29
last-updated: 2026-10-29
---

# A defect that has no written SDL position has a relation to live in, beside the SDL-positioned one

## Goal

A rule the configuration breaks against a corpus, which has no written SDL position, becomes a stored defect row like any other, so a detection mints its `Rejection` from a row and the editor and the build report read it from the same vocabulary. Today the store has nowhere to put one: `graphitron_entry_defect` is keyed by a written SDL position (a foreign key to `graphql_ast_entry`, so every defect is somewhere an editor can point and goes when the document stops writing it), and the only position-less home for an error, `rejection_validation_error`, is marked transitional. A configuration error that can only be found by reading the classpath or the catalog (a configured class that no classpath entry holds, a configured table that has no column of the kind it needs) therefore has to be thrown from a walk or minted by a detection that reads rows and stores none.

## Why it is open

The first idea, named while specifying the per-request session identity work, is a sibling relation: `graphitron_entry_defect` stays the SDL-positioned kind, and a `graphitron_config_defect` joins it, keyed by graph, the configured element's path and code, with each code declared once in `graphitron_defect_type` whichever kind carries it, and derived by its own rule view. That is a recommendation, not a decision. The question is the subtype shape, and it is not settled here:

* Two sibling tables, or a supertype with an SDL-positioned subtype and a configuration subtype. A supertype keeps one place a reader asks "what is wrong with this graph", at the cost of migrating `EntryDefects`, `graphitron_entry_defect_site`, the defect readers (`DefaultPageSizeDefects` and its siblings) and the diagnostic arms; two siblings leave those alone and give every cross-kind reader a union.
* What identifies a configured element. A path string (`sessionState.handle`) carries no foreign key, so a config defect would not cascade when the configuration stops writing the element, and the deriving step's own delete-and-reinsert over the graph's partition becomes the sweep. Whether that is enough, or the configuration capture should state its elements as rows a defect can reference, is part of the question.
* Whether the editor should see a configuration defect at all: there is no position in `pom.xml` to point at, so the likely answer is the build report only.

## Consumers waiting on it

* The per-request session identity item (`owned-session-state-supplied-per-request`): a configured `<handle>` class that is missing or not public. Until this item lands that case is a detection over the configuration row and `code_class` that mints one schema-wide `Rejection` and stores nothing; it moves into the new relation when it exists.
* The transactional outbox item (`transactional-outbox-directive`): its `<outbox>` block checked against the catalog (a missing table or column, an unsupported payload type, an unmapped `NOT NULL` column).

## Plan

To be written when the item is picked up: settle the subtype shape above first, with the `principles-architect` subagent, then follow `adding-a-relation` for each new relation (owner, grain, mark and sweep) and move the two consumers' detections onto it.
