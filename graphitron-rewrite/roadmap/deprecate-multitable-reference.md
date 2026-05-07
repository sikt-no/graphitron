---
id: R44
title: "Deprecate `@multitableReference`"
status: Backlog
bucket: cleanup
priority: 5
theme: interface-union
depends-on: []
---

# Deprecate `@multitableReference`

No consumer of graphitron-rewrite uses `@multitableReference`. Rather than carry the
stub through the RC and ship coverage we don't need, the directive joins `@notGenerated`
on the deprecation list: it stays declared in `directives.graphqls` so the GraphQL parser
does not fail with an "unknown directive" error, but the rewrite classifier rejects every
application with a "no longer supported" message and points the schema author at the
migration note.

The shape `ChildField.MultitableReferenceField` and its sibling `TypeFetcherGenerator`
stub entry exist only to keep the `[deferred]` rejection alive while we figured out
whether to ship support. Now that the answer is no, both can retire alongside the
classifier change. The directive declaration itself remains for parser tolerance.

## Scope

- `graphitron-rewrite/graphitron/src/main/resources/no/sikt/graphitron/rewrite/schema/directives.graphqls`:
  rewrite the directive description to mirror the `@notGenerated` removal note ("Removed.
  ... is no longer supported. The directive stays declared here only so the GraphQL parser
  does not fail; the rewrite classifier rejects any application with a 'no longer
  supported' message"). Leave the directive declaration shape alone so consumer SDLs that
  still mention it parse before classification rejects.
- Classifier path: wherever `MultitableReferenceField` is built today (FieldBuilder, the
  scalar-`@reference`-to-multi-table-target arm), redirect to `UnclassifiedField` with
  a `Rejection.AuthorError` carrying the "no longer supported" message. Confirm the
  rejection vocabulary maps to the right `RejectionKind` so the log prefix reads as a
  deliberate deprecation rather than a `[deferred]` stub.
- Remove `ChildField.MultitableReferenceField` from `TypeFetcherGenerator.STUBBED_VARIANTS`
  and from the sealed permits in `ChildField`. The `MultitableReferenceField` record
  itself can go: nothing else in the model needs it once classification no longer routes
  there. `VariantCoverageTest` and the generator dispatch test pick up the removal
  automatically (sealed-switch exhaustiveness).
- Pipeline test: assert that an SDL using `@multitableReference` produces an
  `UnclassifiedField` with the deprecation message (not `[deferred]`).
- Migration guide entry in `docs/manual/how-to/migrating-from-legacy.adoc`: short note
  alongside `@notGenerated` describing the removal and what schema authors should do
  (drop the directive; if the use case was multi-table interface dispatch, document the
  `@reference` + interface-union path that does the same job).

## Non-goals

Restoring or extending multi-table reference support. If a consumer surfaces a real use
case post-RC, that's a fresh roadmap item, not a revival of this stub.
