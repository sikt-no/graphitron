---
id: R90
title: "LSP Java-source surfacing: goto-definition, Javadoc, @externalField, argMapping"
status: Spec
bucket: Backlog
priority: 17
theme: lsp
depends-on: []
last-updated: 2026-06-19
---

# LSP Java-source surfacing: goto-definition, Javadoc, @externalField, argMapping

Today the LSP resolves goto-definition for GraphQL type references
(`IntraSchemaDefinitions`) and for the jOOQ-backed `@table` / `@field` /
`@reference` directive arguments (`Definitions`, file-level only). The
gap this item closes: the directives that name a *consumer* Java class or
method, `@service`, `@externalField`, `@enum`, `@condition`, and the flat
`@sourceRow` / `@tableMethod` `className` / `method` slots, have **no**
goto-definition, no Javadoc on hover, and (for `@externalField`) no
completion. They light up for completion / hover / diagnostics through
`LspVocabulary`'s `ClassNameBinding` / `MethodNameBinding` behaviors, but
there is nowhere to jump to and no description to show, because the catalog
records `ExternalReference` / `Method` purely from compiled `.class`
bytecode via `ClasspathScanner` (JDK `java.lang.classfile`), which carries
no source path and no declaration line.

The unblocking insight: read the consumer's `.java` sources to recover
declaration positions and Javadoc, and attach them to the catalog. The
parser is the JDK's own Compiler Tree API, not an external dependency (see
"Parsing approach" below). A sibling Rust implementation
(`alf/graphitron-lsp`, tree-sitter-java) validates the overall shape:
walk the source roots, index class / method declaration ranges, then
dispatch goto-definition per directive off that index. We take the shape,
not the code.

## What changed from the original Backlog body

This Spec deliberately diverges from the Backlog draft on two points;
everything else is preserved.

1. **Parser: JDK Compiler Tree API replaces JavaParser.** The Backlog body
   specified `com.github.javaparser:javaparser-symbol-solver-core` and
   pinning its version in the parent pom. We drop that dependency entirely
   in favor of the JDK-internal `com.sun.source.util` API (details below).
   This is the change that was discussed and approved before this rewrite:
   it removes the "adding the dep is non-trivial; symbol solver brings type
   inference we do not need" objection that kept the item in Backlog. The
   slug (`lsp-javaparser-...`) is left unchanged so the R18 cross-reference
   and any commit/changelog mentions still resolve; it is now historical,
   not descriptive.
2. **Goto-definition for consumer Java refs is made an explicit, net-new
   deliverable.** The Backlog body framed the definition work only as
   "per-line refinement" of `Column.definition` / `Method.definition`,
   which assumed a file-level definition already existed. It does not for
   the consumer-Java directives: `ExternalReference` / `Method` carry no
   `SourceLocation` at all, and `Definitions.compute` has no dispatch arm
   for `@service` / `@externalField` / `@enum` / `@condition` / `@sourceRow`
   / `@tableMethod`. So this is new capability, not refinement. The
   refinement of the existing jOOQ-side `Column` definition is kept as a
   secondary deliverable.

No original requirement is dropped: Javadoc surfacing (Phase 1),
`@externalField` completion (Phase 3), and argMapping completion +
diagnostics (Phase 4) all remain, restated below.

## Parsing approach: JDK Compiler Tree API

The generator runs on Java 25, so the `jdk.compiler` module is present and
its `com.sun.source.tree` / `com.sun.source.util` packages are exported as
supported API (no `--add-exports`; we touch no `com.sun.tools.javac.*`
internals). The walk is parse-only:

- `javax.tools.ToolProvider.getSystemJavaCompiler()` →
  `compiler.getTask(...)` cast to `com.sun.source.util.JavacTask`.
- `task.parse()` returns `CompilationUnitTree`s **without** attribution, so
  no classpath resolution is required and the walk is fast and tolerant of
  unresolved symbols.
- A `TreePathScanner` visits `ClassTree` / `MethodTree` / `VariableTree`.
  `Trees.instance(task).getSourcePositions().getStartPosition(cu, tree)`
  plus `cu.getLineMap()` gives the declaration's line / column;
  `Trees.getDocComment(path)` gives the Javadoc text.

This single walk feeds all of goto-definition, Javadoc, and the
`@externalField` method index, exactly as the JavaParser walk would have.

Two hazards to pin during implementation, both cheap one-liners that are
expensive to discover late:

- **Doc-comment retention.** `Trees.getDocComment(path)` returns null unless
  the task is created so doc comments survive the parse. Configure the
  `JavacTask` to keep them; this is the most likely "Javadoc comes back
  empty" surprise.
- **Module access.** The "no external dependency" claim rests on `graphitron`
  being able to read `jdk.compiler`. On the classpath / unnamed module this
  is automatic; if `graphitron` ever gains a `module-info`, it must
  `requires jdk.compiler`.

