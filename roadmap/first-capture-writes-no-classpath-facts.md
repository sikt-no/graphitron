---
id: R973
title: "A first capture into an empty store writes no classpath facts, so every verdict the store derives from the classpath comes out short"
status: Backlog
bucket: architecture
priority: 2
theme: model-cleanup
depends-on: []
created: 2026-09-24
last-updated: 2026-09-24
---

# A first capture into an empty store writes no classpath facts, so every verdict the store derives from the classpath comes out short

## Goal

A consumer's first `graphitron:capture` into an empty store writes the classpath family, the `code_`
relations that record the consumer's Java methods, types, record slots, `@service` and `@condition`
methods, and does not leave it empty. Today it does. On the `sis` consumer, a capture into an absent
`target/graphitron-model` finishes with `store_graph_source` at 47 rows and every `code_` table at 0
(`code_method`, `code_type`, `code_type_element`, `code_type_slot`, `code_service_method`,
`code_condition_method`); a second capture into that same store fills them (2294, 425, 400, 820,
1802 and 460 rows). The *fact store* is the H2 database each generator pass captures the schema, the
jOOQ catalog and the classpath into; a *cold* store is one no capture has written yet, which is what
every build after `mvn clean` starts from. What the store derives from classpath facts is short on a
cold store as a result: `intent_field_accessor_hop` holds 0 rows against 10180, so the type-backing
closure written by `TypeBackingRows` stops at its seeds (`intent_type_backing_class` 106 rows against
125), and `graphitron_field_column_scope` holds 2802 rows against 3199. The warm store is a strict
superset of the cold one in both tables.

## What was observed

- Seen on 2026-09-24 while taking R955's `sis` reading, on both the tree before the register left
  (`fc327fb`) and on trunk at `d0a86d0`, identically in three cold runs, so it predates R955 and is not
  a timing artefact.
- In the `-X` log of a cold capture the classpath gatherer issues its `DELETE ... WHERE source_name IN
  (...)` statements and no `merge into "PUBLIC"."CODE_*"` batch at all; the warm capture issues both.
  The two `DELETE` lists also start from different sources: the cold one from a jar under `~/.m2`, the
  warm one from the `sis` module's own build output.
- The `sis` run was `no.sikt:graphitron-maven-plugin:10-SNAPSHOT:capture` in `sis-graphql-spec`, `sis`
  commit `9a17d34d` plus an uncommitted working tree. Not yet shown: whether `generate`, as opposed to a
  store reader such as the LSP or the MCP server, reads what is missing, and whether a fixture store
  reproduces it.

## What a spec should settle

Why the classpath gatherer writes nothing on a first capture: a stamp or `read_at` comparison that
answers "unchanged" against an empty store, a source list that is not yet known when the gatherer runs,
or something else. Then a test at the capture tier that captures once into an empty store and asserts
the `code_` family is populated, which is the case no current fixture seems to exercise.
