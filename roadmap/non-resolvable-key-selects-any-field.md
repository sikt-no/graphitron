---
id: R1210
title: "A federation key declared resolvable: false may select a field read through a reference"
status: Backlog
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

## What we know

- The refusal is one arm of the view `graphitron_entity_key_entry_defect_rule` in `graphitron-model.sql`. The arm refuses a key selection that has no row in `graphitron_key_field_binding` (one row per key field that binds at the entity's own table: a column, the node's own id, or an `@accept` voucher), and never reads the key's `resolvable` flag.
- The `KEY_TYPE_NOT_TABLE_BOUND` arm of the same view already exempts a non-resolvable key, through `COALESCE(k.resolvable, TRUE) = TRUE` on `graphitron_ast_federation_key_entry`. That is the precedent for the same exemption here.
- The fact document's description states the rule as one about "a resolvable @key", but pins no non-resolvable key on a table-bound type, so the missing exemption reads as an oversight rather than a decision.
- Open for the spec: whether the readers of `graphitron_key_field_binding` (`EntityKeyAlternatives` in graphitron-model, `EntityKeyQuery` in graphitron) or the generated entity dispatch assume every field of every key on an entity binds, so that lifting the refusal moves the failure downstream instead of removing it. A non-resolvable key should draw no `_entities` lookup at all; the spec confirms that each reader already keeps off it, or makes it.

## Surfaces that change

- The `KEY_FIELD_NOT_A_COLUMN` arm of `graphitron_entity_key_entry_defect_rule`, and the diagnostic catalogue's message for the code, which states the rule with no exemption. The view's comment gives `@key(fields: "titel")` as its `KEY_FIELD_NOT_A_COLUMN` example, a name the fact document's `Film` does not declare and so pins as `KEY_FIELD_MISSING`; check whether the comment means a declared field with no column, and say so if it does.
- The fact document gains the accepted near miss.
- The user manual states the rule with no exemption in three places, each of which gains it: the `KEY_FIELD_NOT_A_COLUMN` row in `docs/manual/reference/federation.adoc`, and in `docs/manual/how-to/federation-keys.adoc` the paragraph listing what cannot participate in a key and the "Keys must list columns of the type's own table only" bullet.
