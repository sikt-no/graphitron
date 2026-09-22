---
id: R966
title: "Write inputs keyed by a decoded node id divine the tenant"
status: Backlog
bucket: bug
priority: 3
theme: classification-model
depends-on: []
created: 2026-09-22
last-updated: 2026-09-22
---

# Write inputs keyed by a decoded node id divine the tenant

## Goal

A mutation that identifies its rows by a node id classifies as tenant-bound when the tenant column sits inside that id. Today it is rejected at build time with "no argument or input field maps to tenant column 'X'", although the tenant value is right there in the key the mutation already decodes, so an UPDATE, DELETE or `@nodeId`-keyed INSERT against a tenant-scoped table does not generate at all. *Tenant binding* is how a generated resolver knows which tenant database to route a write to; a *tenant-scoped* table is one carrying the column named by the Mojo's `<tenantColumn>`; a *node id* is graphitron's opaque global identifier, which encodes the row's key columns and is decoded back into them at the call site. When a tenant-scoped table's key includes the tenant column, decoding the id yields the tenant, and the write should route on it.

Three real sis shapes (2026-09-22 spike, `<tenantColumn>INSTITUSJONSNR_EIER</tenantColumn>`):

```graphql
type Mutation {
  # DELETE of PERMISJON, keyed by node id
  slettPermisjoner(input: [SlettPermisjonerInput!]!): SlettPermisjonerPayload

  # UPDATE of PERSON, keyed by node id
  angiBankkontonummerForPersonProfil(input: AngiBankkontonummerInput!): ...

  # INSERT of KULL, with a @nodeId reference field
  opprettKull(input: OpprettKullInput!): ...
}

input SlettPermisjonerInput { id: ID! }
input OpprettKullInput { studieprogramId: ID! @nodeId(typeName: "Studieprogram") ... }
```

The rejection received for the first: "'Mutation.slettPermisjoner' reaches tenant-scoped table 'PERMISJON' with no tenant binding in scope: no argument or input field maps to tenant column 'INSTITUSJONSNR_EIER', and no ancestor established a tenant context."

## Mechanism (verified against the tree, 2026-09-22)

Three sites, each a separate half of the same omission:

- `TenantBindingIndex.Fold.directSlots` switches over the operation's members and mints slots for `OperationMember.Write.Insert` and `Write.Upsert` only; `Write.Update(inputArg, UpdateRows)` and `Write.Delete(inputArg, DeleteRows)` fall to `default -> List.of()`. Both carriers already hold `KeyColumn` rows naming the SDL field, the target column, a `CallSiteExtraction.NodeIdDecodeKeys` extraction and the decode slot, so the tenant column's position inside the id is already stated where the fold could read it.
- `slotsFromTableInput` (the INSERT/UPSERT arm) reads `InputColumnBindingGroup.MapGroup` and skips `InputColumnBindingGroup.DecodedRecordGroup`, which is the `@nodeId`-on-an-input-field case.
- `collectFromInputFields` matches `InputField.ColumnBackedField cf when !cf.isComposite()` and drops the rest, its `default` arm reasoning that composite node-id tuples "belong to the per-row family and the deliberate fan-out arm". No per-row arm exists for a write input: `TenantBinding.NodeIdBound` is assigned only where the members carry `OperationMember.Kind.NODE_RESOLVE`, which a mutation does not.

## Direction (to be developed at Spec)

Add a `TenantBinding.SlotRead` variant for a decoded node id, carrying the outer argument name, the access path to the id field, the decode method and the decode slot. Mint it from the `KeyColumn` rows of `Write.Update` / `Write.Delete` and from `DecodedRecordGroup` bindings whose target column matches the tenant column. `TenantDslEmitter.slotReads` renders it as a walk of the input list that decodes each id and takes the slot, and hands the resulting collection to the existing `TenantConnections.divinedTenant`, which already flattens a collection and checks the values agree.

That gives agreement-guarded routing: one tenant per mutation call, and a batch mixing tenants rejected before any SQL runs. The precedent is the hand-written `QueryInspector.getUniqueId` in sis v9, which threw on a mixed-tenant batch. Per-row partitioning of a mixed batch is a possible follow-up; sis does not need it, and the Spec should say so rather than leave the arm open.

## Tests

`graphitron-sakila-example/src/main/resources/graphql/multitenant.graphqls` has no UPDATE or DELETE taking a node-id input, which is why this was never caught; add one, plus an `@nodeId` reference field on an INSERT input. The agreement guard needs an execution-tier case for the mixed-batch rejection, since the pre-SQL failure is the observable.

## Provenance

A sis spike on 2026-09-22 configured `<tenantColumn>INSTITUSJONSNR_EIER</tenantColumn>` against the sis schema and counted 875 `Rejection.AuthorError.NoTenantBinding` errors, against 0 on the same tree and database without the setting. 121 are root fields; the other 750 are children cascaded by the every-path fold in `TenantBindingIndex.tenantContextOf`. 105 of the 121 roots are this item: 59 UPDATE, 26 DELETE and 20 INSERT mutations.

11 more are R965, the sibling gap where a field-level `@condition(override: true)` hides a column-bound argument from the fold. The two are independent. R965 is the smaller change and unblocks the wider cascade, so it is worth taking first.

Every one of the 875 rejections printed without file:line coordinates, because the fold's rejections carry `SourceLocation.EMPTY`. That is R523; sis is its motivating multi-file case.
