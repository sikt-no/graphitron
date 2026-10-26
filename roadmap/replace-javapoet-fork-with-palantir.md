---
id: R174
title: "Replace the graphitron-javapoet fork with Palantir's JavaPoet"
status: Backlog
bucket: architecture
priority: 9
theme: tooling
depends-on: []
created: 2026-05-17
last-updated: 2026-10-26
---

# Replace the graphitron-javapoet fork with Palantir's JavaPoet

## Goal

Graphitron's generator builds every emitted Java file through
`graphitron-javapoet`, our in-tree copy of Square's JavaPoet. Square
archived JavaPoet in October 2024 and its README points to Palantir's
JavaPoet (`com.palantir.javapoet:javapoet`, Apache 2.0, no runtime
dependencies, Java 17 bytecode, released roughly monthly) as the
maintained successor. When this item lands, the generator depends on
Palantir's artifact and the `graphitron-javapoet` module is gone.

What changes for consumers of graphitron: nothing in the generated
code. A trial swap regenerated all 1,376 `graphitron-sakila-example`
sources byte-identical to the fork's output. The plugin's dependency
tree gains `com.palantir.javapoet:javapoet` and loses
`no.sikt:graphitron-javapoet`; neither appears on a consumer's runtime
classpath, since generated code never imports JavaPoet.

What changes for graphitron: we stop maintaining a fork of a code
emitter, and we get the Java language features the fork predates.
Palantir already emits records (`TypeSpec.recordBuilder`,
`recordConstructor`, `MethodSpec.compactConstructorBuilder`) and sealed
types (`addPermittedSubclass`). That unblocks R94's deferred shape (one
record per SDL input type plus a sealed marker interface), which is its
own follow-on slice.

## Why the fork can go

The fork's additions over Square, traced through git history:

- **Private fields behind accessors** (May 2025, at the copy). Palantir
  made the same change with the same accessor names, so it carries over
  for free.
- **Builder helpers for the legacy generator** (July 2025 to April
  2026, the `GG-` commits): the `*If` variants, `declare`/`declareNew`,
  `addAll`, `join`/`joining`, `ofIf`, `statementOf` and similar. The
  legacy generator was deleted in R182. Nothing in the reactor calls
  these any more; the trial swap compiled `graphitron`'s main and test
  sources without them.
