---
id: R1041
title: "A mutation called with an empty input list returns an empty payload without routing to a tenant"
status: Backlog
bucket: bug
priority: 3
theme: runtime-connection
depends-on: []
created: 2026-10-15
last-updated: 2026-10-15
---

# A mutation called with an empty input list returns an empty payload without routing to a tenant

## Goal

In a database-per-tenant build, a mutation whose tenant is read from the elements of its input list, called with an empty list (`input: []`), returns its payload with an empty success list and null `errors`, and opens no tenant connection. With nothing to route there is nothing to write. Today routing runs first: `TenantConnections.tenantSlot(env.getArgument("input"), ...)` yields no candidates, `TenantConnections.divinedTenant` throws `NoSuchElementException("The tenant binding value is absent; cannot route the operation to a tenant database.")`, and since no payload error type maps it, the client gets the redacted "An error occurred. Reference: <uuid>" (the R1036 shape). On the DML arm the generator already emits an `in.isEmpty()` guard that returns an empty `Result`, but it sits after routing and uses the routed `DSLContext` only for `dsl.newResult(...)`, so it is dead code for exactly the case it exists for. The `@service` arm has no empty guard at all. Graphitron 9 returned an empty payload here, and sis's integration tests assert it.

## Evidence

Reported from the sis port on 2026-10-15 against trunk `e4cfef7c1`. Three sis integration tests fail:

- DML UPDATE: `endreStudieprogramOpptakskrav(input: [EndreStudieprogramOpptakskravInput!]!)` with `@mutation(typeName: UPDATE, table: "studieprogram")`, tenant from the `id: ID!` Studieprogram node id. IT `EndreStudieprogramOpptakskravMutationIT.endreStudieprogramOpptakskrav_tomInputliste_returnererIngenStudieprogram`.
- `@service`: `angiStatsborgerskap(input: [AngiStatsborgerskapInput!]!)`, tenant from each element's `personProfilId` `@nodeId`. IT `StatsborgerskapMutationIT.angiStatsborgerskapMedTomInputlisteReturnererIngenRader`.
- `@service`: `registrerVeiledningsforhold(input: [RegistrerVeiledningsforholdInput!]!)`, tenant from the elements' `@nodeId` fields. IT `VeiledningsforholdMutationIT.registrerVeiledningsforhold_medTomListe_girTomPayloadUtenFeil`.

Scope is the empty list only. A non-empty list whose elements yield no tenant is already a build error under R1019, and a null list cannot occur on these `[X!]!` arguments.

## Questions for the Spec

- **The `@service` arm: skip the call, or call it on a default tenant.** Graphitron 9 called the service with an empty list, and sis's services return an empty list for it, but sis does not need the call. Skipping is the leaning: it needs no tenant, and calling a service with a `DSLContext` for a tenant the request never named would invent a routing decision. The cost is that a service with side effects that do not depend on the list (an audit row, say) no longer runs, and the Spec should say so in the user manual.
- **Which shapes share it.** The census covers every arm where the tenant is read only from list elements: the DML verbs (INSERT, UPDATE, UPSERT, DELETE, single-row and bulk), `@service`, and any read-side field with the same slot shape. It also has to decide what happens when the list is empty but another argument also binds the tenant, where routing could still succeed.
- **Building the empty result without a `DSLContext`.** The DML arm's empty `Result` needs a `DSLContext` only for `newResult`. Either build it with `DSL.using(dialect)` or skip the re-read and return the empty success directly, whichever keeps the payload shape unchanged.
