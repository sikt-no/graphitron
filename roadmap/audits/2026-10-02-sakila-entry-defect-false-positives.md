# What the entry defects report on the sakila example that the generator does not

Observed 2026-10-02 by a try that made every error-severity `graphitron_entry_defect` row a build
error, through `StoreDetections`, on trunk-side `3330b8206`, and then reverted it. Run as a full
reactor build with test failures ignored, so every module reported.

The sakila example is the reactor's own consumer: it generates, compiles and passes its tests
today. Under the try it reported 44 errors, every one a coordinate the generator accepts. The same
readings fail `MethodClosureOracleTest` (`FilmDetails.actorsByLookup`, `NO_ROUTE_FROM_DEPARTURE`),
`IncrementalCompileHarnessTest` and five `CorpusFragmentTest` documents, which generate from
fixtures of the same shapes. `DetectionReadReachGateTest` failed on its roster only.

This is the in-tree reproduction of `2026-10-01-sis-entry-defect-false-positives.md`. Three of
these codes are classes that audit names, and its mechanisms were not re-derived here. Two are new:
`NO_ROUTE_TO_TARGET`, which sis did not show, and `CODE_REFERENCE_METHOD_AMBIGUOUS`, which did not
exist when sis was run.

What the try could not show is which walk rejections duplicate which codes: the false positives
failed every build that would have listed both.

## Counts

| code | count |
|---|---|
| `NO_ROUTE_FROM_DEPARTURE` | 11 |
| `CHAIN_WITHOUT_TARGET` | 19 |
| `NO_ROUTE_TO_TARGET` | 8 |
| `ELEMENT_UNRESOLVED` | 4 |
| `CODE_REFERENCE_METHOD_AMBIGUOUS` | 2 |

## The two new classes

* **`CODE_REFERENCE_METHOD_AMBIGUOUS`** on `Query.occupantsByTypedNamePrefix` and
  `Query.occupantsByMixedNamePrefix`. Each `@condition` names an overload set in
  `MultiTableConditionFixtures`, one declaration per participant table, and javac picks each
  branch's own declaration, which the schema's comments state as the feature. A condition reference
  names a set of methods by design, so a reference matching several is not a fault at a condition
  site; `graphitron_code_reference` keeping only a single match is wrong there.
* **`NO_ROUTE_TO_TARGET`**, eight coordinates. Not examined; the list is below.

## Every row

By code, then coordinate, with the line in `graphitron-sakila-example/src/main/resources/graphql/schema.graphqls`.

### `NO_ROUTE_FROM_DEPARTURE`

* `CustomerAddressSummary.address`, line 1369
* `FilmDetails.actorsByLookup`, line 2239
* `FilmDetails.language`, line 2232
* `FilmDetailsCarrier.actorsByLookup`, line 2265
* `FilmDetailsCarrier.language`, line 2263
* `FilmInfo.cast`, line 2780
* `FilmInfo.castByKey`, line 2788
* `FilmInlineBundle.actorsByKey`, line 2840
* `FilmInlineBundle.actorsByKeyViaJunctionCondition`, line 2852
* `FilmInlineBundle.language`, line 2829
* `FilmInlineBundle.languageFiltered`, line 2838

### `CHAIN_WITHOUT_TARGET`

* `AppAccount.displayName`, line 3679
* `Company.displayName`, line 3710
* `Customer.addressNodeId`, line 1603
* `Customer.districtByCondition`, line 1638
* `FanAlpha.note`, line 3601
* `Film.languageName`, line 2376
* `Film.prices`, line 2349
* `Film.pricesSplit`, line 2352
* `Film.soleActorFirstName`, line 2326
* `Film.taglineTranslations`, line 2346
* `Film.titleTranslations`, line 2340
* `Film.titleTranslationsSplit`, line 2343
* `FilmActor.noteTranslations`, line 2888
* `FilmContent.rating`, line 3737
* `FilmProjectedLeaves.languageName`, line 2805
* `FilmProjectedLeavesInner.languageName`, line 2816
* `Individual.displayName`, line 3704
* `OccupantLocation.occupantDistrict`, line 1871
* `Person.displayName`, line 3686

### `NO_ROUTE_TO_TARGET`

* `Category.childRefs`, line 2214
* `Category.parentRef`, line 2221
* `DivergedRefChild.polyOrg`, line 2053
* `DivergedRefOrg.polyChildren`, line 2083
* `Film.firstMemberViaJunction`, line 2645
* `Film.membersViaJunction`, line 2647
* `Film.membersViaJunctionConnection`, line 2649
* `Store.contact`, line 1831

### `ELEMENT_UNRESOLVED`

* `Customer.addressByCondition`, line 1644
* `Film.actorsByCondition`, line 2522
* `Film.castByCondition`, line 2660
* `Film.firstByCondition`, line 2658

### `CODE_REFERENCE_METHOD_AMBIGUOUS`

* `Query.occupantsByMixedNamePrefix`, line 1033
* `Query.occupantsByTypedNamePrefix`, line 1025
