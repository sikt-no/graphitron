---
id: R1161
title: "The tree-sitter runtime moves to 0.26.13 and jtreesitter to 0.26.1"
status: Backlog
bucket: tech-debt
priority: 3
theme: lsp
depends-on: []
created: 2026-11-07
last-updated: 2026-11-07
---

# The tree-sitter runtime moves to 0.26.13 and jtreesitter to 0.26.1

## Goal

The LSP runs on the current patch release of its 0.26 line: `graphitron-tree-sitter-natives` is rebuilt from tree-sitter tag `v0.26.13` as `0.26.13-1`, and `jtreesitter` moves from 0.26.0 to 0.26.1, so the native code every LSP-touching JVM loads carries the upstream fixes since `v0.26.9`. The one that motivates this is in 0.26.10, "address strict aliasing violations in `TreeCursor`" (tree-sitter#5693): undefined behaviour that the `-O3` the runtime is built with may compile into wrong memory accesses, which in a JVM shows up as heap corruption rather than a Java exception.

## Evidence

On 2026-11-07 a `graphitron-maven-plugin` test fork died with SIGSEGV in G1 concurrent marking (`OopOopIterateBoundedDispatch<G1CMOopClosure>::oop_oop_iterate_bounded<ObjArrayKlass, narrowOop>`, `SEGV_ACCERR` at `0x9000011`), the collector tripping on a corrupted reference in an object array; no test failed, and a rerun of the module passed. The fork ran with `--enable-native-access=ALL-UNNAMED` and held `R157PipelineTest` beside `DevMojoTest`, `BuildBackedDiagnosticsTest` and `DiagnosticsAggregateTest`. H2 was alive in the fork, but it is pure Java and its 2.5.252 changelog carries no crash fix; native code reached through jtreesitter's FFM binding is the likelier writer. The retarget is the plausible fix, not a confirmed one.

## For the plan

* Stay on 0.26: `jtreesitter` has no 0.27 release, and the runtime's 0.27 line is a separate step.
* The natives are released by the human-driven procedure in `graphitron-tree-sitter-natives/UPSTREAM.md`: the version, the `tree-sitter-cli-version` workflow input at `v0.26.13`, and the release-gate assertions; then `graphitron-lsp`'s dependency moves to `0.26.13-1`.
* Note the next such crash, with the classes its fork held, beside this item, so a recurrence after the retarget points elsewhere.
