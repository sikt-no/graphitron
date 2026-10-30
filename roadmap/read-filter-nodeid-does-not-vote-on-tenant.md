---
id: R1130
title: "A node id in a query filter does not vote on the tenant when another slot already binds it"
status: Backlog
bucket: feature
priority: 4
theme: runtime-connection
depends-on: []
created: 2026-10-30
last-updated: 2026-10-30
---

# A node id in a query filter does not vote on the tenant when another slot already binds it

## Goal

Take a query field whose tenant is already fixed by a slot the client cannot leave out, such as a non-null tenant argument or input field. A node id in that field's filter only filters; it no longer takes part in choosing the tenant. An id naming another tenant then matches nothing, and the field answers with the bound tenant's rows instead of failing.

Mutations are unchanged: a write whose tenant values disagree is still refused, since one statement across two tenants has no correct execution. A query whose filter ids are its only tenant source is also unchanged: disagreement among them is still an error, because there is no single database to pick.

Case, from sis (`LarestedRutingIT.idListeMedIderFraToLarestederAvvisesForFeltet`, difftest X3):

```graphql
studieprogramV2(filter: { eierOrganisasjonskode: "1234",
                          studieprogrammer: [<id at 1234>, <id at 186>] }) { totalCount }
```

`StudieprogramV2FilterInput` has `eierOrganisasjonskode: String! @field(name: "INSTITUSJONSNR_EIER")` and `studieprogrammer: [ID!] @nodeId(typeName: "Studieprogram")`. Today the field is `null` with "Tenant bindings disagree within one operation: '1234' vs '186'". Graphitron 9 treated the ids as a `WHERE` on the key columns inside 1234's request and returned the 1234 row with no error.

## Why the rule is sound

* **Routing is not ambiguous.** The guaranteed slot names the one tenant database. A foreign id's key carries the other tenant's value in the tenant column, so it cannot match a row in that database. Nothing from the other tenant is read or disclosed.
* **Reads are already lenient elsewhere.** `node(id:)` and `_entities` answer `null` without an error for an id at another tenant, and an id that exists nowhere in the same filter list just matches nothing. Only a foreign id inside a read filter fails the whole field.
* **The access check is unchanged.** The bound tenant is checked against the request's permitted set as today. The foreign id's tenant is never checked, since nothing is read from it.

## What exists today

`TenantBindingIndex` classifies the field `TenantBinding.ArgumentBound`, whose `BoundSlot`s are folded at runtime by the generated `TenantConnections.namedTenant` / `divinedTenant`. Each slot's value passes through `agreeOnTenant` (`ConnectionRuntimeClassGenerator.divinedTenantAgree`), which throws the generated `GraphitronClientException` on the first mismatch. A filter id's slot carries `SlotProjection.DecodedKeySlot`. A non-null argument or input field that crosses no list carries `SlotPresence.Guaranteed`, and `ArgumentBound` already insists that `whenNoTenant` is `Unreachable` exactly when such a slot exists. So the model can already tell "another slot guarantees the tenant" from "a filter-id slot" at classification time. It looks like a pruning decision in the fold that builds the verdict, plus nothing at runtime.

## Things to settle in Spec

* **The rule.** For a field on the query side (connections, lookups, `@splitQuery` children of the same shape), when some slot is `Guaranteed`, a slot whose projection is `DecodedKeySlot` and which is not itself `Guaranteed` leaves the fold. Decide whether non-`Guaranteed` `Raw` slots (an optional tenant field in the same filter) leave too, or keep voting. Two guaranteed slots that disagree still refuse.
* **The predicate actually excludes the foreign id.** Confirm that every filter shape that decodes an id compares the whole key tuple, tenant column included. Then a foreign id matches nothing rather than matching a row with the same local key in the bound tenant's database.
* **A parent row as the guarantee.** sis lists it as a guarantee too. `ParentRowBound` and `Inherited` children are separate arms. Check whether a filter id on such a child folds today, and whether the same pruning applies there.
* **Query versus mutation.** The verdict needs the operation side. Name where it comes from, and pin that a mutation keeps refusing.

## Tests

The sis case in the sakila example: a query with a guaranteed tenant argument and a filter list holding one id at that tenant and one at another returns the first row and no error. A mutation with the same disagreement still refuses. A query whose only tenant source is the id list still refuses when the ids disagree. An access-denied bound tenant still fails as today, with a foreign id beside it.

## Provenance

Requested 2026-10-30 by sis (the Graphitron 10 port, request C40). When this lands, sis's `LarestedRutingIT` test flips to expecting the 1234 row; no sis code change.