**Invocation cost (hot path).** `CatalogBuilder` "runs hot": it is rebuilt on
every classpath-watcher trigger, and the watcher fires on `.class` changes.
A full Compiler-Tree-API parse of every `.java` under every source root
(including potentially hundreds of jOOQ generated files) on every trigger
is materially heavier than the current `java.lang.classfile` byte scan. The
`SourceWalker` index must therefore be cached and invalidated per source
file (by mtime or content hash), re-parsing only changed `.java` files;
source positions change only when a `.java` changes, not when a `.class` is
recompiled. State this contract in the walker, tied to the same determinism
the idempotent-writer section pins.

## Phasing

Each phase is a coherent landing unit. Phases 1-3 share the source-walk
infrastructure and ship together or close in sequence; the headline
deliverable is goto-definition (Phase 2). Phase 4 ships independently, with
no dependency on the source walk.

**Phase 1: source-root plumbing + `SourceWalker` + Javadoc.** Thread the
consumer's compile source roots through `RewriteContext` (populated from
`MavenProject.getCompileSourceRoots()` on the maven-mojo side, where
`AbstractRewriteMojo` already has access; null / empty when not available,
e.g. unit-tier callers that build catalogs without a real project). Add a
`SourceWalker` that parses each `.java` under those roots with the Compiler
Tree API and indexes, by fully-qualified name, the declaration position and
Javadoc of each class, method, and field. Attach the Javadoc text to the
`description` slots on `CompletionData.Table` / `Column` / `TypeData` /
`Method` / `ExternalReference`. Hover formatters already render
`description` when non-empty, so hover lights up automatically. The walk
covers both hand-written consumer sources and the jOOQ-generated sources
(generated-sources roots are on `getCompileSourceRoots()`), so table /
column Javadoc surfaces too.

**Phase 2 (headline): goto-definition for consumer Java refs + per-line
refinement.**

- Add a `SourceLocation` field to `CompletionData.ExternalReference` and
  `CompletionData.Method`. `CompletionData` records are immutable and the
  catalog is rebuilt rather than mutated, so this is not a post-hoc patch:
  `CatalogBuilder.build` is the single join site, constructing
  `ExternalReference` / `Method` from `ClasspathScanner` structure (names,
  signatures) plus the `SourceWalker` index (positions, Javadoc) in one
  pass. The join key is `(className, methodName, paramCount)`; on an
  overload collision (same name and arity, e.g. a `@condition` method
  differing only by a `DataFetchingEnvironment` vs context parameter), the
  enrichment is skipped and the `SourceLocation` stays `UNKNOWN` rather than
  binding a wrong line. Keep the test-friendly factories defaulting to
  `SourceLocation.UNKNOWN`, matching the existing `Column` / `Reference`
  pattern, for unit-tier callers that have no walker.
- Add a dispatch arm to `Definitions.compute` for the class-name and
  method-name binding directives. Reuse `LspVocabulary.behaviorAt` /
  `siblingStringAt` exactly as `MethodCompletions` does to read the cursor's
  `ClassNameBinding` / `MethodNameBinding` and the resolved class name, then
  return the `ExternalReference.definition()` (class) or the matching
  `Method.definition()`.
- Refine the existing jOOQ-side `Column.definition` from file-level (`0:0`)
  to the per-line position the same walk now provides. The walker's field
  index is keyed by the jOOQ Java field name (matching `Column.name()`, not
  the SQL column name, which the catalog already distinguishes). The
  existing layout-synthesis in `CatalogBuilder.tableSourceLocation` /
  `keysSourceLocation` stays as the fallback: generated-sources may not
  exist before `mvn compile`, the same state `ClasspathScanner` already
  tolerates, and the walker simply has no entry then. (The Backlog body also
  named `Method.definition`; that slot did not previously exist, so it is
  created above rather than refined.)

When `SourceLocation` is `UNKNOWN` (sources unavailable, e.g. a dependency
JAR with no attached sources), goto-definition returns empty rather than
jumping to a bogus location, and completion / hover continue to work off
the bytecode-derived catalog as today.

**Phase 3: `@externalField` completion.** Index every
`public static Field<X> name(<Table> table)` method on the source roots
(the Compiler Tree API gives the modifiers, the `Field<...>` return type,
and the single parameter's declared type token syntactically; the "is a
Table parameter" decision stays catalog-driven via the existing
`Parameter.source = ParamSource.Table` model). Register an
`ExternalFieldCompletions` provider keyed on the nested
`reference: { className, method }` slots, registered in
`GraphitronTextDocumentService`.

**Phase 4: `argMapping` autocomplete + validation.** Independent of phases
1-3; no source walk. The `argMapping` string on `@service(service:)` /
`@condition(condition:)` / `@tableMethod(...)` carries a comma-separated
list of `javaParam: graphqlArg` entries (R84 dot-paths on the right for
nested input fields). Add an `ArgMappingCompletions` provider keyed on the
cursor sitting inside that string literal, decomposing the string-content
cursor position into:

- which `javaParam: graphqlArg` entry the cursor is in (split on `,`),
- whether the cursor is left of `:` (Java parameter side) or right (GraphQL
  argument side),
- the prefix already typed before the cursor.

Candidates per side:

- **Left.** The resolved method's parameter names from the catalog's
  `ExternalReference` → `Method` → `Parameter[].name`. Suppress when names
  are null (consumer compiled without `-parameters`); the existing 5c
  diagnostic already nudges toward the fix.
