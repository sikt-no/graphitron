---
id: R115
title: "Enumerate the capabilities graphitron delivers"
status: Backlog
bucket: architecture
theme: structural-refactor
depends-on: []
---

# Enumerate the capabilities graphitron delivers

Graphitron delivers a set of durable, named *capabilities* — pagination, typed errors, polymorphic dispatch, JSON scalars, federation entities, and others — but no list of those capabilities exists. The roadmap tracks transient implementation work; the SDL describes shape; the docs site narrates use. None of these is a stable namespace of "what graphitron does." That gap blocks several pieces of work that want to reference capabilities by stable slug — most concretely R112's `@capability(name:)` directive, whose value space is speculative until the catalog exists, but also less formally any roadmap item, doc page, or commit message that wants to say "this touches pagination."

This item is *thinking*, not engineering: enumerate what graphitron currently delivers as user-facing capabilities, give each a stable kebab-case slug, and write a one-sentence definition. The output is a directory of authored stub files, nothing more. No directive, no tooling, no DB tables, no docs render, no transitive-closure traversal. Those are R112's job; R112 takes this catalog as a precondition.

## What this item produces

`graphitron-rewrite/capabilities/<slug>.adoc`, one file per capability, with frontmatter:

```adoc
---
id: pagination
title: "Pagination over connections"
---

= Pagination over connections

One-sentence definition of what this capability is and how a developer recognises it in a graphitron-generated API.
```

The slug `id` is the join key the rest of the project will use; the title is human-readable; the one-sentence body is a working definition, not full prose. R112's pilot phase later swaps the one-sentence body for proper preamble on at least one capability; subsequent capability work fills in the rest over time.

## Slug rules

Lowercase kebab-case, descriptive of the capability and not the implementation (`pagination`, not `connection-types`; `typed-errors`, not `error-channel`). Slugs must be stable from authorship onward — once an `R<n>` references a slug, renaming requires a coordinated update across every referer. Pick names that read well in three contexts: as a directive value (`@capability(name: "pagination")`), as a roadmap front-matter cross-reference (`relates-to: [pagination, typed-errors]`), and as a docs-site URL (`/capabilities/pagination`).

## Initial candidate list

A starting list to pressure-test the slug namespace. The author of this item refines the list during Spec, but these are the candidates to argue with rather than start from a blank page:

- `pagination` — connection-style relations with cursor and limit
- `typed-errors` — `@error` types routed to a payload's errors field via a typed channel
- `polymorphic-dispatch` — `@discriminator` interfaces and unions
- `federation-entities` — Apollo-Federation `@key` types and entity dispatch
- `json-scalars` — JSON, JSONB, custom scalar serialisation
- `table-types` — `@table` SDL types backed by jOOQ-generated DML
- `generated-mutations` — `@mutation(typeName: INSERT|UPDATE|DELETE)` synthesis
- `service-backed-fields` — fields delegating to a developer-authored service method
- `node-id` — Relay-style globally unique IDs
- `audit-fields` — automatic audit-column writes (created_by, updated_at, etc.)
- `selection-aware-fetching` — jOOQ projection narrowed to GraphQL selection set

Each becomes one stub `.adoc`. The list will be wrong in some way; that's the point of having a real artefact instead of vapour.

## Why this is its own item

Splitting it from R112 separates two cognitively distinct kinds of work. *Cataloguing* is product thinking: what does graphitron actually deliver, where are the seams, what's the right vocabulary? It benefits from review by someone who knows the product surface. *R112's plumbing* is architecture: directive, DuckDB joins, AsciiDoctor extension. Different review instincts, different reviewers. Splitting also lets the catalog ship and become useful immediately — even before R112 starts, any session can grep `graphitron-rewrite/capabilities/` and know the slug namespace; roadmap items can begin using `relates-to: [<slug>]` cross-references; docs prose can link to `/capabilities/<slug>` even before the page is rendered.

## Out of scope

- The `@capability` directive (R112).
- The `capability_coordinate` table or any DuckDB schema (R112).
- AsciiDoctor render of capability pages (R112).
- Long-form preamble prose for each capability — *one sentence per slug* is the bar; preamble is filled in over time as capabilities surface in worked examples.
- *Worked examples* per capability — examples live in the `.adoc` frontmatter as structured references to operations in the corpus (see R112), but no operations exist before R112 ships, so example authoring is a follow-up pass, not part of this item. R115 produces a stable slug namespace that future example authors can reference; nothing more.
- Cross-referencing existing roadmap items to capabilities (a follow-up item once the slug list is stable).

## Acceptance

A complete enough enumeration that an independent reviewer reading `graphitron-rewrite/capabilities/` and the rewrite-design docs would not be surprised by a "missing capability" finding when R112's pilot lands. Concretely: every classifier-tier sealed-variant family that produces non-trivial fetchers (RootField, ChildField, ResultType variants, etc.) maps to at least one capability slug, and conversely every authored slug corresponds to a recognisable user-facing surface in either the sakila example schema or graphitron's documented directive set.
