---
id: R1047
title: "An @error GENERIC handler can name the generated GraphitronClientException on a clean build"
status: Backlog
bucket: architecture
priority: 4
theme: error-channel
depends-on: []
created: 2026-10-15
last-updated: 2026-10-15
---

# An @error GENERIC handler can name the generated GraphitronClientException on a clean build

## Goal

A consumer can route graphitron's own client errors into an `@error` payload type with a handler that matches exactly those errors and holds on a clean build. Graphitron's generated code throws `GraphitronClientException` for client mistakes: a tenant outside the request's set, a NodeId that decodes to the wrong type, conflicting values. That class is emitted into the consumer's output package by the same generator run that validates `@error` handlers. `TypeBuilder.validateExceptionClass` checks a GENERIC handler's `className` through `ClasspathNameability.verdictFor` and `Class.forName` on the codegen loader, and neither recognises a class the run is about to emit. So the name resolves only when an earlier compile left the class in `target/classes`; on a clean checkout or after `mvn clean` the handler is rejected as "not carried by this module's own classes".

The only robust workaround today is a GENERIC handler on `graphql.GraphqlErrorException`, the graphql-java superclass. It needs graphql-java declared directly in the consuming module, and it matches far more than graphitron's client errors. The Spec should choose between two options. One is to recognise the generated class's name (the consumer's output package plus `GraphitronClientException`) in the classify-time checks. The other is a stable, non-generated supertype or a dedicated handler kind that names "graphitron client errors" without a class name.

## Evidence

Reported from sis. A GENERIC handler on the generated `GraphitronClientException` on `UgyldigInput` passed only against a stale `target/classes` and was rejected on `mvn clean generate-sources`. The `GraphqlErrorException` workaround passes a clean build and the affected integration tests, and puts the foreign-institution tenant error into the payload as `UgyldigInput`, which is v9's shape.

## Related

R1046 makes an unmatched `GraphitronClientException` surface as a top-level error on `@error`-channel fetchers instead of being redacted. It decides the no-match case only; this item is about letting an authored match name the class precisely.
