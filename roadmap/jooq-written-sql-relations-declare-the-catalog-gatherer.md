---
id: R960
title: "Fourteen sql_ relations declare the catalog gatherer while JooqFactCapture writes them"
status: Backlog
bucket: cleanup
priority: 5
theme: model-cleanup
depends-on: []
created: 2026-09-18
last-updated: 2026-09-18
---

# Fourteen sql_ relations declare the catalog gatherer while JooqFactCapture writes them

## Goal

`meta_relation.owner_name` names the gatherer that writes a relation, and for fourteen of the
fifteen declared `sql_` relations it names `catalog` while `JooqFactCapture` is what writes them.
When this lands, a declared owner in the `sql_` family is the gatherer that actually fills the
relation, so a reader who asks the register who owns a row gets an answer the code agrees with.

The mismatch is old and harmless today, because nothing gates a declared owner against its writer.
It stops being harmless the moment something does: `CatalogFactCapture`'s own javadoc records that
it "used to fill the `sql_` family too, and that half is gone", so the declarations are a record of
where those stages used to live rather than of where they are.

Two relations are not part of the sweep and both are worth naming so the correction does not
overreach. `sql_name_matched_key_column` declares `catalog` correctly: `NameMatchedKeys.derive` is
called from `FactCapture.capture` as, in that method's own comment, "a stage of the catalog gatherer
rather than a derivation". And `sql_table_reference` already declares `jooq`, that being the first
relation declared by its writer rather than by its family.

## Tests

`MetaDeclarationGateTest` is where the change is visible; whether a gate comparing a declared owner
against the class that writes the relation is worth minting here, or belongs with the ownership work
R877 carries, is the first question this item answers rather than a decision it inherits.

## Provenance

Named by R954's phase-0 body, which declared `sql_table_reference` as `jooq` and said to "file the
fourteen as a Backlog item rather than correcting them here: they are a pre-existing declaration
defect this item happens to surface". That filing did not happen while R954 was in flight, and the
item's Done gate filed it instead.