- **Right.** The enclosing field's GraphQL argument list, plus R84-style
  dot-path expansion through the input type's nested fields when the cursor
  sits past a `.`. Before walking the raw `Workspace` GraphQL schema, check
  whether the classified snapshot (`LspSchemaSnapshot`, which already carries
  the input-field projection the classifier computed) can answer the
  right-side candidate query; R84 dot-paths into nested input fields are
  exactly the traversal that projection structures. Fall back to the raw
  schema only for what the snapshot does not project.

Diagnostics: unknown Java parameter on the left; unknown GraphQL argument or
unreachable path step on the right; duplicate Java-parameter entries; empty
entry / dangling `:` / extra `,`.

The string-content cursor decomposition does not slot into the tree-sitter
argument-walking pattern the other providers use and warrants its own
design + tests against malformed inputs; that is why it ships as its own
phase.

## Implementation

- `RewriteContext`: add a `compileSourceRoots` field (list of paths,
  nullable / empty-tolerant); populate it from
  `MavenProject.getCompileSourceRoots()` in `AbstractRewriteMojo`.
- `SourceWalker` (new, under
  `graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/catalog/`):
  Compiler-Tree-API parse-only walk over the source roots, producing an
  FQN-keyed index of `{ SourceLocation, javadoc }` for classes and methods,
  and a field index keyed by jOOQ Java field name. No external dependency.
  Caches parsed results per source file and re-parses only changed files
  (mtime / content hash), per the hot-path contract above.
- `CompletionData.ExternalReference` / `CompletionData.Method`: add a
  `SourceLocation definition` component plus `UNKNOWN`-defaulting factories.
- `CatalogBuilder`: the single join site. Constructs `Table` / `Column` /
  `TypeData` / `Method` / `ExternalReference` from `ClasspathScanner`
  structure plus the `SourceWalker` index in one pass, with `SourceLocation`
  and `description` populated at construction (records stay immutable; there
  is no later mutation). `ClasspathScanner` remains the source of structure;
  the walk supplies positions and Javadoc. Join key for methods is
  `(className, methodName, paramCount)` with ambiguity → `UNKNOWN`.
- `Definitions.compute`: new dispatch arm for `ClassNameBinding` /
  `MethodNameBinding` behaviors (reusing `LspVocabulary.behaviorAt` /
  `siblingStringAt`), returning the class / method `definition()`.
- `ExternalFieldCompletions` (new provider) + registration in
  `GraphitronTextDocumentService` (Phase 3).
- `ArgMappingCompletions` (new provider) + argMapping diagnostics, wired
  into the `@service` / `@condition` / `@tableMethod` cases (Phase 4).

## User documentation (first-client check)

Goto-definition and hover over Java references are a user-visible surface.
The `getting-started.adoc` LSP section should gain a short note: "Cmd/Ctrl-
click (or your editor's go-to-definition) on a `@service`, `@externalField`,
`@condition`, or `@enum` class / method name jumps to its Java source;
hover shows its Javadoc. Requires the consumer's sources to be on the build
(they are, for the project's own `compileSourceRoots`); references into
dependency JARs without attached sources fall back to no jump." If that note
cannot be written simply, the directive surface is wrong; revisit before
implementing.

## Tests

The load-bearing signal is the end-to-end goto-definition behaviour; keep
that matrix primary and let the unit tests cover only the failure modes that
pipeline / end-to-end coverage would make repetitive.

- **End-to-end (LSP), primary.** goto-definition landing on the class /
  method declaration line, one case per binding directive: `@service`,
  `@externalField`, `@enum`, `@condition`, `@sourceRow`, `@tableMethod`.
  Hover showing Javadoc; `@externalField` completion; argMapping completion
  + diagnostics.
- **Pipeline.** `CatalogBuilder` populating `SourceLocation`s and
  `description`s from a fixture source root; `UNKNOWN` fallback when roots
  are absent.
- **Unit (failure modes only).** `SourceWalker` against synthetic
  source-root tempdirs: unparseable / missing files, overload-ambiguity
  → `UNKNOWN` (finding from the join-key rule), missing `-parameters`,
  doc-comment retention. argMapping cursor decomposition against malformed
  inputs (Phase 4). No output is generated Java, so no code-string
  assertions are in play; positions / `Location`s are the asserted shape.

## Out of scope

- Javadoc rendering: the LSP returns the raw description string;
  Markdown / HTML rendering happens client-side or stays plain text.
- Any change to the `MethodRef.ParamSource` taxonomy or to the generator's
  classifier-driven `Parameter.source` population; that is generator-side
  work the LSP does not need.
- Auto-quick-fixes for argMapping diagnostics (e.g. "replace `inputs` with
  `input`); pure diagnostics ship first.
- Symbol resolution / type inference over the parsed sources. The walk is
  positional + Javadoc only; "is this parameter a Table" stays catalog-
  driven, not solved from the parse tree.

Predecessor: R18 (`graphitron-lsp.md`). The R18 plan body and its
"Out of scope" sections reference this item by slug for both the
source-walk-driven work and argMapping.
