---
id: R90
title: "LSP Javadoc surfacing + per-line definitions + @externalField + argMapping"
status: Backlog
bucket: Backlog
priority: 17
theme: lsp
depends-on: []
---

# LSP Javadoc surfacing + per-line definitions + @externalField + argMapping

R18 Phase 5 originally folded three JavaParser-driven capabilities in
alongside `@service` / `@condition` / `@record` autocomplete:

1. **Javadoc surfacing on hover.** Read `.java` aggregator sources for
   tables / columns / scalars / methods and attach the comment text
   to `CompletionData.Table.description` /
   `CompletionData.Column.description` /
   `CompletionData.TypeData.description` /
   `CompletionData.Method.description`. Hover formatters already
   include the description in their output when non-empty, so the
   payload lights up automatically.
2. **Per-line definition refinement.** Phase 4 ships file-level URIs
   on `Column.definition` / `Method.definition`; the JavaParser walk
   that reads Javadoc also gives the line / column of each declaration
   for free, refining goto-definition from "open the file" to "land
   on the field / method".
3. **`@externalField` completion (R48 follow-up).** Index every
   `public static Field<X> name(<Table> table)` method on the
   consumer's source roots and offer them as
   `reference: { className, method }` completions.
   `Parameter.source = ParamSource.Table` already models the
   parameter shape; the source-walk filter is the only new code.

Phase 5d also surfaced a fourth LSP gap that does *not* require
JavaParser:

4. **`argMapping` autocomplete and validation.** The `argMapping`
   string on `@service(service:)` / `@condition(condition:)` /
   `@tableMethod(...)` carries a comma-separated list of
   `javaParam: graphqlArg` entries (with R84 dot-paths on the right
   for nested input fields). Phase 5 left this slot completely
   untouched: no completion, no diagnostics. The catalog already
   carries the resolved method's parameter names (when the consumer
   compiled with `-parameters`); the GraphQL schema already carries
   the field's argument list and input-type field shape. The
   missing piece is a provider that reads the cursor position
   *inside the string literal*, decomposes it into "which entry,
   left or right of `:`, what prefix is already typed", and offers
   the right candidate set.

Phase 5 (shipped as `ed5ebf3` → `39ca34f` → `a672c82` → `bec04f8`) cut
the JavaParser-dependent items out and delivered class-FQN +
method-name autocomplete only, driven off a JDK 25
`java.lang.classfile` walk of `target/classes`. The trade-off:
bytecode enumeration is fast, depends only on the stdlib, and covers
the high-value autocomplete path. Javadoc / per-line / source-walk
all need a real `.java` parser; JavaParser
(`com.github.javaparser:javaparser-symbol-solver-core`) is the
non-JDK-internal option, but adding the dep is non-trivial (large
transitive graph; symbol solver brings type inference machinery we
do not need for these reads). argMapping was deferred separately
because the string-content cursor decomposition is its own design
question, even though the work doesn't need JavaParser.

This item lands when the trade-off shifts. Triggers worth watching:
consumer feedback that hover-without-Javadoc is awkward; goto-
definition feedback that file-level navigation is too coarse; an
`@externalField`-heavy schema where typing the full FQN
unassisted slows authoring; argMapping-heavy mutations where typos
slip through to build-time errors that an editor diagnostic would
catch sooner.

## Phasing

Each phase is a coherent landing unit; phases 1–3 share the
JavaParser dep and ship together or close in sequence. Phase 4
ships independently — it has no dependency on JavaParser or the
source-root plumbing — and may land before, after, or alongside
1–3 depending on which trigger fires first.

**Phase 1: JavaParser + source-root plumbing + Javadoc on tables /
columns / scalars / methods.** Adds the
`com.github.javaparser:javaparser-symbol-solver-core` dep, threads
`compileSourceRoots` through `RewriteContext`, and uses it to
populate the `description` slots on `CompletionData.Table` /
`Column` / `TypeData` / `Method`. Hover formatters already render
`description`; the payload lights up automatically.

**Phase 2: Per-line definition refinement.** Same JavaParser walk
gives line / column of each declaration; refines `Column.definition`
and `Method.definition` from file-level URIs to per-line ranges.
Goto-definition lands on the field / method instead of the file
opener.

