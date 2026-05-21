---
id: R213
title: "Plain-input field rejections attributed to consumer field, losing input-field source location"
status: Backlog
bucket: bugs
depends-on: []
created: 2026-05-21
last-updated: 2026-05-21
---

# Plain-input field rejections attributed to consumer field, losing input-field source location

When a plain (non-`@table`) input type carries a broken `@condition` directive (parameter-binding mismatch, reflection failure, etc.) or an unresolvable column, the failure surfaces as an `UnclassifiedField` on the *consuming* field (e.g. `Query.opptak`), not on the input type's offending field (e.g. `OpptakFilterInput.opptaksNavn`). The reported `SourceLocation` is the consuming field's definition, so LSP fix-its, watch-mode formatters, and editor highlights point one indirection away from the actual broken directive the author needs to edit.

## Symptom

Production schema (`opptak-subgraph`, same source as R211):

```graphql
input OpptakFilterInput {
    opptaksNavn: String @condition(
        condition: {className: "no.sikt.fs.opptak.opptak.OpptakService", method: "opptakNavnSok"},
        override: true
    )
}

type Query { opptak(filter: OpptakFilterInput): [Opptak!]! }
```

When `opptakNavnSok`'s parameter name doesn't match the input field name, the build error is rendered as:

```
Field 'Query.opptak': argument 'filter': plain input type 'OpptakFilterInput': input field 'opptaksNavn' @condition: parameter 'navn' in method 'opptakNavnSok' is not a GraphQL argument and not a context key
```

The `qualifiedName` is `Query.opptak` and the `location` is `Query.opptak`'s definition line. The author has to read the prose to find the actual offending site; tooling that consumes `ValidationError.location` can't take them there.

## Trace

The location is dropped early and the diagnostic is folded into prose at four successive layers:

1. `InputFieldResolver.resolve` (`InputFieldResolver.java:60-97`) iterates the input fields, collects column-miss failures (`InputFieldResolution.Unresolved`, which has no `SourceLocation`) and `condErrors` (`List<String>`, also no location) and folds everything into one `Resolution.Rejected(Rejection.structural("plain input type '<T>': ..."))`.
2. `FieldBuilder.classifyArgument` (`FieldBuilder.java:1010-1015`) wraps that into `ArgumentRef.UnclassifiedArg` on the consuming arg.
3. `projectFilters` (`FieldBuilder.java:1377-1380`) re-prefixes with `"argument '<n>': "` and pushes onto the parent field's `errors`.
4. `foldRejections` (`FieldBuilder.java:1277-1282`) joins everything into a single structural rejection, and `validateUnclassifiedField` (`GraphitronSchemaValidator.java:953-959`) emits one `ValidationError` whose `qualifiedName` is the consuming field and whose `location` is the consuming field's definition.

By the time the rejection reaches the validator, the offending `@condition` directive's `SourceLocation` on `OpptakFilterInput.opptaksNavn` has been discarded twice (at the `Unresolved` record's schema, and at the `condErrors` `List<String>` channel) and there's nothing left to point at except the consumer.

## Direction (sketch)

Three layers need touching:

- `InputFieldResolution.Unresolved` (`InputFieldResolution.java:20-24`) grows a `SourceLocation` field (the input field's definition, or the `@condition` directive's location when the failure is condition-side).
- The `condErrors` channel populated by `buildInputFieldCondition` becomes `List<LocatedRejection>` (or similar) rather than `List<String>` — each entry carries its input field's location.
- `InputFieldResolver.Resolution.Rejected` carries `List<LocatedRejection>` rather than a single folded `Rejection`. `FieldBuilder.classifyArgument` and `projectFilters` propagate the list; `UnclassifiedArg.rejection` either becomes a list-carrying variant or grows a sibling located-items slot.
- `validateUnclassifiedField` fans located items out into one `ValidationError` per offending input field, each with its own `SourceLocation`. The consuming field still gets one `ValidationError` (its existence as `UnclassifiedField` is itself a fact — emission can't proceed against it), but with a different message: "argument '<n>': input type '<T>' has unresolved fields — see errors on that type's fields" rather than the joined dump.

The change touches the rejection/diagnostic shape across three resolvers and the validator. Worth a design-fork pass with `principles-architect` before drafting Spec: the rejection sealed hierarchy is load-bearing for typed lifts (R205 `AuthorError.UnknownName`), and growing a list-carrying arm needs to interact cleanly with existing folding sites (e.g. `foldRejections`, `Rejection.prefixedWith`).

## Scope notes

- The fix benefits every plain-input failure mode (column-miss, condition reflection failure, parameter-binding mismatch), not just the R211 surface — same broken attribution applies to all of them.
- `@table` input types route through `TableInputType` classification at type-build time and already attribute to the input type via `UnclassifiedType`; this item is plain-input-only.
- R211 (`condition-override-true-misleading-column-miss-message.md`) is the narrower fix at the gate; it suppresses the column-miss noise line. R211 and R213 don't interact: R211 shrinks the message at the consuming-field carrier, R213 moves the carrier (or splits it). R211 ships first.

## References

- `graphitron/src/main/java/no/sikt/graphitron/rewrite/InputFieldResolution.java:20-24` — `Unresolved` record missing a location field.
- `graphitron/src/main/java/no/sikt/graphitron/rewrite/InputFieldResolver.java:60-97` — the folding site (Resolution.Rejected carries one `Rejection`, no per-field locations).
- `graphitron/src/main/java/no/sikt/graphitron/rewrite/FieldBuilder.java:1377-1380` — `UnclassifiedArg` → parent field's `errors` re-prefix.
- `graphitron/src/main/java/no/sikt/graphitron/rewrite/FieldBuilder.java:1277-1282` — `foldRejections`, the final collapse to a single rejection.
- `graphitron/src/main/java/no/sikt/graphitron/rewrite/GraphitronSchemaValidator.java:953-959` — `validateUnclassifiedField`, the single-ValidationError emission point.
- R211 (`condition-override-true-misleading-column-miss-message.md`) — narrower companion fix, same `opptak-subgraph` source schema.
- Surfaced by alf's production `opptak-subgraph` schema; same investigation pass as R211.
