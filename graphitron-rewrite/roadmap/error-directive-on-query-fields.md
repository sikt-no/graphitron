---
id: R397
title: "Lift @error to query fields so decode and other client errors route through error handlers"
status: Backlog
bucket: architecture
priority: 4
theme: mutations-errors
depends-on: [nodeid-filter-malformed-vs-mismatched]
created: 2026-06-29
last-updated: 2026-06-29
---

# Lift @error to query fields so decode and other client errors route through error handlers

## Problem

`@error` is `on OBJECT` and routes through an error *channel* that only `MutationField` carries (`MutationField implements WithErrorChannel`; `QueryField` does not, `QueryField.java:25`). Query/fetch fields wrap their fetcher in a no-channel catch arm (`TypeFetcherGenerator` `redactCatchArm`). So a client-facing error raised while fetching a query field cannot be mapped to a typed `@error` payload the way a mutation's can; the consumer's only lever is whatever the no-channel disposition does.

This blocks the natural follow-on to R378 (`nodeid-filter-malformed-vs-mismatched`). R378 makes a malformed / wrong-type `@nodeId` filter throw a stable, generated client-error type (`GraphitronClientException`) and, via path B, surfaces it on query fields through `ErrorRouter.surfaceClientErrorOrRedact` (real message in the standard `errors` array) rather than redacting it. R378 deliberately built that type to be `instanceof`-matchable by `ExceptionMapping`, so the missing piece is purely the *channel*: give `QueryField` an error channel and route the same throw through `@error` handlers.

## Direction (for Spec)

- Make `QueryField` carry an error channel (`WithErrorChannel`), resolve channels for query result types, and swap the no-channel catch arm for channel dispatch when a channel is present (falling through to R378's `surfaceClientErrorOrRedact`, which becomes the no-channel disposition exactly as `redact` is for channel-less mutations today).
- The hard design question: `@error` declares handlers on an OBJECT that hosts the `errors` field. A list-returning query (`soknader: [Soknad!]`) has no payload object. Decide the shape: does the consumer wrap query results in error-carrying payload types, or does the channel attach to query fields by another means? This shaping is the crux and is why the lift is its own item, not folded into R378.
- Because R378 already throws an `instanceof`-matchable client-error type, the throw site needs no change: a `@error(handlers: [{handler: GENERIC, className: "<outputPackage>.schema.GraphitronClientException"}])` (or a `NodeIdDecodeException` subtype) matches it once the channel exists.

## Relationship

Depends on R378 (the client-error type + surface/redact split are the predecessor). Sibling to R262 (`@nodeId`-on-non-ID validate-time rejection) and R273 (NodeId mismatch semantics / `__NODE_*` sourcing), both in the nodeid/errors space.
