---
id: R273
title: "Align the bare scalar-ID argument arm with the R473 grammar; land or retire R265's deferred compile-tier guard"
status: Backlog
bucket: architecture
priority: 5
theme: nodeid
depends-on: [explicit-nodeid-grammar]
created: 2026-06-02
last-updated: 2026-07-14
---

# Align the bare scalar-ID argument arm with the R473 grammar; land or retire R265's deferred compile-tier guard

Re-scoped 2026-07-14 (file renamed from `nodeid-skip-mismatch-error-surfacing.md`; the original Spec body is in git history). The item was written 2026-06-02 as a merged policy decision (skip vs throw on NodeId mismatch) plus a five-site metadata-sourcing refactor. Both halves have since been settled or claimed elsewhere:

- **The policy half is settled by R378 (Done).** Authored `@nodeId` filters throw on a malformed or wrong-type id, with the two failure modes distinguished in the message and the error surfaced through `GraphitronClientException` / `ErrorRouter.surfaceClientErrorOrRedact`. `SkipMismatchedElement` survives only on the legacy `__NODE_*` synthesis-shim arms, which the shim-retirement track owns. Nothing here to decide.
- **The "infer `@node` from `implements Node` + `__NODE_*` metadata" deliverable is contradicted, not pending.** R473 (`explicit-nodeid-grammar`) makes the explicit `implements Node @node` pair the source of node identity, and R34 (`nodeid-migration-quickfix`) deliberately replaces silent promotion with an LSP hint offering to add the declaration. R27 (`retire-synthesis-shims`) records that metadata-based auto-promotion was removed on purpose. Re-introducing inference here would run against all three; the deliverable is dropped.
- **The `__NODE_*` purge deliverables are subsumed.** R473 makes decode resolution typeName-first via `NodeIndex.byName` and deletes `resolveDecodeHelperForTable` together with the shims; R27 is the deletion vehicle for the shim reads in `BuildContext` (the FK-qualifier id-reference gate and the input-scalar arm). Those sites are no longer this item's to refactor.

## What survives: the bare scalar-ID argument arm

One classification site remains unclaimed by R473/R27/R34: the bare-`ID` argument arm in `FieldBuilder` (the block its own comment calls "the legacy implicit scalar-ID arm"). A bare `id:` argument with no `@nodeId` and no `@lookupKey` that resolves to the table PK is treated as an authored NodeId key: the arm reads `ctx.catalog.nodeIdMetadata(...)` *directly* (bypassing `resolveTargetKeys` and its modern `@node` + `catalog.findPkColumns` tier) and calls `resolveDecodeHelperForTable` to build a `ThrowOnMismatch` decode.

Under R473's grammar this arm is nonconforming twice over: rule-wise, a directive-less `ID` is a plain column-mapped scalar (only `Node.id` itself carries implicit nodeId semantics), so the arm's implicit decode should not exist; mechanically, both things it leans on (`nodeIdMetadata` as a direct source, `resolveDecodeHelperForTable`) are scheduled for deletion. The work:

1. **Settle the arm's fate under the R473 grammar at Spec time.** The expected outcome is that the implicit-decode behaviour is retired: a bare `id: ID!` argument binds as a plain column-mapped scalar, and a consumer who wants global-id semantics writes `@nodeId` explicitly (R34's quick fixes make that migration cheap). If a live consumer schema depends on the implicit decode, that surfaces through R27's migration gate, not by keeping this arm.
2. **Land or retire R265's deferred compile-tier guard.** R265 fixed the non-compiling `GraphqlErrorException(String)` construction in the `ThrowOnMismatch` helpers and deferred its compilation-tier regression guard to this item, because the scalar throw arm was reachable only via the legacy `__NODE_*` path. If the arm is retired (expected), the deferral resolves by deletion and the registry's remaining throw arms are already covered by R378's execution-tier tests; if the arm survives in some explicit form, the guard is a `graphitron-sakila-example` fixture compiling the generated decode helper against the real graphql-java API.
3. **Sequence behind R473.** This item is a consumer of R473's grammar decision and shares its deletion targets; it should not start before R473 leaves Backlog, and may collapse into R473's implementation if the reviewer prefers one motion.

## Out of scope

- The encode side and wire format of NodeIds (typeId-prefixed base64): unchanged.
- The shim arms' `SkipMismatchedElement` behaviour and their deletion: R27/R473's track.
- Any change to the R378 throw policy or its error-surfacing shape.
