---
id: R579
title: "Drop the unused parentTypeName parameter from FieldBuilder.parseExternalRef"
status: In Review
bucket: cleanup
priority: 4
theme: legacy-migration
depends-on: []
created: 2026-08-03
last-updated: 2026-08-04
---

# Drop the unused parentTypeName parameter from FieldBuilder.parseExternalRef

`FieldBuilder.parseExternalRef` still takes a `String parentTypeName` first parameter
that its body never reads. The only consumer was the per-field deprecation warning on
the retired `ExternalCodeReference.name` arm; when that arm came out the parameter
stayed. Both call sites (`ServiceDirectiveResolver` and `ExternalFieldDirectiveResolver`)
thread a value that is discarded.

The dead value is not confined to one signature. At `ExternalFieldDirectiveResolver`
the `parseExternalRef` call is the *only* reader of that resolver's own
`parentTypeName` parameter, so dropping the argument orphans the parameter one level
up as well. The sweep therefore has to follow the value until it reaches a frame that
genuinely uses it, rather than stopping at the first signature.

## Implementation

Four edits, in dependency order. Each is removal-only; no logic moves.

- `FieldBuilder.parseExternalRef`: drop the leading `String parentTypeName` parameter.
  The body already reads none of it.
- `ServiceDirectiveResolver`, at the `parseExternalRef` call in the table-parent
  `resolve` overload: drop the argument only. `parentTypeName` stays live in that
  method (it feeds `validateTableRecordSourceParentTable`, `projectReturnType`, and the
  record-class lookup), so the signature is unchanged.
- `ExternalFieldDirectiveResolver`: drop the argument at the `parseExternalRef` call,
  **and** drop the now-unread `String parentTypeName` parameter from its own `resolve`
  signature. Its javadoc documents only `parentTable`, so no doc edit follows.
- `FieldBuilder`, at the `externalFieldResolver.resolve` call in the
  `@externalField` classify branch: drop the argument. `parentTypeName` stays live in
  the enclosing method (it constructs both `UnclassifiedField` and `ComputedField`),
  which is where the cascade terminates.

Every symbol involved is package-private within `no.sikt.graphitron.rewrite`, and
neither resolver has a test caller, so there is no external surface to consider.

## Tests

None to add. This is a signature narrowing with no behavioural axis: the compiler is
the whole verification story, and the existing pipeline tier already covers both
`@service` and `@externalField` classification end to end. Adding a test here would
pin an arity rather than a behaviour.

## Acceptance

`parseExternalRef` and `ExternalFieldDirectiveResolver.resolve` no longer declare a
`parentTypeName` parameter; no call site passes one. Full reactor green under
`-Plocal-db`, with no test edits in the diff.
