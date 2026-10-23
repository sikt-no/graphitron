---
id: R1098
title: "graphitron-jakarta-rest hands the request headers to newExecutionInput"
status: Backlog
bucket: feature
priority: 5
theme: runtime-connection
depends-on: []
created: 2026-10-23
last-updated: 2026-10-23
---

# graphitron-jakarta-rest hands the request headers to newExecutionInput

## Goal

A `graphitron-jakarta-rest` adapter can read the current request's headers where it builds the per-request `ExecutionInput`, and gets a ready-made `Accept-Language` parser, without a `@RequestScoped` holder that the resource fills before execution. Today `GraphitronApplication.newExecutionInput()` takes no parameters, so every consumer that needs request data (identity claims, a preferred language) copies the same holder workaround.

## Plan

`GraphitronApplication` gains `newExecutionInput(HttpHeaders headers)` as a default method that delegates to the existing no-argument `newExecutionInput()`, so adapters written today keep compiling and behaving identically. `GraphqlHttpHandler` calls the new overload with the `HttpHeaders` it already receives. `HttpHeaders` is chosen over a wrapper type because it is what the handler is already handed on every route, and a consumer's mounted resource passes it through unchanged. Open for the spec round: whether the no-argument method stays abstract until a later deprecation, or both become defaults that must be overridden in pairs.

`AcceptLanguage.parse(HttpHeaders)` in the same module returns the client's languages as an ordered `List<String>` (RFC 9110: split on commas, sort by q-value with header order as the tiebreak, drop `q=0` and `*`). It lives in `graphitron-jakarta-rest`, which compiles at Java 17, so it stays plain Java 17.

The `AbstractGraphitronApplication` javadoc example and `docs/manual/how-to/error-channel.adoc` stop recommending the holder for header-derived values; the holder remains documented for path-parameter values only the resource can see.

Tests: a `graphitron-jakarta-rest` test that an adapter overriding the new overload sees the request's headers and an adapter overriding only the old method still works; a unit test of `AcceptLanguage.parse` (q ordering, ties, `q=0`, `*`, malformed input, absent header gives an empty list).

Consumed by the translated-text column selection (R1096), which reads the language list as a declared context argument.
