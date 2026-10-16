---
id: R1058
title: "Client errors thrown from generated fetchers carry no path or locations"
status: Backlog
bucket: bug
depends-on: []
created: 2026-10-16
last-updated: 2026-10-16
---

# Client errors thrown from generated fetchers carry no path or locations

## Goal

A client error that a generated fetcher raises carries the failing field's `path` and `locations`, as the GraphQL spec asks of a field error and as v9 did, so a client can tell which field failed. Examples are an invalid cursor and a tenant outside the request's set. Today these errors reach the response with only `message` and `extensions.classification`.

## Context

Reported from the sis port. Two observed shapes, both with `data.studenter: null` and no `path` or `locations` on the error:

- `studenter(after: "ugyldigID")` gives `{"message": "cursor is not valid (was: \"ugyldigID\")", "extensions": {"classification": "DataFetchingException"}}`. The message comes from the cursor decode `ConnectionHelperClassGenerator` emits.
- A tenant outside the request's set gives `"Tenant '186' is not permitted for this request."`, from the check `ConnectionRuntimeClassGenerator` emits.

A likely cause, to confirm at Spec: these are thrown as `GraphQLError` exceptions (for example `GraphqlErrorException`) built without `path` or `locations`. graphql-java's default exception handler hands a `GraphQLError` exception through as is, rather than wrapping it in `ExceptionWhileDataFetching`, which would fill both. The classification `DataFetchingException` fits that path. If so, the fix is to attach `env.getExecutionStepInfo().getPath()` and the field's source location wherever graphitron raises a client error, or to translate at one site that sees the environment. Spec should list every site that raises a client error rather than fix the two reported ones; the NodeId and record decode failures (`RecordDecodeFragments`, `JooqRecordInstantiationEmitter`) are candidates.
