---
id: R1051
title: "Name the @defaultTenant entity tenant apart from the global-read default, including none"
status: Backlog
bucket: feature
depends-on: []
created: 2026-10-16
last-updated: 2026-10-16
---

# Name the @defaultTenant entity tenant apart from the global-read default, including none

## Goal

A database-per-tenant consumer can name, per request, which tenant `_entities` uses for types marked `@defaultTenant` separately from the default tenant that serves global-table reads, and can name *no* entity tenant while global reads still have one. With no entity tenant, every representation of a `@defaultTenant` type answers `null` at its own position without an error and without opening a connection, as a request with no default does today; other entity types and all global reads are unaffected. When the consumer names nothing separately, entities keep using the request default, so existing callers see no change.

## Context

Reported from the sis port. sis's `FeideBruker` is `@table(name: "PERSON") @key(fields: "brukernavn") @defaultTenant`: a username unique only within one institution, resolved on the caller's home institution via `RequestTenants.withDefault(permitted, home)`. Callers with no home institution (machine users, calls outside access control) must get `null` for every `FeideBruker` representation, because guessing an institution can return another person's row for the same username. That is a privacy guarantee v9 gave and sis pins with an IT.

Today one value, the `RequestTenants` default (see `docs/manual/how-to/tenant-scoping.adoc` § Reading global tables from a tenant database), serves both `@defaultTenant` entities and global reads. For a caller without a home institution sis can either name no default (entities `null`, correct, but every global read goes to the default source, which sis does not have, so all global reads fail) or name some other institution (global reads work, `FeideBruker` resolves on an institution the caller does not belong to, a privacy leak). Neither reaches v9 parity.

Constraints from the request: `null` without an error (the router treats an unknown entity as normal); answers keep their batch positions; other entity types unaffected.

Shape is open for Spec. The reporter's sketch is `RequestTenants.withDefault(permitted, globalDefault).withEntityTenant(Optional<String>)`; the Spec should also weigh whether "global-read default" and "entity tenant" are better modelled as two named roles on `RequestTenants` from the start, and whether a named entity tenant must lie in the tenant set and be hosted, as the default must. The consumer-side alternative (sis wrapping `_entities` and nulling `FeideBruker` representations itself) is the fallback if this is declined; it reaches into generated dispatch and re-implements position bookkeeping graphitron already owns, which argues for doing it here.
