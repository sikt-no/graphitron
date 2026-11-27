---
id: R1213
title: "An input field with @reference and its own @condition(override: true) is rejected unless it names a column the override never uses"
status: Backlog
bucket: bug
priority: 3
theme: classification-model
depends-on: []
created: 2026-11-27
last-updated: 2026-11-27
---

# An input field with @reference and its own @condition(override: true) is rejected unless it names a column the override never uses

## Goal

An input field that carries a `@reference` path and its own `@condition(override: true)` builds without naming a column, and its condition method still receives the path's last table, correlated to the row through the path, exactly as it does today when a column is named. The override suppresses the implicit `column = value` predicate, so the column the build demands today is never read; consumers name a placeholder column to get past the check, and the placeholder misleads every later reader. A column miss without the override stays rejected as today.

The shape, from the sis port (input consumed by `Query.evuKursSoknader`, whose table is `KURSSOKNAD`):

```graphql
input QueryEvuKurssoknaderFilterInput {
  personenErDod: Boolean @reference(path: [{table: "DELTAKER"}, {table: "PERSON"}])
    @condition(condition: {className: "no.fellesstudentsystem.kjerneapi_service.conditions.PersonConditions",
                           method: "personErDodFilterCondition"}, override: true)
}
```

```java
public static Condition personErDodFilterCondition(Person person, Boolean personenErDod)
```

When this lands the field builds as written, and the generated condition is the one the same field produces today with `@field(name: "STATUS_DOD")` added: a correlated `EXISTS` over `DELTAKER` and `PERSON` that hands the method the `PERSON` alias.

## Reported by the sis port, on RC58 (`f6e16dd53`)

Relayed by the sis porting session on 2026-11-27, verified there by a build; not re-run here.

* The build refuses the field:
  ```
  Input field 'QueryEvuKurssoknaderFilterInput.personenErDod': no column 'personenErDod' reachable via @reference path; did you mean: PERSONNR, PERSON_ALDER, STATUS_DOD, PLNR, KJONN
  Field 'Query.evuKursSoknader': argument 'filter': plain input type 'QueryEvuKurssoknaderFilterInput' against table 'KURSSOKNAD': 1 input field could not be resolved
  ```
* Naming any column of `PERSON` through `@field(name:)` passes, and the generated code is the same whichever column is named; none of the named columns appears in the generated condition code.
* sis carries 101 such placeholder declarations across 8 distinct fields. Because the name is neither type-checked nor meaning-checked, they mislead: `Boolean` fields name `String` columns (`EMAILADRESSE`, `UTDREGID_SPESIFIKASJON`), `undervisningsenhetErApen` names `STATUS_LUKKET`, and `personErDodFilterCondition` actually reads `STATUS_DOD` on another table.

## Read on trunk at `bb5c79d15`

The *walk* is the older classifier that turns each schema field into a model carrier; its refusals reach the author as validation errors. On trunk it still refuses this shape for the same reason.

* `BuildContext.classifyInputField`, in its `@reference` arm, resolves the column through `ServiceCatalog.resolveColumnForReference` and on a miss returns `Rejection.unknownColumn` ("no column '...' reachable via @reference path"). It does not look at the field's override.
* Its two neighbours do. An input field without `@reference` that misses a column under its own override is lifted to `InputField.ConditionOwnedField` (further down in `classifyInputField`). A direct argument with `@reference` that misses under its own override becomes `ArgumentRef.ScalarArg.ConditionOwnedArg`, through `FieldBuilder.ownedByOverride`.
* When the column resolves, the arm mints `InputField.ColumnBackedReferenceField` with the path and a `FilterBinding.Remote`, and the walk's filter assembly in `FieldBuilder` wraps its condition in an `FkTargetConditionFilter` carrying the path's target table. That wrap is what hands the method the terminal table rather than the row's own table.

## Open

* **Where the condition is rendered now.** Since `f34cef0` (after RC58) the filter conditions are emitted from the *store*, the fact database a build captures a schema into, through `ConditionEmitter`; the walk no longer renders condition code. The fix therefore owes both judges: the walk's admission, and a store-side derivation of the correlated `EXISTS` for a reference field that has a path but no column. How the store treats this shape today has not been read.
* **The reporter's suggested direction, not yet reviewed.** Keep minting a reference carrier on a column miss under override, with the path and the `Remote` binding but no key column, rather than lifting to `ConditionOwnedField`. Lifting would drop the path, and the method would then be handed `KURSSOKNAD`: a compile failure for a typed first parameter, and silently the wrong table for `Table<?>`. Whether the carrier is the right seam now that emission is store-side is for the Spec.
* **The argument side may drop the path too (unverified).** `ConditionOwnedArg` carries no path, so a direct argument with `@reference` and a column miss under override plausibly hands its method the row's own table. Read from code only; sis has no argument of this shape. The Spec confirms it with a test and either folds the fix in or splits it off.

## Neighbours

* R1139 admits an argument that matches no column under a *field-level* override. Same principle (an override owns the predicate, so the column is not needed), different coordinate; the two should share one mechanism.
* R1092 is the non-override sibling: an input field's `@condition` without override drops the implicit predicate it demands a column for.
* R1205 argues that under override a route to a `@nodeId` table serves nothing and should not be demanded. That does not contradict this item: there the method writes its own join, while here the `@reference` path is what selects the table the method is handed, so the path stays and only the terminal column goes.

## What a fix owes

* The repro builds without `@field`, and its generated code equals the `@field(name: "STATUS_DOD")` version's.
* A column miss without override is still rejected, with today's message.
* A direct argument with `@reference`, override, and a column miss: a test that the method receives the path's terminal table, and a fix if the unverified observation above holds.
* `docs/manual/how-to/condition-cascade.adoc` says a field that resolves on no column of a consumer's table fails the build (twice), and `docs/architecture/reference/argument-resolution.adoc` says the same; both gain the override exemption for a field with a path.
