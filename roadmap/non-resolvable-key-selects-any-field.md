---
id: R1210
title: "A federation key declared resolvable: false may select a field read through a reference"
status: Spec
bucket: bug
priority: 3
theme: diagnostics
depends-on: []
created: 2026-11-26
last-updated: 2026-11-26
---

# A federation key declared resolvable: false may select a field read through a reference

## Goal

A federation `@key` declared with `resolvable: false` may select any field its type declares, including one read through a `@reference` path, so a subgraph can hand out references to an entity by a key it cannot look rows up by. Today generate refuses such a key with `KEY_FIELD_NOT_A_COLUMN`, a refusal whose own reasoning (an entity is resolved by matching the key's values against its table's columns) holds only for a key this subgraph resolves. `resolvable: false` tells the supergraph composer, the router-side step that merges every subgraph's schema, that another subgraph resolves the entity by that key, so this subgraph never looks a row up by it and no column match ever happens. When the item lands, sis restores the key it dropped to get past the refusal.

The schema the sis port to 10.0.0-RC58 hit, in sis `personProfil.graphql` (it fails the same way on trunk):

```graphql
type PersonProfil implements Node @table(name: "PERSON")
    @key(fields: "eierOrganisasjonskode fodselsnummer", resolvable: false)
    @key(fields: "id") {
  fodselsnummer: String @field(name: "FODSELSNR")
      @reference(path: [{table: "PERSON_FODSELSNUMMER", key: "PERSON__HAR__PERSON_FODSELSNUMMER"}])
}
```

```
Type 'PersonProfil': A federation @key selects a field that is not a column of the entity's own table,
a nested selection or a field read some other way, and an entity is resolved by matching its key's
values against that table's columns: key on fields that read the table's columns, or on the node's
own id alone. (fodselsnummer) [KEY_FIELD_NOT_A_COLUMN]
```

The minimal pair sits in the sakila shape the entity-key fact document (`graphitron-model/src/test/resources/facts/defects/entity-key.graphqls`) already uses: its `Film @key(fields: "languageName")`, a field read through `film_language_id_fkey`, stays refused, and the same key written `@key(fields: "languageName", resolvable: false)` is accepted.

