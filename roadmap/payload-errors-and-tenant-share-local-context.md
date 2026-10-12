---
id: R1016
title: "A tenant-routed mutation payload carries both its tenant and its errors"
status: Backlog
bucket: bug
priority: 6
theme: runtime-connection
depends-on: []
created: 2026-10-12
last-updated: 2026-10-12
---

# A tenant-routed mutation payload carries both its tenant and its errors

## Goal

In a database-per-tenant build (one with `<tenantColumn>` configured), a successful DML mutation whose payload carries an `errors` field returns its payload cleanly. Today every such mutation fails with `Can't resolve value (/endreStudieprogram/errors) : type mismatch error, expected type LIST`. The cause is that graphql-java's single `DataFetcherResult.localContext` slot is doing two jobs. On success, the tenant-routed fetcher ships `data(payload).localContext(_divinedTenant)` so the payload's children run on the tenant it divined, where `_divinedTenant` is a `String`. On failure, the error channel ships `localContext(List.of(t))`, and the payload's `errors` fetcher is `return env.getLocalContext();`. So on success `errors` reads the tenant key and graphql-java rejects it as not a list. When this lands, one carrier holds both the tenant and the error list, and each reader takes its own part; the carrier's shape is a Spec decision.

## Provenance

sis IT run against Oracle 18c on trunk `dd940c703` (`sis/GRAPHITRON10_STATUS.md`, section "Second IT run"), about 37 of 218 ITs. Schema shape:

```graphql
type Mutation {
  endreStudieprogram(input: [EndreStudieprogramInput!]!): EndreStudieprogramPayload
    @mutation(typeName: UPDATE, table: "studieprogram")
}
type EndreStudieprogramPayload {
  studieprogram: [Studieprogram]
  errors: [EndreStudieprogramError!]
}
union EndreStudieprogramError = UgyldigInput
```

The error path is consistent today only by accident: its data is an empty result, so the sibling `rowsStudieprogram`, which calls `divinedTenant(env.getLocalContext())`, never runs against the error list.

## Where it lives

Two writers and two readers share the slot:

- Writer, tenant: `TenantDslEmitter` (the `.localContext(_divinedTenant)` success return) and `TenantAcquisitionFragments:97`.
- Writer, errors: the `ErrorChannel.LocalContext` catch arm (`data(...).localContext(List.of(t))`, via `ErrorRouter.dispatchToLocalContext` at runtime).
- Reader, errors: `ChildField.ErrorsField` with `ChildField.Transport.LocalContext`.
- Reader, tenant: the generated `TenantConnections.divinedTenant(env.getLocalContext())` in child fetchers.

`GraphitronSchemaValidator.validateLocalContextErrorsFieldGuards` and R533 (single-sourcing the null-source guard) touch the same channel and should be read before the Spec.
