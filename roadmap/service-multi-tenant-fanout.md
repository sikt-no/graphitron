---
id: R46
title: "Multi-tenant fan-out: run one field across many tenants and union the results"
status: Backlog
bucket: architecture
priority: 6
theme: runtime-connection
depends-on: [tenant-routing-and-execution-input]
last-updated: 2026-07-03
---

# Multi-tenant fan-out: run one field across many tenants and union the results

## Motivation

Two production patterns need the same shape:

- **No index narrows the tenant.** A student's results live in per-university databases. When a tenant-index table exists, R45 routes children per row; when it does not, the only way to answer "all results for this student" is to query *every* organisation and union what comes back. This is what production does by hand today.
- **Membership-driven fan-out.** A downstream resolver (`megVedLarested`) bypasses `@service` and hand-writes: for each tenant the logged-in user belongs to, open that tenant's connection, call the service, drop nulls, union. The service method is GraphQL-free Java; what does not fit codegen today is the per-tenant connection plumbing and the parallel orchestration.

## Direction (to be spec'd)

This item is the deliberate no-binding arm of R45's `TenantBinding` axis. An explicit schema marker (form open: a directive such as `@fanOut`, or a list-typed contextArgument naming the tenant subset) classifies the field into a fan-out arm; the arm and its emitters land together here, so R45's `noTenantBinding` rejection keeps guarding every unmarked unroutable field.

**Fan-out domain (resolved):** the intersection of the `Map<TenantId, DataSource>` keys and the tenantIds the user holds roles for in the request's claims, with the two directions of the difference treated differently. A mapped tenant the user holds no role for is never queried: the authorization pre-filter, silent by design. A claimed tenant missing from the map is a **request-level error before any SQL runs**, not a silent skip: the derived tenant set is the model's statement that data could exist there, so skipping would return incomplete results presented as complete, and R45 already gives the same event (a divined tenant with no `DataSource`) the same error semantics. Deployments where claims legitimately span more tenants than this subgraph hosts narrow the set in the claims-extraction seam (question 1), where the narrowing is the consumer's explicit statement rather than a silent runtime drop. Neither the whole map nor the raw claims set alone is ever the domain.

Mechanics ride the R429 substrate: acquisition per tenant in the domain through the map, one read-only transaction per tenant (R429's demarcation rule already covers N transactions per operation), session state set per acquisition so per-tenant RLS composes (a tenant where the user has no row access contributes nothing). Results union; nulls and empties drop. Connection threading is R429's acquisition seam, not hand-rolled executor code in generated fetchers.

The previous design here (a `ContextValueRegistration<FanOut>` permit, `DslContextPerElement`, and `GraphitronContext` widening with `getContextFanOut` / `openContextDslContext` / `getExecutor`) predates R190 sealing `GraphitronContext` and R429 owning connections; it is superseded and recorded in this file's git history.

## Open questions for the Spec pass

1. **Claims extraction seam.** The domain is resolved (map keys intersected with the user's role-bearing tenantIds, above); what remains is how graphitron reads the tenant set out of the claims. Candidates: the consumer derives a collection-typed contextArgument (e.g. `Set<Long> tenantRoles`) from the JWT before calling the factory, keeping graphitron claims-format-agnostic; or graphitron takes a claims-map contextArgument plus a configured extraction. The pre-derived contextArgument is the lighter seam and matches how the hand-written resolver already reads `institusjonsroller`. This seam also owns relevance-scoping: when claims legitimately span more tenants than this subgraph hosts, the consumer narrows the derived set here, which is what keeps the missing-tenant error above meaningful.
2. **Marker syntax.** Directive vs contextArgument-driven; reconcile with R45's inference posture (fan-out cannot be inferred, it must be asked for).
3. **Result semantics.** Ordering across the union; pagination and `@asConnection` over a fanned-out field; per-tenant partial failure (null-drop vs error surfacing, composing with the typed-errors plan).
4. **Parallelism bounds.** Fanning out to dozens of databases per field needs a concurrency cap and a timeout story; likely R429 config.

## Siblings

- **Depends on R45** ([`tenant-routing-and-execution-input.md`](tenant-routing-and-execution-input.md)): the `TenantBinding` axis this item adds its arm to, and the tenant-index routing that makes fan-out the fallback rather than the default.
- **Depends on R429** ([`connection-transaction-lifecycle.md`](connection-transaction-lifecycle.md)): acquisition, transaction demarcation, session state, and the threading rules fan-out must not reimplement.
