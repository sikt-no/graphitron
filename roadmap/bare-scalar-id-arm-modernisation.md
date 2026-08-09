---
id: R273
title: "Land or retire R265's deferred compile-tier guard"
status: Backlog
bucket: architecture
priority: 5
theme: nodeid
depends-on: [explicit-nodeid-grammar]
created: 2026-06-02
last-updated: 2026-07-14
---

# Land or retire R265's deferred compile-tier guard

Re-scoped 2026-07-14 (file renamed from `nodeid-skip-mismatch-error-surfacing.md`; the original Spec body is in git history). The item was written 2026-06-02 as a merged policy decision (skip vs throw on NodeId mismatch) plus a five-site metadata-sourcing refactor. Both halves have since been settled or claimed elsewhere:

- **The policy half is settled by R378 (Done).** Authored `@nodeId` filters throw on a malformed or wrong-type id, with the two failure modes distinguished in the message and the error surfaced through `GraphitronClientException` / `ErrorRouter.surfaceClientErrorOrRedact`. `SkipMismatchedElement` survives only on the legacy `__NODE_*` synthesis-shim arms, which the shim-retirement track owns. Nothing here to decide.
- **The "infer `@node` from `implements Node` + `__NODE_*` metadata" deliverable has shipped, and this bullet's earlier reading of it was wrong.** It was recorded as contradicted-not-pending on the grounds that R473 makes the explicit `implements Node @node` pair the source of node identity, that R34 replaces silent promotion with an LSP hint, and that R27 records metadata-based auto-promotion as deliberately removed. That conflated two axes. R473 closes off a *field* acquiring node semantics from table facts, and inference does not reopen it: inputs, arguments and cross-type references still require `@nodeId(typeName:)`. What shipped is a *type* that has already published nodehood in SDL (`implements Node`) getting its two identity parameters filled in from the catalog that owns them. The declaration of nodehood stays in SDL, where R473 wants it. The shim that caused the incident promoted on metadata alone, with no SDL-side opt-in at all, which is the part that mattered. This item's own surviving scope, the bare-`ID` argument arm below, is unaffected either way.
- **The `__NODE_*` purge deliverables are subsumed.** R473 made decode resolution typeName-first via `NodeIndex.forName` and deleted `resolveDecodeHelperForTable` together with all three shims, including the two `BuildContext` reads (the FK-qualifier id-reference gate and the input-scalar arm). Those sites no longer exist.

## The argument arm moved to R473

**Settled at R473's pass-3 Spec gate, 2026-08-06: the arm is R473's, and this item no longer owns it.** It offered the collapse ("may collapse into R473's implementation if the reviewer prefers one motion") and R473 took it, because R473's own thesis requires it: while a directive-less `ID` can mean "node identity, resolved from the table", `resolveDecodeHelperForTable` cannot be deleted, and this arm was its last caller once the three synthesis shims go.

R473 does not simply retire the arm, which is where the expected outcome recorded here turned out to be wrong. Its new rule 6 replaces the arm with a narrower, SDL-derived one: an argument named for the return type's `Node.id` field, on a field returning a node type, is that nodeId implicitly, resolved through `NodeIndex.forName` rather than `catalog.nodeIdMetadata`. A name mismatch requires `@nodeId(typeName: T)`. So a bare `id:` argument does *not* universally become a plain column-mapped scalar; that happens only where the name does not match or the return type is not a node type. See R473's rule 6 and its "Flip and delete" section for the replacement's shape and coverage.

## What survives: R265's deferred compile-tier guard

**Land or retire R265's deferred compile-tier guard.** R265 fixed the non-compiling `GraphqlErrorException(String)` construction in the `ThrowOnMismatch` helpers and deferred its compilation-tier regression guard to this item, because the scalar throw arm was reachable only via the legacy `__NODE_*` path. R473's rule 6 keeps a `ThrowOnMismatch` argument arm alive in explicit form rather than deleting the shape outright, so the deferral does *not* resolve by deletion the way this item once expected: the guard is a `graphitron-sakila-example` fixture compiling the generated decode helper against the real graphql-java API. Re-check that against R473's landed implementation before starting, since the reachability argument is what the deferral rested on.

**Sequence behind R473.** This item is a consumer of R473's grammar decision; it should not start before R473 lands.

## Out of scope

- The encode side and wire format of NodeIds (typeId-prefixed base64): unchanged.
- The shim arms' `SkipMismatchedElement` behaviour and their deletion: shipped in R473.
- Any change to the R378 throw policy or its error-surfacing shape.
