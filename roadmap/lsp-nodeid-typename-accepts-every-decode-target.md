---
id: R1034
title: "The LSP accepts every @nodeId(typeName:) target the build decodes"
status: Backlog
bucket: bug
priority: 4
theme: lsp
depends-on: []
created: 2026-10-14
last-updated: 2026-10-14
---

# The LSP accepts every @nodeId(typeName:) target the build decodes

## Goal

The LSP stops flagging a `@nodeId(typeName: T)` that the build accepts. Today `Diagnostics.judgeNodeTypeName` in `graphitron-lsp` reports "Unknown @node type 'T' on @nodeId(typeName:). The type must be declared in the schema and carry the @node directive." whenever `T` is missing from `graphitron_node_entry`, the authored-`@node` family (`DiagnosticFacts.nodeTypeBindingArm`). The build accepts three populations that family misses: node types inferred from `implements Node` plus catalog metadata, `@table` types with catalog metadata decoded by a `@service` record parameter, and, after R1032, the same targets on DML and filter inputs. An author working in the editor sees a red diagnostic on a schema that builds. `NodeTypeCompletions` has the same gap and does not offer these types.

## Scope notes for the Spec

- The accepted set is the build's: `graphitron_node_type` (authored and inferred node types), plus `@table` types whose table carries catalog node metadata, plus the polymorphic containers the arm already reads. If R1033 lands first, read its instruction-target vocabulary rather than restating the predicate.
- The diagnostic's text should stop saying the type must carry `@node`.
- Found while specifying R1032.