**Phase 3: `@externalField` completion.** Indexes every
`public static Field<X> name(<Table> table)` method on the
consumer's source roots and registers an `ExternalFieldCompletions`
provider keyed on the nested `reference: { className, method }`
slots.

**Phase 4: `argMapping` autocomplete and validation.** Independent
of phases 1–3. Adds an `ArgMappingCompletions` provider keyed on
the cursor sitting inside the `argMapping` string of a
`@service(service: {})` / `@condition(condition: {})` /
`@tableMethod` directive. Decomposes the string-content cursor
position into:

- which `javaParam: graphqlArg` entry the cursor is in (split on
  `,` outside of any nested whitespace),
- whether the cursor is left of `:` (Java parameter side) or right
  of `:` (GraphQL argument side),
- the prefix already typed before the cursor.

Candidates per side:

- **Left.** The resolved method's parameter names from the
  catalog's `ExternalReference` → `Method` → `Parameter[].name`.
  Suppress when names are null (consumer compiled without
  `-parameters`); the existing 5c diagnostic already nudges the
  user toward the fix.
- **Right.** The enclosing field's GraphQL argument list, plus
  R84-style dot-path expansion through the input type's nested
  fields when the cursor sits past a `.`. The walker reads the
  GraphQL schema (already in `Workspace`) rather than the
  catalog, so the dependency surface is "schema state we already
  have".

Diagnostics:

- Unknown Java parameter on the left.
- Unknown GraphQL argument or unreachable path step on the right.
- Duplicate Java-parameter entries (same name twice).
- Empty entry / dangling `:` / extra `,`.

The string-content cursor decomposition is the work that pushed
this phase out of Phase 5 — it does not slot cleanly into the
tree-sitter argument-walking pattern the other Phase 5 providers
use, and warrants its own design + tests against malformed
inputs.

What this item should deliver (combined across phases 1–4):

1. Add `com.github.javaparser:javaparser-symbol-solver-core` to
   `graphitron-rewrite/graphitron`. Pin the version in the parent
   pom alongside lsp4j / jtreesitter. (Phases 1–3.)
2. Thread the consumer's source roots through `RewriteContext`
   (additional field, populated from `MavenProject.getCompileSourceRoots()`
   on the maven-mojo side; null / empty when not available, e.g.
   from unit-tier callers that build catalogs without a real
   project). (Phases 1–3.)
3. Add a `SourceWalker` helper under `graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/catalog/`
   that walks the source roots, parses each `.java` file with
   JavaParser, and extracts:
   - File-level URIs that already exist on Phase 4 ranges, refined
     to per-line.
   - Javadoc text per declaration, attached to the matching
     `Column.description` / `Method.description` /
     `TypeData.description`.
   - Public-static `Field<X> name(<Table> table)` method records,
     populated as `ExternalReference.methods` flagged for
     `@externalField` consumption.
   (Phases 1–3.)
4. Light up the four LSP entry points: hover formatters already
   render `description`; goto-definition already reads
   `SourceLocation.line` / `column`. `@externalField` completion
   needs a new `ExternalFieldCompletions` provider keyed on the
   nested `reference: { className, method }` slots, registered in
   `GraphitronTextDocumentService`. (Phases 1–3.)
5. Add an `ArgMappingCompletions` provider plus argMapping-aware
   diagnostics, both wired into `GraphitronTextDocumentService`'s
   `@service` / `@condition` / `@tableMethod` cases. (Phase 4.)
6. Tests at all three tiers: unit (`SourceWalker` against synthetic
   source-root tempdirs; argMapping cursor decomposition against
   malformed inputs), pipeline (`CatalogBuilder` populating
   descriptions from a fixture source root), and end-to-end
   completion / hover / definition / argMapping assertions.

Out of scope: Javadoc rendering — the LSP returns the raw
description string; Markdown / HTML rendering happens client-side
or stays as plain text. Any change to the
`MethodRef.ParamSource` taxonomy or to the rewrite generator's
classifier-driven `Parameter.source` population is also out of
scope; that is generator-side work and the LSP does not need it
for hover. Auto-quick-fixes for argMapping diagnostics (e.g.
"replace `inputs` with `input`") are out of scope; pure
diagnostics ship first.

Predecessor: R18 (`graphitron-lsp.md`). The R18 plan body and the
"Out of scope" sections reference this item by slug for both the
JavaParser-driven work and argMapping.
