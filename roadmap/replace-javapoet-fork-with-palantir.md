---
id: R174
title: "Replace the graphitron-javapoet fork with Palantir's JavaPoet"
status: In Progress
bucket: architecture
priority: 9
theme: tooling
depends-on: []
created: 2026-05-17
last-updated: 2026-10-28
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
types (`addPermittedSubclass`). That unblocks the shape R94 wanted for
generated input types and had to defer: one Java record per SDL input
type, plus a sealed marker interface over them. Emitting that shape is
its own follow-on slice.

## Why the fork can go

**Why we forked, and why that no longer applies.** The module README
gives two reasons: Square's JavaPoet was archived, and keeping the
emitter an implementation detail spares us "strict API and ABI
compatibility". The first is answered by Palantir being maintained.
The second was an obligation running from a library to its users: as
long as `graphitron-javapoet` was published, its API was ours to keep
stable. As a consumer of Palantir we owe it nothing, generated code
never imports JavaPoet, so none of Palantir's API reaches graphitron's
consumers, and an exact version pin means upgrades happen on our
schedule.

**What the fork added over Square,** traced through git history:

- **Private fields behind accessors** (May 2025, at the copy). Palantir
  made the same change with the same accessor names, so it carries over
  for free.
- **Builder helpers for the legacy generator** (July 2025 to April
  2026, the `GG-` commits): the `*If` variants, `declare`/`declareNew`,
  `addAll`, `join`/`joining`, `ofIf`, `statementOf` and similar. The
  legacy generator was deleted in R182 and nothing in the reactor calls
  these any more.
