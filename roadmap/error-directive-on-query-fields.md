---
id: R397
title: "Let bare-entity query fields host @error so decode and other client errors route through handlers"
status: Backlog
bucket: architecture
priority: 4
theme: error-channel
depends-on: []
created: 2026-06-29
last-updated: 2026-06-29
---

# Let bare-entity query fields host @error so decode and other client errors route through handlers

## Problem

`@error` is `on OBJECT`: handlers are declared on a payload object that carries an `errors` field, and the error *channel* binds to fields whose return type is such a payload. This already works for query fields whose return shape is payload-like: `WithErrorChannel` is implemented by `QueryServiceTableField`, `QueryServiceRecordField`, `QueryServicePolymorphicField`, and `QueryTableMethodTableField` (`WithErrorChannel.java:13` names "root + child services, root + child `@tableMethod` fields"), and `CatalogBuilder` resolves `errorChannelName(f.errorChannel())` for them. What has **no** error channel is a plain **bare-entity** fetch field (`soknader: [Soknad!]`): the sealed `QueryField` interface does not `extends WithErrorChannel` (`QueryField.java:25`, unlike `MutationField.java:18`), the table-fetch variants do not implement it, and their fetcher wraps work in a no-channel catch arm (`TypeFetcherGenerator` `redactCatchArm`). So a client-facing error raised while fetching a bare-entity field cannot be mapped to a typed `@error` payload; the consumer's only lever is the no-channel disposition.

This blocks the natural follow-on to R378 (`nodeid-filter-malformed-vs-mismatched`). R378 makes a malformed / wrong-type `@nodeId` filter throw a stable, generated client-error type (`GraphitronClientException`) and, via path B, surfaces it on bare-entity query fields through `ErrorRouter.surfaceClientErrorOrRedact` (real message in the standard `errors` array) rather than redacting it. R378 deliberately built that type to be `instanceof`-matchable by `ExceptionMapping`, so the missing piece is **not** "add a channel to query fields" in general (service / `@tableMethod` query fields already have one) but giving a bare-entity field a payload object to host `@error` handlers.

## Direction (for Spec)

- The hard design question, and the crux that keeps this out of R378: `@error` declares handlers on an OBJECT that hosts the `errors` field. A bare-entity field (`soknader: [Soknad!]`) has no such payload object. Decide the shape: does the consumer wrap such results in error-carrying payload types (the route the existing service/`@tableMethod` channels already take), or does the channel attach to bare-entity query fields by another means?
- Once the host is decided, extend the channel to the bare-entity table-fetch `QueryField` variants and swap their no-channel catch arm for channel dispatch when a channel is present (falling through to R378's `surfaceClientErrorOrRedact`, which is the no-channel disposition exactly as `redact` is for channel-less mutations today). The service/`@tableMethod` variants already do this and are the working precedent.
- Because R378 already throws an `instanceof`-matchable client-error type, the throw site needs no change: a `@error(handlers: [{handler: GENERIC, className: "<outputPackage>.schema.GraphitronClientException"}])` (or a `NodeIdDecodeException` subtype) matches it once a bare-entity field can host a channel.

## Relationship

Depends on R378 (the client-error type + surface/redact split are the predecessor). Sibling to R262 (`@nodeId`-on-non-ID validate-time rejection) and R273 (`bare-scalar-id-arm-modernisation`, re-scoped 2026-07-14: the mismatch policy is settled by R378 and the `__NODE_*` sourcing work moved to R473/R27; R273 keeps only the bare scalar-ID argument arm), both in the nodeid/errors space.
