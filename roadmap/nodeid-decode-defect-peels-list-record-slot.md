---
id: R990
title: "The @nodeId decode defect refuses a List of the node table records as KEY_ARITY_EXCEEDS_SLOT, which the generator accepts"
status: Backlog
bucket: bug
theme: nodeid
depends-on: []
created: 2026-10-06
last-updated: 2026-10-06
---

# The @nodeId decode defect refuses a List of the node table records as KEY_ARITY_EXCEEDS_SLOT, which the generator accepts

## Goal

A root `@service` whose argument `[ID!]! @nodeId(typeName: "X")` lands on a `List<XRecord>` parameter, where `XRecord` is the generated record of `X`'s own table, builds without a decode defect whatever the arity of `X`'s key, because the generator already hands each decoded tuple to one element of that list. Today the fact store reads the parameter's container instead of its element. For a composite key it refuses the call as `KEY_ARITY_EXCEEDS_SLOT`, and the refusal advises declaring the parameter as the generated record, which is what the author already did. The store and the generator should give one answer for this slot, and the *decode defect* (`intent_node_id_decode_defect`, the store view the build turns into decode errors through `NodeIdDecodeDefects`) should refuse only a slot that really cannot take the tuple.

## Minimal pair

At the store tier, next to `NodeIdDecodeDefectTest.aRecordOfTheNodeTypesOwnTableIsNoDefectAtAnyArity`, which seeds the composite-key node type `FilmCategory` over `film_category` and a producer parameter typed as `film_category`'s record:

- Today, passing: the parameter typed `Map.of("", recordClass("film_category"))` draws no defect row and one `JOOQ_RECORD` destination of arity 2.
- New, failing today: the same parameter typed `Map.of("", "java.util.List", "0", recordClass("film_category"))`, the census shape `AccessorHopTest` already seeds for a `List<LanguageRecord>`. Expected: no defect row, and the same `JOOQ_RECORD 2` destination in `intent_node_id_decode`. Today it draws `KEY_ARITY_EXCEEDS_SLOT arity 2`.

At the generator tier: the same shape over sakila, with a test schema declaring `FilmCategory @node` over `film_category` and a root `@service` taking `List<FilmCategoryRecord> ids` from `ids: [ID!]! @nodeId(typeName: "FilmCategory")`, classifies as `CallSiteExtraction.NodeIdDecodeRecord` (already true) and its build reports no rejection (false today).

## What is true today

- `intent_node_id_decode_slot.java_type` is the *root* of the parameter's declared type, by its own column comment: "a container-typed parameter therefore reports the container". A `List<XRecord>` parameter reports `java.util.List`.
- `intent_node_id_decode_defect` excludes the record case on `s.java_type <> k.record_class`. For a list parameter that inequality holds, so with `k.arity > 1` the row is emitted as `KEY_ARITY_EXCEEDS_SLOT`. The slot arm of `intent_node_id_decode` tests `s.java_type = k.record_class` for `JOOQ_RECORD` the same way, so the list slot is missing from the destinations too.
- The generator, `ServiceCatalog.takesTheNodeTablesRecord`, looks past one `List<...>` wrap and classifies the slot as `NodeIdDecodeRecord`. Its javadoc names exactly this case: comparing the declared type as written "would read `List<XRecord>` as a single-valued slot".
- The store already holds the peeled type: `code_method_parameter.element_class`, with `code_type_element.delivery` saying whether a container multiplies. So the fix needs no new capture; it needs the slot relation (or its readers) to carry the element.
- The slot column comment says "that a list of node ids is a coherent request rather than a mistake is stated on the leaf relation and is a consumer's reading". The defect view is a consumer that does not apply that reading.

## Open points for the Spec

1. **Where the element enters.** Add an element column to `intent_node_id_decode_slot` that both readers test, or keep `java_type` as the root and have each reader join the element itself. The slot comment argues for keeping the root equal to `intent_argmapping_bound_parameter_type.java_type`; an added column keeps that argument intact.
2. **Which containers.** The generator peels `List` only; `code_type_element` peels every container and calls `Set`, `Collection` and jOOQ `Result` multiplying too. A `Set<XRecord>` parameter today goes to the generator's one-column path and is refused by the store, so the two agree there, though the message is still wrong. The Spec decides whether `Set<XRecord>` becomes a supported shape (generator and store together) or stays refused with a message that names the container.
3. **Refusal text.** Whatever stays refused, the `KEY_ARITY_EXCEEDS_SLOT` message in `NodeIdDecodeDefects` should not advise the type the parameter already has.
4. **Input-field site.** The defect view draws no row at the `INPUT_FIELD` site (its stated population edge), so a nested `@nodeId` member reaching a list of records is not this bug. The Spec should confirm that, not pin it as part of the fix.

## Why it surfaced now

Reported by the sis Graphitron 10 migration on trunk at `17196b8eb`: six root services (`godkjennUndervisningsaktivitetForPublisering` and five siblings on `UndervisningsaktivitetService` and `UndervisningsenhetService`) taking `List<UndervisningsaktivitetRecord>` or `List<UndervisningsenhetRecord>` for an eight-column key. The same snapshot and schema validated clean earlier the same day and refused these six after the service module's classes were rebuilt. Not verified which change caused it. Read off the SQL, a NULL `java_type` would still refuse at arity 8 (`anUntypeableParameterAtACompositeKeyIsStillRefusedOnTheArity`), so the clean run more likely had no slot row at all: no `code_method_parameter` row for those methods, either the R973 shape (a first capture writes no classpath facts) or service classes absent from the scanned classpath. The other candidate is R985 widening the classification domain the build-error surface joins. Either way the population is real and the verdict is wrong.