- **Only rewrite a file when its content changed** (Sept 2025, #161),
  later extended by R410 into `JavaFile.writeToPathReporting` returning
  `WriteResult(path, changed)`. Production code: the `changed` flag
  feeds `GenerationResult.changedUnits`, which `DevMojo` hands to
  `IncrementalCompiler`. Neither Square nor Palantir has this; both
  overwrite unconditionally. It uses nothing private to JavaPoet.
- **`TypeSpec.referencedClassNames()`** (R410, R455), the structured
  walk behind `TypeSpecReferenceWalk`. Its only caller is
  `IncrementalCompileHarnessTest`. It needs `CodeBlock.args()`, which
  Palantir keeps package-private, so it cannot move out as it stands.
- **`TypeName.OBJECT`**: Palantir has this as `ClassName.OBJECT`.

## Plan

1. **Move the writer into graphitron.** A small graphitron-side writer
   renders the `JavaFile` to a string through the public API, compares
   it with the file on disk, writes only on a difference, and reports
   whether it wrote. `GraphQLRewriteGenerator` calls it where it calls
   `writeToPathReporting` today. Two tidy-ups come with the move: render
   once rather than twice on the write path, and compare content
   directly rather than through a SHA-256 of each side.
   `IdempotentWriterTest` in `graphitron-sakila-example` keeps pinning
   the behaviour.
2. **Derive the compile-graph oracle from javac, and retire
   `referencedClassNames`.** `TypeSpecReferenceWalk` exists to check
   that the projected `CompileDependencyGraph` is a superset of "javac's
   true cross-unit dependencies", in its own javadoc's words. It
   approximates javac from the pre-render `TypeSpec` tree, which needs a
   recursion into `$L` blocks, a regex scan for fully qualified names
   baked into strings, and a documented residue it still cannot see.
   The harness can ask javac instead: compile the generated sources as a
   `JavacTask`, and after analysis scan each compilation unit's tree for
   the top-level class of every resolved symbol, keeping the generated
   ones. That sees same-package names, string-baked code (it is real
   code by then) and inlined compile-time constants, which a class-file
   scan such as jdeps would miss. The oracle then lives in test sources,
   `TypeSpecReferenceWalk` and `referencedClassNames` are deleted, and
   the leg assertions in `IncrementalCompileHarnessTest` read the new
   oracle. This is the one step with real design work; if it grows, it
   splits into its own item that this one depends on.
3. **Adapt to Palantir's API differences.** `TypeName.OBJECT` becomes
   `ClassName.OBJECT` (about ten code sites). Palantir rejects a format
   string passed arguments it has no placeholder for, where Square
   ignored them silently; the trial swap hit this once, at
   `ConnectionRuntimeClassGenerator`'s multi-tenant constructor
   (`addStatement` with a placeholder-free template and a `source`
   argument). Fix that site, and treat any further hit the same way:
   it is a latent sloppiness in our emit code, not a Palantir problem.
4. **Switch the dependency.** Pin `com.palantir.javapoet:javapoet`
   (0.22.0 at time of writing) in the root `pom.xml` properties and
   dependency management, depend on it from `graphitron`, and rename
   `no.sikt.graphitron.javapoet` to `com.palantir.javapoet` in the 289
   importing files (mechanical).
5. **Delete the module.** Remove `graphitron-javapoet` from the root
   `pom.xml` modules list, and from the module enumerations in
   `CLAUDE.md` and `docs/architecture/reference/modules.adoc` (the
   `check-module-enumeration` step keeps those in step with the pom),
   the publishable-surface list in `docs/architecture/index.adoc`, the
   module lists in `GuardScope` and `JavadocReferenceGateTest`, and the
   build-time table in `.claude/skills/build/SKILL.md`. Repoint the
   prose that names the fork: the record-support javadocs on
   `InputRecordShape`, `HasInputRecordShape` and `InputRecordGenerator`,
   and the `CodeBlock.equals` citation in `MethodRefCallShapeTest`.
   R560 (un-skip the fork's test suite) has no subject once the module
   is gone and is Discarded in the same commit.

Steps 1 to 3 are independent of each other and each lands green
against the fork, so the switch in step 4 is the only commit that
changes the dependency.

## Out of scope

- Emitting records, sealed markers and `package-info.java` for R94's
  input types: the follow-on slice this item unblocks. Palantir has no
  `package-info.java` support, so that slice writes the file through
  the graphitron-side writer from step 1.
- Upstreaming anything to Palantir. Asking for a public
  `CodeBlock.args()` was the alternative to step 2 and is not needed
  once the oracle comes from javac.

## Retired vocabulary

- `graphitron-javapoet`, `no.sikt.graphitron.javapoet`,
  `LICENSE-JAVAPOET.txt`
- `TypeSpec.referencedClassNames`, `TypeSpecReferenceWalk`
- `JavaFile.writeToPathReporting`, `JavaFile.WriteResult`
- `TypeName.OBJECT`

## Tests

- The full verification build is green with the Palantir dependency,
  including the Java 17 compilation and execution tiers of
  `graphitron-sakila-example`.
- Regenerating `graphitron-sakila-example` from clean before and after
  the switch yields byte-identical sources.
- `IdempotentWriterTest` passes against the graphitron-side writer.
- `IncrementalCompileHarnessTest`'s three-leg oracle passes with the
  javac-derived oracle, and a deliberately dropped edge in the projected
  graph is reported as a gap.
- `mvn dependency:tree` on `graphitron-maven-plugin` shows
  `com.palantir.javapoet:javapoet` and no `graphitron-javapoet`.

## Other solutions we've considered

- **Keep the fork and port Palantir's record and sealed-type code into
  it.** This was this item's previous plan. It works (a trial build of
  the fork rebased onto Palantir's sources was green), but it keeps us
  maintaining an emitter whose only remaining additions are a writer
  that does not need to live there and a test oracle that should come
  from javac.
- **Rebase the fork onto Palantir's sources, keeping our package
  name.** No import churn, but the same maintenance burden as above,
  now tracking Palantir's releases by hand.
- **A different code-generation library** (Roaster, JavaParser's
  printer, JCodeModel). None shares JavaPoet's API, so every emitter
  would be rewritten.

## Provenance

The trial swap, run 2026-10-26 in a throwaway worktree: Palantir
0.22.0's sources, renamed into our package, replaced the fork's. With
`TypeName.OBJECT`, the writer and `referencedClassNames` ported over,
`graphitron` compiled; its generator tests ran 4,575 with 16 errors,
all the single format-argument site in step 3; with that fixed, the
full `mvn install -Plocal-db` was green across all modules (the fork's
own tests removed, since they exercise the dead helpers); and a clean
regeneration of `graphitron-sakila-example` with the fork and with
Palantir produced identical output for all 1,376 files.
