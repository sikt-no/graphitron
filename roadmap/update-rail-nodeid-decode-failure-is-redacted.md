---
id: R1036
title: "A wrong-type @nodeId on a DML UPDATE input reaches the client as a redacted server error"
status: Backlog
bucket: bug
priority: 4
theme: classification-model
depends-on: []
created: 2026-10-14
last-updated: 2026-10-14
---

# A wrong-type @nodeId on a DML UPDATE input reaches the client as a redacted server error

## Goal

A client that sends an id of the wrong type, or a malformed id, to a `@nodeId` field on a `@mutation(typeName: UPDATE)` input gets back the same "Invalid node id ... decodes to type X, expected a Y id" error a query filter returns for the same id, instead of "An error occurred. Reference: <uuid>". Today the UPDATE rail's generated decode check throws a plain `GraphqlErrorException` ("Decoded NodeId did not match the expected type for input field 'f'"), which `ErrorRouter.surfaceClientErrorOrRedact` does not recognise as a client error and so redacts. The read rails raise the generated `GraphitronClientException` through `NodeIdDecodeFailure`, which survives. The statement still fails and writes nothing, so this is a diagnosability defect, not a data one: the client cannot tell a bad id from a server fault.

## Evidence

Seen while writing `TableOnlyNodeIdLeafExecutionTest` (sakila-example): `levelIntervals(filter: {fromLevelIds: [<XlatParent id>]})` returns the "Invalid node id" message, while `updateLevelInterval(in: {fromLevelId: <XlatParent id>})` returns the redacted reference. The generated `MutationFetchers.updateLevelInterval` shows the `GraphqlErrorException` throw. The shape is not specific to that test's `@table`-only target: node-type references on UPDATE inputs go through the same emitter, which is why `StraddlingReferenceUpdateExecutionTest`'s wrong-type cases assert only that the errors are non-empty. Whether the INSERT and DELETE rails share it is part of the Spec work.
