---
id: R1036
title: "A bad node id fails with the same client error at every @nodeId site, never redacted"
status: Backlog
bucket: bug
priority: 3
theme: nodeid
depends-on: []
created: 2026-10-14
last-updated: 2026-10-30
---

# A bad node id fails with the same client error at every @nodeId site, never redacted

## Goal

Every node-id decode failure has one shape. A client that sends a malformed id, or an id of the wrong type, gets the `NodeIdDecodeFailure` message: `Invalid node id "<id>" for this argument: not a valid <Type> id`, or `... decodes to type "<n>", expected a <Type> id`. The error is raised as the generated `GraphitronClientException`. It does not matter whether the id sits in a query filter, a lookup argument, a `@service` argument or a DML `@mutation` input field. Today only some of those sites behave this way. The rest throw a plain `GraphqlErrorException` that says "Decoded NodeId did not match the expected type ...". It neither echoes the id nor says whether the id was malformed or of another type. `ErrorRouter.surfaceClientErrorOrRedact` does not recognise it as a client error, so on a field without an errors channel the client reads "An error occurred. Reference: <uuid>" instead of a reason. The statement still fails and writes nothing either way, so this is a diagnosability defect, not a data one: the client cannot tell a bad id from a server fault.

`NodeIdDecodeFailure`'s own javadoc states the rule this breaks: one bad id failing two ways tells a client that one spelling of an id validates and another does not.

## The sites

Already on `NodeIdDecodeFailure`: the key helpers `CompositeDecodeHelperRegistry` mints (filters), `RecordDecodeFragments`' failing helpers that take a supplied throw, and `MultiTablePolymorphicEmitter`'s multi-candidate dispatch.

Still throwing `GraphqlErrorException` with "Decoded NodeId did not match the expected type":

* `TypeFetcherGenerator.appendDecodeLocal` and both branches of `TypeFetcherGenerator.appendSetDecodeLocal`: DML input fields, `for input field '<f>'`. This covers the UPDATE rail and the WHERE-side decode locals other rails share. Spec confirms which INSERT and DELETE shapes reach it.
* `RecordDecodeFragments.decodeHelper`, the overload without a supplied throw (the input-bean family), and `JooqRecordInstantiationEmitter.DECODE_MISMATCH_MSG`: `@service` arguments, `for this argument`.
* `LookupRows` (the lookup-key decode, `for argument '<a>'`).

## Things to settle in Spec

* **Naming the field.** The DML and lookup messages name the input field or argument; `NodeIdDecodeFailure` says "for this argument". Either the shared message gains an optional location that every site fills where it has one, or the field name is dropped. Pick one and apply it everywhere, since a site that names the field and a site that does not are two shapes again.
* **A non-`String` wire value.** Each site guards with `instanceof String`, so a list arriving where a single id is expected also lands in the mismatch branch (R419 is the build-time fix for the one shape known to reach it). `peekTypeId` needs a `String`; decide what the message says for a value that is not one.
* **Out of scope:** an explicit `null` on a `@nodeId` field (`identityClearMessage`, the "Conflicting values" texts). Those are refusals of a well-formed request, not decode failures, and keep their own messages.

## Routing

`GraphitronClientException` subclasses `graphql.GraphqlErrorException` (`GraphitronClientExceptionClassGenerator`, superclass from `GeneratedClientException.SUPERCLASS`), and a `GENERIC` handler matches by `isInstance`. So a payload whose `@error` handler names `GraphqlErrorException` claims these errors exactly as today. Without a channel, they now reach the client through `FetcherErrorResponse.clientErrorMessage` instead of being redacted. With R1126 the claimed error's `path` names the field that owns the payload, as for every other claimed error.

## Tests

`TableOnlyNodeIdLeafExecutionTest`: `updateLevelInterval(in: {fromLevelId: <XlatParent id>})` returns the same "Invalid node id" text that `levelIntervals(filter: ...)` returns for that id. `StraddlingReferenceUpdateExecutionTest`'s wrong-type cases, which today assert only that `errors` is non-empty, assert the message. Add one case per site family that does not have one: a `@service` argument, a lookup argument, and a DML reference field on a mutation with no errors channel, each asserting the client sees the decode text and not a reference.

## Evidence

Seen while writing `TableOnlyNodeIdLeafExecutionTest` (sakila-example). `levelIntervals(filter: {fromLevelIds: [<XlatParent id>]})` returns the "Invalid node id" message, while `updateLevelInterval(in: {fromLevelId: <XlatParent id>})` returns the redacted reference. sis (the Graphitron 10 port, request C38, 2026-10-30) widened the item to every site. sis has 20 mutations without an errors channel where a wrong-type reference id currently returns a log reference. Graphitron 9 returned the decode text everywhere (an `IllegalArgumentException` whose message `TopLevelErrorHandler` passed through). sis's `UgyldigIdIMutasjonIT` already asserts the `Invalid node id` text for a `@service` mutation and a DML DELETE, so sis needs no change when this lands.
