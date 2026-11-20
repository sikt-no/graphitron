---
id: R1202
title: "A list of node ids bound by argMapping at a field-level @condition is refused, though the same binding by parameter name builds"
status: Backlog
bucket: bug
priority: 6
theme: nodeid
depends-on: []
created: 2026-11-20
last-updated: 2026-11-20
---

# A list of node ids bound by argMapping at a field-level @condition is refused, though the same binding by parameter name builds

## Goal

A field-level `@condition` whose `argMapping` names a list-shaped `@nodeId` argument builds, and the bound parameter receives the decoded keys as a `List` of the key column's type, as it already does when the parameter is named after the argument and no `argMapping` is written. Today the two spellings of one binding disagree: by parameter name it builds, through `argMapping` it is refused, and the refusal's remedy leads to a second refusal that asks for the declaration the author started with. Reported in [GitHub issue 583](https://github.com/sikt-no/graphitron/issues/583), where the consumer dropped `@nodeId` and decodes by hand inside the condition, losing the client error a bad id gets at every other `@nodeId` site.

## What happens today

The reported shape is a root field with a list of node ids and a single node id, both read by one condition because the predicate is an `EXISTS` that needs them together:

```graphql
films(
    languageIds: [ID!]! @nodeId(typeName: "LanguageNode") @reference(path: [{key: "film_language_id_fkey"}])
    originalId: ID! @nodeId(typeName: "LanguageNode") @reference(path: [{key: "film_original_language_id_fkey"}])
): [Film!]! @condition(condition: {className: "...", method: "...", argMapping: "..."})
```

Four spellings of it were run through the build's validate pass on trunk at `dae0ddc`, against the sakila catalog, with a condition method on the classpath for each:

| Spelling | Parameters | Outcome |
|---|---|---|
| No `argMapping`, parameters named `languageIds` and `originalId` | `List<Integer>`, `Integer` | Builds. |
| `argMapping: "languages: languageIds, original: originalId"` | `List<Integer>`, `Integer` | Refused: "binds the @nodeId(typeName: "LanguageNode"), whose key column 'language_id' jOOQ binds as Integer, but the parameter it binds to takes List; bind a parameter of the column's own type". |
| The same `argMapping` | `Integer`, `Integer` | Refused twice. One message says to declare the parameter `java.util.List<java.lang.Integer>`, the other that the entry "opens a list of node ids ... which parameter binding does not emit yet". |
| `argMapping: "languages: languageIds.language_id, ..."` | `List<Integer>`, `Integer` | Refused twice: the type message above, and "descends into 'languageIds', a list of input objects with no single value below it", which misnames a list of ids. |

Only the validate pass was run; the generated query for the first row was not executed.

The first three rows are one binding. A *whole-slot binding* is a parameter bound to the whole of a `@nodeId` argument rather than to one column of its key; the user manual's `@condition` page says such a parameter receives the decoded key, wrapped in `List` for a list-shaped slot, and that it is bound "by giving it the slot's name, or with an `argMapping:` whose right-hand side is the bare slot". `ConditionResolver.installNodeIdDecode` implements that for both spellings and takes the list axis as a parameter, and `ProjectedKeyReads.installRailOwns` states that this rail wins at render where a bare binding also resolves as a projection.

The refusals come from the other claimant. A bare `argMapping` entry onto a node type with a one-column key also resolves as an inferred *projection* (the binding read as "the sole key column of the decoded id"), and `ArgmappingProjectionDefects` reports on that reading without knowing the whole-slot rail owns the binding. Its type gate compares the key column's Java type against the parameter's erased class, so `List<Integer>` reads as `List` and mismatches `Integer`; and its `listDeferred` arm defers every list-shaped projection outside `@service`. A single id escapes both only by coincidence: its parameter's type equals the column's, and it is not a list. The by-name spelling escapes because it writes no `argMapping` entry for the projection relations to read.

## Scope

In scope: a bare `argMapping` entry at a field-level `@condition`, naming a `@nodeId` argument the whole-slot rail owns, draws no projection defect and no list deferral, at either cardinality; and a pipeline case and an execution case hold the `argMapping` spelling to the by-name spelling's result.

To settle at Spec, not assumed here:

* Whether the same disagreement exists at a composite key, where the whole-slot rail hands a jOOQ `Row` and the projection side's "nothing to infer" verdict may fire on the bare entry. Not probed.
* Whether the store should state which rail owns a binding, so that no reader has to re-derive it, rather than `ArgmappingProjectionDefects` filtering on it.

Out of scope: the dotted form in the fourth row, a list projected column by column (`languageIds.language_id` into `List<Integer>`), stays deferred and is R735's. Its "list of input objects" message is wrong on its own terms and is worth a line there. A `@condition` on one argument not seeing a sibling argument, the second thing the issue tried, is that site's contract and is not changed.

## Workaround until this lands

Name the condition method's parameters after the GraphQL arguments and drop `argMapping`. The `@nodeId` directives stay, so ids are decoded before the method runs and a malformed or wrong-type id is the usual client error.
