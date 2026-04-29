---
title: "Checked exceptions on `@service` / `@tableMethod` for typed GraphQL errors"
status: Backlog
bucket: architecture
priority: 8
theme: mutations-errors
depends-on: [error-handling-parity, mutations]
---

# Checked exceptions on `@service` / `@tableMethod` for typed GraphQL errors

Explore mapping developer-declared checked exceptions on service / table-method methods to typed GraphQL errors (`@error` types, mutation payload error unions). Today `ServiceCatalog.reflectServiceMethod` / `reflectTableMethod` ignore `getExceptionTypes()`; the emitted fetcher has no `throws` clause, so a developer method declaring `throws SQLException` (or any checked exception) breaks the rewrite-test compile gate.

Direction worth scoping: treat declared exceptions as a typed error channel that maps to GraphQL error shapes on the wire, classified against `@error` types or mutation payload union members so the generated fetcher catches the typed exception and yields a graphql-java error of the matching shape. Cheap stop-gap if the exploration stalls: a classify-time rejection with a clear message naming the offending exception class.

Most relevant once mutation bodies (Stubs #4) lands, since service-method signatures are most commonly checked-exception-bearing on the write path.
