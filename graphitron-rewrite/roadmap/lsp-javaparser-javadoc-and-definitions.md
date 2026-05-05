---
id: R90
title: "LSP Javadoc surfacing + per-line definitions + @externalField completion"
status: Backlog
bucket: Backlog
priority: 17
theme: legacy-migration
depends-on: []
---

# LSP Javadoc surfacing + per-line definitions + @externalField completion

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

Phase 5 (shipped as `ed5ebf3` → `39ca34f` → final 5c commit) cut these
three out and delivered class-FQN + method-name autocomplete only,
driven off a JDK 25 `java.lang.classfile` walk of `target/classes`.
The trade-off: bytecode enumeration is fast, depends only on the
stdlib, and covers the high-value autocomplete path. Javadoc /
per-line / source-walk all need a real `.java` parser; JavaParser
(`com.github.javaparser:javaparser-symbol-solver-core`) is the
non-JDK-internal option, but adding the dep is non-trivial (large
transitive graph; symbol solver brings type inference machinery we
do not need for these reads).

This item lands when the trade-off shifts. Triggers worth watching:
consumer feedback that hover-without-Javadoc is awkward; goto-
definition feedback that file-level navigation is too coarse; an
`@externalField`-heavy schema where typing the full FQN
unassisted slows authoring.

What this item should deliver:

1. Add `com.github.javaparser:javaparser-symbol-solver-core` to
   `graphitron-rewrite/graphitron`. Pin the version in the parent
   pom alongside lsp4j / jtreesitter.
2. Thread the consumer's source roots through `RewriteContext`
   (additional field, populated from `MavenProject.getCompileSourceRoots()`
   on the maven-mojo side; null / empty when not available, e.g.
   from unit-tier callers that build catalogs without a real
   project).
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
4. Light up the four LSP entry points: hover formatters already
   render `description`; goto-definition already reads
   `SourceLocation.line` / `column`. `@externalField` completion
   needs a new `ExternalFieldCompletions` provider keyed on the
   nested `reference: { className, method }` slots, registered in
   `GraphitronTextDocumentService`.
5. Tests at all three tiers: unit (`SourceWalker` against synthetic
   source-root tempdirs), pipeline (`CatalogBuilder` populating
   descriptions from a fixture source root), and end-to-end
   completion / hover / definition assertions.

Out of scope: Javadoc rendering — the LSP returns the raw
description string; Markdown / HTML rendering happens client-side
or stays as plain text. Any change to the
`MethodRef.ParamSource` taxonomy or to the rewrite generator's
classifier-driven `Parameter.source` population is also out of
scope; that is generator-side work and the LSP does not need it
for hover.

Predecessor: R18 (`graphitron-lsp.md`). The R18 plan body and the
"Out of scope" / "Open questions" sections reference this item by
slug.
