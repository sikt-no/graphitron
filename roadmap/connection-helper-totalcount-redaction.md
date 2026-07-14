---
id: R476
title: "Route ConnectionHelper.totalCount failures through the ErrorRouter redaction contract"
status: Backlog
bucket: generator
depends-on: []
created: 2026-07-14
last-updated: 2026-07-14
---

# Route ConnectionHelper.totalCount failures through the ErrorRouter redaction contract

`ConnectionHelper.totalCount(env)` issues its own SQL but is wired as a bare delegate with no
try/catch, so a failure (e.g. a jOOQ `DataAccessException`, whose message embeds the rendered
SQL) reaches graphql-java's default exception handler, which copies the raw message into the
client-visible errors array; this bypasses the `ErrorRouter.surfaceClientErrorOrRedact`
redaction contract every emitted fetcher honours. The R13 review (finding 5) surfaced the gap;
the facets delegate now routes through the redaction path
(`ConnectionFetcherClassGenerator.facetsDelegate`) and is the template: give totalCount the
same catch arm and pin the degrade contract (nullable field resolves to null, page unaffected,
redacted error) the way `GraphQLQueryTest.filmsFaceted_facetFailure_degradesToNullFacetsWithRedactedError`
does for facets.
