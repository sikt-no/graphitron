---
name: capability-catalog
description: Maintain the graphitron capability catalog at `capabilities/<slug>.adoc` — add, edit, suggest, audit, dedupe, and remove capabilities. Use when the user asks to "add a capability for X", "list capabilities", "suggest missing capabilities", "audit the catalog", "remove the foo capability", "edit the pagination capability", or any phrase about graphitron's capability namespace. The catalog is the source of truth for the slug namespace `@capability(name:)` and `@exemplifies(capability:)` reference; every authored slug must surface on at least one coordinate, every directive value must resolve to an authored slug.
---

# Capability catalog

Operates on `capabilities/<slug>.adoc` files. Each capability is a durable, named *capability* graphitron delivers (pagination, typed-errors, polymorphic-dispatch, federation-entities, ...). The slug `id` is the join key the rest of the project uses; the title is human-readable; the body is a working definition that grows over time as worked examples register via `@exemplifies` (R112).

## Capability identity: slug

Lowercase kebab-case, descriptive of the capability not the implementation (`pagination`, not `connection-types`; `typed-errors`, not `error-channel`). Slugs must be stable from authorship onward — once an `R<n>`, an `@capability(name:)` directive, or an `@exemplifies(capability:)` directive references a slug, renaming requires a coordinated update across every referer. Pick names that read well in three contexts: as a directive value (`@capability(name: "pagination")`), as a roadmap front-matter cross-reference (`relates-to: [pagination, typed-errors]`), and as a docs-site URL (`/capabilities/pagination`).

## File shape

```adoc
---
id: <slug>
title: "<title>"
---

= <title>

One-sentence definition of what this capability is and how a developer recognises it in a graphitron-generated API.
```

The one-sentence body is the bar; preamble prose fills in lazily as `@exemplifies` operations register worked examples the body can reference.

## Subcommands

The skill recognises six intents from the user's request. Pick one and execute.

### list

Print slugs and titles in one block, sorted:

```bash
ls capabilities/*.adoc 2>/dev/null
```

If the directory does not yet exist, say so (R115 has not seeded it yet) and stop.

### add `<slug>` `--title "<title>"`

Create `capabilities/<slug>.adoc` with the file shape above and a one-sentence definition. Validate slug rules before writing (lowercase kebab-case, no `directive-` or `impl-` prefix, no implementation jargon). Refuse if the file already exists; suggest `edit` instead.

### edit `<slug>`

Resolve the slug to the file (filename = `id`). Surface current content; help the user refine the one-sentence definition or grow preamble prose. Don't rename slugs through this subcommand — renames are explicit multi-file edits across SDL, roadmap front-matter, and docs links, with no shortcut.

### suggest

Walk discovery sources and propose candidate slugs not yet authored. This is the load-bearing subcommand for R115's seeding pass and for ongoing capability-gap detection.

The walk is idempotent against canonical state: before listing candidates, subtract the set of already-authored slugs (from `capabilities/*.adoc`) and the set of coordinates already carrying `@capability(name:)` in the SDL. Re-running `suggest` after authoring stubs yields a shrinking list, never a repeat. State lives in the catalog and the SDL; this subcommand reads, never writes.

1. **Directives reference.** For each `docs/manual/reference/directives/<directive>.adoc`, identify the user-facing capability it exposes. Most map 1:1 (`@discriminator` → `polymorphic-dispatch`; `@table` → `table-types`); some cluster (`@key`/`@field`/entity dispatch → `federation-entities`); some are infrastructure that don't surface as a capability (`@condition`, `@notGenerated`, `@experimental_constructType`). Flag any directive whose capability is not authored.
2. **Classifier sealed-variant families.** Grep `graphitron/src/main/java` for `sealed ` declarations producing fetchers; cross-check that every non-trivial family maps to at least one capability. Implicit capabilities like `selection-aware-fetching` and `node-id` surface here when no single directive corresponds.
3. **Sakila schema.** Read `graphitron-sakila-example/src/main/resources/graphql/schema.graphqls` and `federated-schema.graphqls`; flag directive uses on coordinates whose capability slug isn't authored.

Output: a ranked list of candidate slugs with one-sentence rationale and which discovery source surfaced them. The user decides which to author; this subcommand never writes files.

### audit

Run integrity checks against the current repo state. R112's validator will eventually fail the build on the orphan / dangling-reference findings; until it ships, the skill simulates.

1. **Orphan slugs.** Authored `.adoc` whose slug never appears in `@capability(name: <slug>)` across the SDL or in `@exemplifies(capability: <slug>)` across operation `.graphql` files. Surface as candidates for removal or coverage gaps.
2. **Dangling references.** `@capability(name:)` or `@exemplifies(capability:)` values with no authored `.adoc`. Surface as authoring tasks.
3. **Potential duplicates.** Slugs whose titles or one-sentence definitions overlap heavily, or where the same coordinate carries two `@capability` tags whose semantics could collapse. Flag pairs for human review; never merge automatically — slug renames are coordinated multi-file edits.
4. **Missing surfaces.** Sealed-variant families producing non-trivial fetchers with no capability covering them; this is R115's acceptance check expressed as a continuously-runnable diagnostic.

Output: a finding list per category. The user decides remediation; this subcommand never writes files.

### remove `<slug>`

Refuse if any `@capability(name: <slug>)` or `@exemplifies(capability: <slug>)` reference exists anywhere in the repo (SDL files, operation `.graphql`, roadmap markdown, docs `.adoc`). Surface the references and stop. Otherwise delete `capabilities/<slug>.adoc`.

## Hard rules

- The `.adoc` files in `capabilities/` are canonical. The DuckDB knowledge base R117 frames is a projection rebuilt every build; never edit a capability through any other surface.
- Slug = capability not implementation; lowercase kebab-case; stable from authorship. Renames are coordinated multi-file edits, not a subcommand.
- `id:` in frontmatter must equal the filename slug. If they disagree, the filename wins; correct the frontmatter.
- One sentence per slug is the bar during seeding. Long-form preamble grows lazily as `@exemplifies` worked examples land via R112; don't author multi-paragraph prose just because a stub feels thin.
- `add`, `edit`, and `remove` write the catalog. `suggest` and `audit` are read-only — they propose; the user decides.
- Drift between the catalog and `@capability` references is structurally a validator concern (R112). The skill is the human-facing companion; the build is the enforcement boundary.
