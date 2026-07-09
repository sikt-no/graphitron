---
id: R280
title: "Typed non-empty carrier for fetcher-registration bodies"
status: Backlog
bucket: cleanup
priority: 30
theme: model-cleanup
depends-on: []
created: 2026-06-05
last-updated: 2026-06-05
---

# Typed non-empty carrier for fetcher-registration bodies

Backlog stub. Spun out of R166 (`graphqlschemavisitor-driven-emission`, retired into R279)
to keep a parked micro-refactor alive; originally surfaced as R165
(`fetcher-registration-empty-body-filter`) and flagged by the principles-architect read on
R165 as the natural endpoint its `Optional<CodeBlock>` deferral should not lose track of.

`FetcherRegistrationsEmitter.emit` returns a `Map<String, CodeBlock>` whose values must be
non-empty *by producer convention*: an empty body is the exact drift that produced R165's
bug (an empty-bodied keyset entry the registration emitter kept, `ObjectTypeGenerator`
skipped emitting the method for, and `GraphitronSchemaClassGenerator` still emitted the
call site against, failing the consumer's `javac`). The invariant lives in a comment and a
convention, not the type. Lift it into the signature: either a `NonEmpty<CodeBlock>`
wrapper (compact-constructor checked) or a dedicated `FetcherBodies` record that carries the
non-empty guarantee, so every consumer reads a value that cannot be empty and the
"empty body" drift class is a compile error rather than a runtime/`javac` failure.

Independent of R279: R279 removes the *reachability* drift (only reachable, classified
things reach emitters), but the non-empty-body invariant on this specific emitter return is
an orthogonal type-system strengthening that stands on its own. Small, contained; no Spec
fork beyond the wrapper-vs-record choice and the consumer-update surface
(`ObjectTypeGenerator` is the other reader).
