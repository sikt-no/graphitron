---
id: R174
title: "Replace the graphitron-javapoet fork with Palantir's JavaPoet"
status: In Review
bucket: architecture
priority: 9
theme: tooling
depends-on: []
created: 2026-05-17
last-updated: 2026-10-29
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

1. **Move the writer into graphitron, at the existing seam.** Shipped at
   `913de99`, inside another item's commit rather than this one: the
   helper is `IdempotentWriter` (compare rendered content with the file on
   disk, write only on a difference, return the path and `changed`), called
   from `GraphQLRewriteGenerator.writeUnits`, and `EmissionLog.record` takes
   its result. `IdempotentWriterTest` in `graphitron-sakila-example` pins
   the behaviour. `SchemaSdlEmitter` keeps its unconditional write; adopting
   the helper was left out of scope.
2. **Adapt to Palantir's API differences.** Shipped at `65a70ec`:
   `TypeName.OBJECT` became `ClassName.OBJECT` in eight files while the fork
   still had both. The placeholder-free format string the trial swap hit did
   not surface in the delivered swap: the generator tests ran clean against
   Palantir 0.22.0, so no emit-code fix was needed.
3. **Switch the dependency.** Shipped at `b059dc3`: root `pom.xml` pins
   `com.palantir.javapoet:javapoet` 0.22.0 as a property, `graphitron/pom.xml`
   depends on it, and `no.sikt.graphitron.javapoet` is renamed to
   `com.palantir.javapoet`. Files that arrived from trunk during the rebases
   needed the same rename, at `ea2d616` and `b09ee14`.
4. **Delete the module and its mentions.** Shipped at `b059dc3`: the module,
   its `pom.xml` entry, the module enumerations (`CLAUDE.md`,
   `modules.adoc`, `index.adoc`, `GuardScope`, `JavadocReferenceGateTest`,
   the `build` skill), the agent and command docs, and the fork-naming
   Javadoc prose and roadmap bodies. The fork's test-suite item was
   discarded in the same commit. Two historical roadmap mentions of the old
   module or package, as measurement and incident examples, stay as they
   were.

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
  the switch yields byte-identical sources. Measured against the fork
  (`65a70ec`) and the delivered tree (`b09ee14`), both on the same trunk
  base and database seed: 1,500 generated `.java` files on each side, and
  all but two or three identical. The remainder differ only in the order of
  the `fields.add(...)` lines inside a multi-term projection arm
  (`Category`, `Store`, `DivergedRefChild`). That is not the switch: two
  regenerations of the Palantir tree differ from each other in a different
  set of files, because the order of the correlation-key columns that feed
  `Contribution.Project` is not stable from run to run, and both trees run
  the same generator code. So the evidence is the same-base diff plus that
  self-diff: the switch changes no rendering, and the run-to-run ordering is
  an existing defect, filed as R1127.
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

## Reviewer findings

Review pass 1 (session_01WkW2JGp7UJ9sNv2NUT5Y3i): the implementation is correct and is the approved change. Not Done yet, for one reason.

- **Spec body does not reflect what shipped (approval precondition; bears on question 2).** The delivery commits changed only the front-matter of this file. Plan steps 1-4 still read as future work. Collapse each to a one-line "shipped at <sha>" note (step 1 and 2 at `65a70ec` and the writer commit, steps 3-4 at `b059dc3`, with the follow-up renames `ea2d616` and `b09ee14`), and state the completeness evidence per Tests bullet: byte-identical regeneration of `graphitron-sakila-example` is currently evidenced only by the pre-implementation trial swap, so either record a before/after diff result against the fork (a build at `65a70ec` versus the delivered tree), or say plainly that the trial swap is the evidence.

Verified at this gate: `mvn install -Plocal-db` green (BUILD SUCCESS, including `IdempotentWriterTest` 6/6 and the Java 17 example tiers); `graphitron-maven-plugin` dependency tree shows `com.palantir.javapoet:javapoet:0.22.0` and no `graphitron-javapoet`; no tracked `graphitron-javapoet` files; no `TypeName.OBJECT`, `writeToPathReporting`, `TypeSpecReferenceWalk` or `LICENSE-JAVAPOET` left in code; the writer lives in `IdempotentWriter` at the `writeUnits` seam; R560 is discarded. Non-blocking: `roadmap/javadoc-gate-incremental-skip.md` and `roadmap/capture-api-residue-after-nodehood-move.md` still name the old package or module as historical examples.
