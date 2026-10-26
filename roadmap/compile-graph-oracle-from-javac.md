---
id: R1103
title: "Derive the compile-graph oracle from javac and retire referencedClassNames"
status: Backlog
bucket: architecture
priority: 8
theme: testing
depends-on: []
created: 2026-10-26
last-updated: 2026-10-26
---

# Derive the compile-graph oracle from javac and retire referencedClassNames

## Goal

The incremental compiler's dependency graph is checked against the real dependencies javac resolves when it compiles the generated sources, instead of against a reconstruction from the JavaPoet `TypeSpec` tree. The check moves into test sources, and `TypeSpec.referencedClassNames()` and `TypeSpecReferenceWalk` are deleted. Nothing changes for consumers; for graphitron, the check stops having blind spots, and the JavaPoet fork loses the one addition that blocks replacing it with Palantir's JavaPoet (R174).

## Context

`IncrementalCompiler` recompiles only the generated files a change affects, using the dependency graph `PlanCompileGraph` projects from the plan (`CompileDependencyGraph`). If that graph misses a real dependency, a file that should be recompiled is not: silently wrong output in the dev loop. `IncrementalCompileHarnessTest.threeLegOracle_projectedGraphMatchesTheEmitArtifact` guards against that by comparing the graph with an independent account of what each generated unit references.

That independent account is `TypeSpecReferenceWalk`, whose javadoc states the target: the projected graph must be a superset of "javac's true cross-unit dependencies". Rather than ask javac, it approximates javac from the pre-render `TypeSpec` tree:

- `TypeSpec.referencedClassNames()`, added to the `graphitron-javapoet` fork, walks every `$T` reference, recursing into `$L` code blocks, anonymous classes, annotations and type-variable bounds. It needs `CodeBlock.args()`, which Palantir's JavaPoet keeps package-private.
- A regex scan of the rendered source catches fully qualified generated names baked into strings.
- A documented residue remains: a same-package simple name baked into a code-bearing string is caught by neither.

Its only caller is the harness test, yet the walk lives in `graphitron/src/main` and in the code emitter every generated file passes through.

## Direction

After javac has analysed the generated sources, every identifier is resolved to a symbol. Collecting the top-level class of each resolved symbol in a compilation unit, and keeping the generated ones, gives the true reference set: same-package names, string-baked code (it is real code by then), and inlined compile-time constants. A class-file scan such as jdeps would miss the last of these, so the check reads the analysed trees, not the class files.

Open questions for the Spec:

- How the harness obtains a `JavacTask` over the generated sources: reuse `IncrementalCompileEngine`'s compiler, reuse `EmittedCodeHarness`, or a dedicated task in the test.
- How a resolved symbol maps back to a generated unit's FQCN, including nested types.
- Whether the "bounded gap" leg (projected edges the walk cannot see must sit inside `PlanCompileGraph`'s declared superset) still needs a superset declaration once the oracle is javac itself.
- A negative test: deliberately dropping an edge from the projected graph must be reported as a gap.
