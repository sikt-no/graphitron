---
id: R117
title: "Graphitron knowledge base programme: DuckDB as queryable model"
status: Backlog
bucket: architecture
theme: tooling
depends-on: []
last-updated: 2026-07-14
---

# Graphitron knowledge base programme: DuckDB as queryable model

This item is a *programme*, not a single deliverable. It frames the DuckDB store graphitron emits at build time as a queryable model of everything graphitron knows about itself: the SDL it parses, the classifications it produces, the code it generates, the runtime it observes, the documentation it ships, the roadmap that drives it. R104 introduced the store as a coverage scratchpad. R112 extends it with operations, capabilities, and runtime trace. The programme this item defines is the deliberate continuation: keep adding dimensions, keep them naturally keyed, keep the store a *projection* (rebuilt on every build, never a competing source of truth), and grow toward a knowledge graph queryable end-to-end. The consumers are build-internal (see "Consumers" below): each dimension absorbed makes the next coverage view, doc render, or static check materially cheaper to write.

## What's already there or in flight

| dimension | item | shape |
|---|---|---|
| coordinate × classification (codegen) | R104 (shipped); R112 renames + extends | `classification`, `classifier_call`, `generated_fetcher` |
| coordinate × runtime fetch | R112 | `fetcher_call` |
| operation corpus | R112 | `operation` |
| capability catalog | R115 (slug list); R112 (`@capability`/`@exemplifies` directives + tables) | `capability`, `capability_coordinate`, `operation_exemplifies` |
| roadmap mentions of classifications | R104 (regex); programme target → parsed | `roadmap_mention` |

## Adjacent dimensions to absorb

Each row below is a candidate Backlog item to be drafted as the need surfaces. The shape pattern is the same for all of them: a builder ingests the source artefact at the appropriate phase (parse, codegen, runtime, file-scan), emits a JSONL stream during the build, and the report tool loads it into a naturally-keyed DuckDB table. The KB rebuilds from scratch on every `mvn install`; nothing in DuckDB is authoritative.

| dimension | natural keys | source |
|---|---|---|
| full SDL | `(parent_type, field_name)`, type names, directive names | `sdl_type`, `sdl_field`, `sdl_directive_application` from a one-pass GraphQL parse; descriptions stored as VARIANT |
| classifier reasoning | `(parent_type, field_name, classification, arm)` | extend `ClassificationTrace` to record the arm it dispatched and the reason; today only the result is captured |
| validator findings | `(parent_type, field_name, finding)` | the validator already runs at codegen time; it just doesn't persist |
| generated code catalog | `(parent_type, field_name)` → `(class_name, method_name, file_path)` | walk the generated source tree post-codegen |
| jOOQ catalog | `table_name`, `column_name`, `foreign_key_name` | the codegen already loads the catalog; persist what it sees |
| roadmap front-matter (parsed) | `roadmap_id`, `slug`, `(roadmap_id, dependency_slug)` | replace the regex-scan `roadmap_mention` with a YAML parse |
| docs site cross-links | URL path, anchor, source file | post-AsciiDoctor render pass |
| test-class membership | `(test_class, operation_key)` | annotation or convention scan in `OperationRunner` callers |

## Programme principles

*Natural keys, never surrogates.* Every table joins on coordinate, operation key, classification name, capability slug, type name, column name, or roadmap id. Surrogate keys are forbidden unless a fork explicitly justifies the absence of a natural one. Joins read off identity directly; aggregations compose without a JOIN graph that takes a working memory to navigate.

*The KB is a projection, not a source of truth.* SDL files, generated source, roadmap markdown, capability `.adoc`, and operation `.graphql` files remain canonical. The KB is a parse of those files. `mvn install -Pleaf-coverage` rebuilds the entire store; nothing in DuckDB ever needs migration, rollback, or schema versioning. If the KB and a source file disagree, the source wins and the KB is regenerated.

*Composition over special-casing.* New dimensions land as additional tables, joining the existing ones on the natural keys above. Views compose from joins; the KB never grows a "and now this dimension is the join hub" kind of asymmetry. If a new dimension can't be reduced to natural keys plus joins, that's a signal the dimension isn't ready to land.

*Every dimension is opt-in via the existing build profile.* `-Pleaf-coverage` is the toggle today; renaming it to `-Pkb` or similar at some point is fine, but the principle holds: the KB is built only when asked, never costs the production-codegen path, and is harmless to ship.

*Schema vocabulary is SQL-shaped, not Java-shaped.* Java-side metaphors that read badly in SQL get translated at the boundary (`leaf` → `classification`, `LeafCoverageReport` keeps the Java class name with a header comment bridging to the SQL term). Names are singular (`classification.name`, not `classifications.name`).

## Decomposition

This item is the navigational artefact. As dimensions absorb, each becomes its own Backlog item that:

- Names the source artefact and the natural keys.
- Adds the JSONL emit at the right phase.
- Adds the DuckDB table and the loader.
- Lists at least one view or query that lights up only after the dimension lands (a forcing function: a dimension without a consumer is premature).
- References this item in `depends-on:` *upward* only as a navigational marker; the per-dimension items don't actually block on this programme item shipping (it's a programme; it doesn't ship).

Authored items contributing to the programme today: R104 (shipped), R112, R115. Future items reference this programme so the body of work stays coherent across many small landings.

## Consumers

(Rewritten 2026-07-14; the original section named R118, the graphitron MCP server, as the KB's first non-build consumer, complete with a free-form SQL tool. R118 shipped differently and this section now reflects what actually exists.)

The KB's consumers are build-internal: the leaf-coverage report (R104), the roadmap-tool verify-mode drift checks, and whatever coverage views, doc renders, or static checks later dimensions make cheap to write. That is where the programme's value lands, and none of it depends on an external query surface.

The shipped MCP server is not a KB consumer and should not become one by default. It is a live-workspace server for *consumers of graphitron* (people authoring SDL against their own database): its tools parse the SDL, read the jOOQ catalog, scan the classpath, and run the validator directly, with Lucene-backed search. That design is deliberate for its audience; consumer workspaces have no rewrite-build JSONL to load, and live reads cannot go stale. The KB, by contrast, is introspective: it models what the graphitron repo knows about itself (classifications, traces, operations, capabilities, roadmap), for people and agents working *on* graphitron.

A dev-facing query surface over the KB (free-form SQL over the joined dimensions, exposed to agents working on this repo) remains a plausible future consumer, and the natural-key join design keeps it cheap to add. But it is a candidate item to file when someone actually wants the joins, not a standing assumption of this programme.

## Out of scope for this item

Authoring code or schema directly — this item ships as a navigational artefact whose Done state is reached only when the programme it frames feels complete (which may be never; programmes outlive sprints). The reviewer-rule gates still apply to per-dimension items underneath; this item itself moves through Backlog → Spec → Ready when the programme framing is signed off, and probably stays in Ready indefinitely. Choosing the next dimension to absorb (that's a prioritisation call made when the next dimension's payoff becomes concrete; this item just lists the candidate set). The MCP server (shipped as a live-workspace server, not a KB consumer; a KB-backed query tool would be its own item, see "Consumers").
