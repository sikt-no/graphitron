---
id: R517
title: "Client narrowing of the fan-out domain: a tenant-column list argument intersects the request set"
status: Backlog
bucket: architecture
priority: 7
theme: runtime-connection
depends-on: [multi-tenant-fanout]
created: 2026-07-23
last-updated: 2026-07-23
---

# Client narrowing of the fan-out domain: a tenant-column list argument intersects the request set

## Motivation

R46's fan-out domain ([`multi-tenant-fanout.md`](multi-tenant-fanout.md)) is a request-level fact: the factory-supplied tenant set intersected with the hosted map, applied uniformly to every fanned field in the operation. It cannot express narrowing driven by the query itself, such as a client selecting two of their ten authorized institutions for one field. Today that shape is approximated with an ordinary filter argument: the per-tenant WHERE clause makes unwanted tenants return empty, but every domain tenant is still queried, so the client pays connection, session, and query cost for databases it asked nothing from.

## Direction (to be developed at Spec)

A **list-valued argument whose column mapping lands on the tenant column**, appearing on a `@tenantFanOut` field, intersects the fan-out domain instead of filtering rows: domain = factory set ∩ hosted map ∩ requested list. Safe by construction: intersection only ever shrinks the authorized set, so the client can exclude but never add a tenant; the factory set remains the authorization boundary and RLS remains defense in depth.

This is the fan-out cousin of R45's `ArgumentBound` (where a scalar tenant-column argument routes the field to exactly one tenant). Note the interplay with R46's rejection list: R46 v1 rejects a marked field that already holds a binding, which is exactly the coordinate this item lands on later; the rejection is the placeholder, and this item relaxes that one combination (list-valued tenant argument + marker) into narrowing semantics, keeping the scalar-argument + marker case rejected (a scalar contradicts fanning).

## Open questions for the Spec pass

1. **Semantics of a requested tenant outside the factory set.** Silent skip (the authorization pre-filter posture: the client asked for something it may not see) vs client-visible error. Leaning silent skip for consistency with R46's mapped-but-unclaimed rule, but the client-facing surface deserves its own look.
2. **Semantics of a requested tenant not hosted.** R46 makes factory-set-but-unhosted a request error (the consumer's statement that data could exist there); a *client*-requested unhosted tenant is a different actor making the claim.
3. **Nullability and absence.** An omitted or null argument must mean "no narrowing" (the full R46 domain), never "empty domain".
4. **Classification shape.** Whether the narrowing slot rides the existing `FanOut` singleton (gaining a carried argument reference and un-singletoning it) or forms a sibling arm; either way the exhaustive switches R46 mandates make the landing loud.

## Siblings

- **Depends on R46** ([`multi-tenant-fanout.md`](multi-tenant-fanout.md)): the fan-out arm, domain rule, and marker this item narrows; R46's spec records this door next to its optional-directive-argument note.
- **R505** ([`tenant-index-parent-row-routing.md`](tenant-index-parent-row-routing.md)): the orthogonal narrowing axis, data-driven (which tenants hold rows) where this item is client-driven (which tenants the caller wants).