- **Only rewrite a file when its content changed** (Sept 2025, #161),
  later extended by R410 into `JavaFile.writeToPathReporting` returning
  `WriteResult(path, changed)`. Production code: the `changed` flag
  feeds `GenerationResult.changedUnits`, which `DevMojo` hands to
  `IncrementalCompiler`. Neither Square nor Palantir has this; both
  overwrite unconditionally. It uses nothing private to JavaPoet, so it
  moves out (plan step 1).
- **`TypeSpec.referencedClassNames()`** (R410, R455), the structured
  walk behind `TypeSpecReferenceWalk`, whose only caller is
  `IncrementalCompileHarnessTest`. It needs `CodeBlock.args()`, which
  Palantir keeps package-private, so it cannot move out. **R1103**
  replaces the walk with a check that reads javac's resolved symbols
  and deletes both; this item depends on it and starts once it is Done.
- **`TypeName.OBJECT`**: Palantir has this as `ClassName.OBJECT`.

**What a trial swap showed** (2026-10-26, throwaway worktree, Palantir
0.22.0's sources renamed into our package in place of the fork's, with
`TypeName.OBJECT`, the writer and `referencedClassNames` ported over):

- `graphitron`'s main and test sources compiled.
- Its generator tests ran 4,575 with 16 errors, all from the single
  format-argument site in plan step 2.
- With that fixed, the full `mvn install -Plocal-db` was green across
  all modules (the fork's own tests removed, since they exercise the
  dead helpers).
- A clean regeneration of `graphitron-sakila-example` with the fork and
  with Palantir produced identical output for all 1,376 files.

## Plan

1. **Move the writer into graphitron, at the existing seam.**
   `GraphQLRewriteGenerator.writeUnits` is already the single write
   site for every planned and fixed-runtime unit (`writeCommand`
   delegates to it). It keeps the one render
   (`JavaFile.builder(...).build().toString()`) and hands the target
   path, computed from the unit's `UnitRef`, plus the rendered source to
   a small helper that compares with the file on disk, writes only on a
   difference, and returns `(path, changed)` as a graphitron-side
   record. `EmissionLog.record` takes that record, so it stops naming an
   emit-library type. This also renders once where the fork renders
   twice on the write path, and compares content directly rather than
   through a SHA-256 of each side. `IdempotentWriterTest` in
   `graphitron-sakila-example` keeps pinning the behaviour. Whether
   `SchemaSdlEmitter` (which writes with `Files.writeString`
   unconditionally) adopts the helper is the implementer's call, not
   scope.
2. **Adapt to Palantir's API differences.** `TypeName.OBJECT` becomes
   `ClassName.OBJECT` (34 occurrences across eight files, seven of them in main sources). Palantir rejects a format
   string passed arguments it has no placeholder for, where Square
   ignored them silently; the trial swap hit this once, at
   `ConnectionRuntimeClassGenerator`'s multi-tenant constructor
   (`addStatement` with a placeholder-free template and a `source`
   argument). Fix that site, and treat any further hit the same way: it
   is a latent sloppiness in our emit code, not a Palantir problem.
3. **Switch the dependency.** Pin `com.palantir.javapoet:javapoet`
   exactly (0.22.0 at time of writing) as a root `pom.xml` property,
   replace the `graphitron-javapoet` dependency in `graphitron/pom.xml`
   with it, and rename `no.sikt.graphitron.javapoet` to
   `com.palantir.javapoet` in the 289 files that name it (mechanical; 270 import it, the rest use the FQN inline or as a string, as `ModelEmitVocabularyGuardTest` and `PackageImportDirectionTest` do).
4. **Delete the module and its mentions.**
   - Build: the `graphitron-javapoet` entry in the root `pom.xml`
     modules list, and the module named in the comment near the
     leaf-coverage configuration.
   - Module enumerations: `CLAUDE.md` and
     `docs/architecture/reference/modules.adoc` (the
     `check-module-enumeration` step keeps those in step with the pom),
     the publishable-surface list in `docs/architecture/index.adoc`, the
     module lists in `GuardScope` and `JavadocReferenceGateTest`, and
     the build-time table in `.claude/skills/build/SKILL.md`.
   - Agent and command docs that cite fork paths:
     `.claude/agents/codebase-analyzer.md`,
     `.claude/agents/codebase-pattern-finder.md`,
     `.claude/commands/prepare_for_pr.md`.
   - Javadoc prose naming the fork: the record-support notes on
     `InputRecordShape`, `HasInputRecordShape` and
     `InputRecordGenerator`, and the `CodeBlock.equals` citation in
     `MethodRefCallShapeTest`.
   - Roadmap bodies: `inputs-package-internal-use-audit.md` calls
     records "blocked on a graphitron-javapoet upgrade", which this item
     removes; `run-record-families-for-commands-and-emitted-units.md`
     and `model-owns-every-gatherer.md` name
     `JavaFile.writeToPathReporting`.
   - R560 (un-skip the fork's test suite) has no subject once the module
     is gone and is Discarded in the same commit.

Steps 1 and 2 are independent of each other and each lands green
against the fork, so the switch in step 3 is the only commit that
changes the dependency.

**Version bumps afterwards.** A Palantir release that changes rendering
would mark every unit changed, force a full recompile in the dev loop
and churn consumers' generated diffs. The exact pin means such a change
can only enter through a pom diff, and a version bump is a generator
change like any other: its verification includes regenerating
`graphitron-sakila-example` from clean and diffing against the
previous version's output, with any difference reviewed as an emit
change. No automated guard compares output across library versions;
this is a review rule, stated here so the item that bumps the version
inherits it.

## Out of scope

- Emitting records, sealed markers and `package-info.java` for R94's
  input types: the follow-on slice this item unblocks. Palantir has no
  `package-info.java` support, so that slice writes the file through
  the helper from step 1.
- Upstreaming anything to Palantir. Asking for a public
  `CodeBlock.args()` was the alternative to R1103 and is not needed
  once the oracle comes from javac.

## Retired vocabulary

- `graphitron-javapoet`, `no.sikt.graphitron.javapoet`,
  `LICENSE-JAVAPOET.txt`
- `TypeSpec.referencedClassNames`, `TypeSpecReferenceWalk` (retired
  by R1103; the sweep here confirms nothing reintroduced them)
- `JavaFile.writeToPathReporting`, `JavaFile.WriteResult`
- `TypeName.OBJECT`

## Tests

- The full verification build is green with the Palantir dependency,
  including the Java 17 compilation and execution tiers of
  `graphitron-sakila-example`.
- Regenerating `graphitron-sakila-example` from clean before and after
  the switch yields byte-identical sources.
- `IdempotentWriterTest` passes against the graphitron-side helper.
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