The other key refusals keep applying to a non-resolvable key, since they hold whoever resolves it: `KEY_FIELD_MISSING` (a key naming a field the type does not declare, already pinned on a non-resolvable key by the fact document's `Course`) and `KEY_SELECTS_NOTHING`.

## Why lifting the refusal alone is not enough

The refusal is one arm of `graphitron_entity_key_entry_defect_rule` in `graphitron-model.sql`. It refuses a selection of a declared field that has no row in `graphitron_key_field_binding` (one row per key field that binds at the entity's own table: a column, the node's own id, or an `@accept` voucher), and it never reads the key's `resolvable` flag. "Binds nothing" covers more than a `@reference` field: a nested selection (`language { name }`), an `@nodeId` reference, the node's own id beside another field and a computed field all fall in the same arm. The `KEY_TYPE_NOT_TABLE_BOUND` arm of the same view already exempts a non-resolvable key through `COALESCE(k.resolvable, TRUE) = TRUE`, the precedent for this one.

Two readers assume today that every key on a generated entity binds in full, and both would turn the lifted refusal into a quieter failure:

- **The store reader.** `EntityKeyQuery.read` (graphitron) builds an entity's key alternatives, the keys the `_entities` dispatch tries a representation against, from `graphitron_key_field_binding`. `graphitron_key_field_binding_rule` writes rows for every authored key, resolvable or not, and no row for a field that binds nothing. So sis's key would draw one row, for `eierOrganisasjonskode`, and become an `Alternative.Direct` over that field alone: a key the entity does not have. A key none of whose fields bind is skipped by the `key.fields().isEmpty()` check instead. Every emitter skips a non-resolvable alternative, but `EntityTenantRouting.partnerOf` pairs a walk alternative with the store's `Direct` alternatives by field list without reading `resolvable`, so a partial key over the same fields as a resolvable one makes the pairing throw.
- **The walk.** `EntityResolutionBuilder` (graphitron), the classifier-side builder of the `entitiesByType` map, is retiring but still the source of two decisions: whether the entity dispatch is emitted at all (`EmitPlan`, `GraphitronSchemaClassGenerator`), and the tenant routing in a database-per-tenant build (`TenantBindingIndex.classifyEntityDispatch`, then `EntityTenantRouting.fromWalk`). Its `buildAlternative` returns null for any key with a field that reads no column, resolvable or not, and `build` then drops the whole type, reasoning that such a key is the store's refusal. With the refusal lifted, `PersonProfil`, resolvable by its `id` key, would leave the walk's entities: in a multi-tenant build its representations would be dispatched with no tenant routing, and nothing raises an error, because `fromWalk` iterates only the types the walk routes.

The design therefore states "this key binds in full" once in the store, has every reader take its keys from there, and makes the walk keep a type whose non-resolvable key does not bind.

## Implementation

**A stored relation of the keys that bind in full.** Add `graphitron_entity_key` to `graphitron-model.sql`, with its `_rule` view, written by the entity key stage (`EntityKeyAlternatives.derive`) right after `graphitron_key_field_binding` and in the same clear-and-insert shape. Grain: one row per `@key` application, authored or minted, on a table-bound object type, every field of which binds at that type's table. Columns: the application's key (`graph_name`, `type_name`, `directive_name`, `ordinal`) and `resolvable`, resolved once: an authored key's as written, a minted key's as its minting rule states (`graphitron_minted_key.resolvable`), true where the author left it out. Primary key on the application's key, a cascading foreign key to `graphitron_directive_application`, `CHECK (directive_name = 'key')`. The rule: an authored key has at least one selection and no selection position without a binding row (the predicate the defect arm negates); a minted key has its one binding row. It is registered the way `graphitron_key_field_binding` is: a `meta_relation` row with the `entity-key-alternatives` owner and an example, the stage's relation list, and the rule pairing. This follows the fact model's ruling that a plain join several rules and readers build on is stored once (`docs/architecture/explanation/fact-model.adoc`, Alf, 2026-11-23): the defect arm, `EntityKeyQuery` and `graphitron_accept_key_rule` each evaluate it today.

**The refusal.** The `KEY_FIELD_NOT_A_COLUMN` arm refuses a declared selection of an authored key that is resolvable (`COALESCE(k.resolvable, TRUE) = TRUE` on `graphitron_ast_federation_key_entry`, joined on the application's source coordinates as the `KEY_TYPE_NOT_TABLE_BOUND` arm does) and has no binding row. The exemption covers the whole arm, nested selections included: the arm's reason, that a row is found by matching the key's values against its table's columns, holds only for a key this subgraph resolves, and a nested selection fails it for the same reason a `@reference` field does. The arm keeps reading per-position absence from `graphitron_key_field_binding`, since its detail names the first field that does not bind. `KEY_FIELD_MISSING`, `KEY_SELECTS_NOTHING` and `KEY_ON_INTERFACE` are unchanged.

**The store reader.** `EntityKeyQuery.read` takes its keys from `graphitron_entity_key` rather than from every `@key` application, and the `resolvable` it reads is that relation's column, replacing its `coalesce(ke.RESOLVABLE, mk.RESOLVABLE, inline(true))`. Every `Direct` alternative is then complete by construction, so the `key.fields().isEmpty()` skip and `keySources`' "reads no column" throw go. Which types are entities is unchanged: the outer query's "carries a `@key` application, or is a node served by its id" stays, so an entity whose only key is a non-binding non-resolvable one is listed with no alternatives and `resolvable()` false, as an all-stub entity is today. `EntityTenantRouting.partnerOf` matches only resolvable store alternatives, since a walk slot is only ever resolvable.

**The accept key rule.** `graphitron_accept_key_rule` reads the candidate keys from `graphitron_entity_key` where `resolvable` is true, in place of its join to `graphitron_ast_federation_key_entry` and its two count comparisons against `graphitron_key_field_binding` standing in for "binds in full". What it decides is unchanged: a resolvable key that does not bind is refused, so it never appears there. The comment above `graphitron_accept_key` says "a field binding nothing is refused as a key", which stays true only of a resolvable key; it is reworded to say the keys come from `graphitron_entity_key`.

**The walk.** `EntityResolutionBuilder.build` skips a non-resolvable key whose fields do not all read columns, giving it no alternative and keeping the type; a resolvable key that does not resolve still drops the type, which only a refused graph can reach. Skipping shifts no index that crosses to the store: `TenantBindingIndex` numbers its slots in the walk's own list, `fromWalk` reads them back from that list, and the pairing to the store is by content. The class javadoc's "Refusals" paragraph is rewritten to match. `EntityTenantRouting.fromWalk` gains the enforcer whose absence would have hidden this: a resolvable store entity the walk has no entry for throws, naming the type, so the walk and the store can no longer disagree silently on which entities exist.

**Prose.**

- The diagnostic catalogue's message for `KEY_FIELD_NOT_A_COLUMN` (the `INSERT` into the catalogue in `graphitron-model.sql`) and the guidance fragment `graphitron-model/src/main/resources/no/sikt/graphitron/model/defects/KEY_FIELD_NOT_A_COLUMN.adoc` both say "a federation @key" with no exemption. Each becomes a resolvable key's rule, and the fragment's next step gains "or mark the key `resolvable: false` when another subgraph resolves the entity by it".
- The view's leading comment, its `COMMENT ON VIEW` and its `meta_relation` description row give `@key(fields: "titel")` as the `KEY_FIELD_NOT_A_COLUMN` example. In the fact document that name is undeclared and pinned as `KEY_FIELD_MISSING`, so the example is replaced with `@key(fields: "languageName")` on a film type whose `languageName` reads language's column through a `@reference` path, which the fact document pins. The leading comment's statement of the rule gains the exemption.
- The user manual gains the exemption in three places: the `KEY_FIELD_NOT_A_COLUMN` row in `docs/manual/reference/federation.adoc`, and in `docs/manual/how-to/federation-keys.adoc` the paragraph listing what cannot participate in a key and the "Keys must list columns of the type's own table only" bullet. The how-to's `resolvable: false` section gains one sentence saying why: this subgraph never looks a row up by such a key, it only serves the key's fields on the references it returns, which the router reads to build the representation it sends to the subgraph that resolves the entity.

The user-visible behaviour is a refusal relaxed, so its first-client docs are the manual edits above; no new directive or argument appears.

## Tests

- **The refusal, at the fact store.** `graphitron-model/src/test/resources/facts/defects/entity-key.graphqls` gains a type in the PersonProfil shape on sakila's film, a resolvable key over a column beside non-resolvable keys over a path field and a nested selection:

  ```graphql
  # The path field and the nested selection Film is refused for, in keys another subgraph resolves:
  # this one never looks a row up by them, so nothing is matched against film's columns.
  type FilmReference @table(name: "film")
      @key(fields: "filmId")
      @key(fields: "filmId languageName", resolvable: false)
      @key(fields: "language { name }", resolvable: false) {
    filmId: Int @field(name: "film_id")
    language: Language @reference(path: [{key: "film_language_id_fkey"}])
    languageName: String @field(name: "name") @reference(path: [{key: "film_language_id_fkey"}])
  }
  ```

  The defect claim is unchanged, so no row for `FilmReference` is the evidence. The `graphitron_key_field_binding` claim gains `FilmReference`'s rows for ordinals 0 and 1 at the `filmId` position, showing the bindings still state how each field binds whatever the key's fate. A new `graphitron_entity_key` claim lists the keys that bind in full, `FilmReference`'s ordinal 0 among them and neither of its non-resolvable keys.
- **The relation and the reader, on a generated graph.** `graphitron-model/src/test/resources/facts/scenarios/entity-key-alternatives.graphqls` gains a third key on its `Rental`, `@key(fields: "rentalId inventoryId customerEmail", resolvable: false)` with `customerEmail: String @field(name: "email") @reference(path: [{key: "rental_customer_id_fkey"}])`, whose binding fields equal the resolvable key 0's: the shape that made `partnerOf` collide. The scenario gains a `graphitron_entity_key` claim (every key there but this one, with `resolvable` resolved, the minted node keys included) and its `graphitron_key_field_binding` claim gains the new key's two rows. `EntityKeyQueryTest.aKeyOverColumnsPairsEachFieldWithItsColumnInKeyOrder` keeps asserting exactly `Key(0)` resolvable and `Key(1)` not, which now also says the new key contributes no alternative. `GraphitronTypeEntriesTest` and `VoucherRuntimeGateTest` read the same scenario and are re-run against it.
- **The walk.** `EntityResolutionBuilderTest` gains a case beside `keyNotResolvable_carriesResolvableFalseThrough`: a table type with a resolvable column key and a non-resolvable key over a `@reference` field gets an entity resolution holding the resolvable alternative alone. A unit test on `EntityTenantRouting.fromWalk` pins the new throw for a resolvable store entity the walk lacks.
- **End to end.** In `graphitron-sakila-example`'s `federated-schema.graphqls`, `City` gains `countryName: String @field(name: "COUNTRY") @reference(path: [{key: "city_country_id_fkey"}])` and `@key(fields: "cityId countryName", resolvable: false)`. `FederationEntitiesDispatchTest` gains a case where a `City` representation by `cityId` resolves its row, so the type is still an entity with its dispatch, and one where a representation carrying only the non-resolvable key's fields yields `null` with no SELECT, as `Language`'s does. `FederationBuildSmokeTest`'s served-SDL check asserts the non-resolvable key reaches `_service { sdl }`.

## Other solutions we've considered

- **Lift the arm and leave the readers.** The failure moves downstream and goes quiet, as the section above shows; rejected.
- **Decide "binds in full" in `EntityKeyQuery` at read time.** A `NOT EXISTS` over `graphitron_ast_federation_key_selection_entry` in the reader would work, but it would be the third place the predicate is spelled, beside the defect arm and the accept key rule's count comparison, which is what the fact model's stored-once ruling exists to stop.
- **Keep non-resolvable keys out of the alternatives altogether**, retiring `Alternative.resolvable()` and every emitter's skip. A node with `@key(fields: "id", resolvable: false)` today gets a non-resolvable node id alternative, and that alternative is what stops the synthetic resolvable one being added, so dropping it would make `Query.node` and `_entities` start resolving the node. The fact model rules `Query.node` and `_entities` separate lookups (2026-11-12), and untangling that double duty is its own item, not this one.

## Open questions for Alf and Filip

- `KEY_FIELD_MISSING` checks only a selection's first segment, so with the exemption the inner names of a non-resolvable nested key (`language { nmae }`) go unchecked by graphitron and are left to supergraph composition. The spec accepts that; say so if graphitron should check them.
- `resolvable` is re-derived in about nine places in two spellings: most read `COALESCE(k.resolvable, TRUE)` off `graphitron_ast_federation_key_entry`, while `graphitron_type_schema_reach_rule` and at least one other rule test the argument's `value_sdl = 'false'`. `graphitron_entity_key` is a natural home for the resolved flag; moving those readers onto it is left out of this item.
