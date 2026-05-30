---
id: R265
title: "Inline ThrowOnMismatch arms emit non-compiling new GraphqlErrorException(String)"
status: Backlog
bucket: cleanup
priority: 5
theme: model-cleanup
depends-on: []
created: 2026-05-30
last-updated: 2026-05-30
---

# Inline ThrowOnMismatch arms emit non-compiling new GraphqlErrorException(String)

## Problem

graphql-java 25's `GraphqlErrorException` has only a protected builder constructor; there is no
public `GraphqlErrorException(String)`. The correct construction is
`GraphqlErrorException.newErrorException().message(..).build()` (as
`LookupValuesJoinEmitter.java:330` and the R195 `InputBeanInstantiationEmitter` already do).

Several inline NodeId `ThrowOnMismatch` emitters still emit `throw new GraphqlErrorException($S)`,
which **does not compile**:

- `ArgCallEmitter.java:395` (arity-1/N list throw arm), `:432` (arity-1 scalar throw arm), `:451`
  (arity-N scalar throw arm).
- `CompositeDecodeHelperRegistry.java:98` (list throw arm), `:113` (scalar throw arm).

This is latent, not live: no compilation-tier fixture currently exercises a `ThrowOnMismatch` arm
(top-level `@nodeId` scalar/list lookup-key argument), so the broken string is never compiled.
R195 surfaced it when its `decode<Record>` helper became the first compile-tested NodeId throw and
failed against the real graphql-java API.

## Fix shape

Switch the five sites to `GraphqlErrorException.newErrorException().message($S).build()`, and add a
compilation-tier fixture in `graphitron-sakila-example` for a top-level `@nodeId` lookup-key
argument (the `ThrowOnMismatch` shape) so the throw arm is compiled and the regression cannot
recur. The Supplier-lambda wrapper in the scalar arms (`(($T<?>) () -> { throw ...; }).get()`)
stays; only the exception construction inside it changes. Cross-check `FetcherEmitter.java:209` —
confirm whether its `new $T($S)` resolves to `GraphqlErrorException` (broken) or a String-ctor
exception (fine) and include it if broken.
