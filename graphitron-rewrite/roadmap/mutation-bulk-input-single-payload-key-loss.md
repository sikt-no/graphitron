---
id: R138
title: "Reject or lift bulk-input + single-record-payload mutations that drop N-1 returned keys"
status: Spec
bucket: validation
priority: 3
theme: mutations-errors
depends-on: []
---

# Reject or lift bulk-input + single-record-payload mutations that drop N-1 returned keys

Surfaced in R134 (`mutation-empty-input-short-circuit-newrecord`, shipped at `36122dc` + `7fadbda`). R134 fixed the empty-input arm of the same shape; this item closes the non-empty arm, where the generated code commits N rows but the GraphQL response carries only the last row's PK. The bug is silent: no exception, no log line, no schema reject — the response simply lies about scope.

## Symptom

For the schema field

```graphql
type FilmPayload { film: Film }
input FilmCreateInput @table(name: "film") { title: String, languageId: Int }
type Mutation {
    createFilmsPayload(in: [FilmCreateInput!]!): FilmPayload @mutation(typeName: INSERT)
}
```

the classifier admits the field as `MutationField.MutationDmlRecordField` with `tableInputArg.list() == true` and `returnType.wrapper().isList() == false`. The emitter at `TypeFetcherGenerator.buildMutationDmlRecordFetcher` (`graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/TypeFetcherGenerator.java:3290-3365`) produces:

```java
Record1<Integer> payload = dsl.transactionResult(tx -> DSL.using(tx)
    .insertInto(film, FILM_ID, TITLE, LANGUAGE_ID)
    .valuesOfRows(in.stream().map(row -> DSL.row(...)).toList())
    .returningResult(FILM.FILM_ID)
    .fetchOne());
```

N rows commit to the DB. jOOQ's `.fetchOne()` keeps the last `RETURNING` row and discards the rest. The downstream data field's response SELECT (`SingleRecordTableField`) projects the single PK in `payload`, so the response carries `film` = the last input row and silently drops the other N-1.

## Why the existing guards miss it

- **`MutationInputResolver.validateReturnType`** (Invariants #14 + #15, at `graphitron/src/main/java/no/sikt/graphitron/rewrite/MutationInputResolver.java:153-219`) rejects `listInput + single-cardinality` on the `ScalarReturnType (ID)` and `TableBoundReturnType (T)` arms with "Invariant #15 / silent drop of all-but-last-row data". The `ResultReturnType` arm explicitly excludes #15 with a comment at lines 148-150 routing the case to "the deferred Payload+list rejection in `FieldBuilder#buildDmlField`".
- **`FieldBuilder.buildDmlField`** (`graphitron/src/main/java/no/sikt/graphitron/rewrite/FieldBuilder.java:2375`) rejects `listInput && returnType instanceof ResultReturnType` as `Rejection.deferred(..., "synthesize-payload-carrier")`. But R75 Phase 1 introduced a single-record-carrier classification path at `FieldBuilder.java:2664-2685` that constructs a `MutationDmlRecordField` and returns **before** the deferred-rejection check fires. When the carrier trigger resolves `SingleRecordCarrierResolution.Ok` (plain SDL Object with a `@table`-element data field — exactly `FilmPayload { film: Film }`), the guard is bypassed.

The existing `DML_INSERT_LIST_PAYLOAD_DEFERRED` test fixture in `GraphitronSchemaBuilderTest.java:5554` covers the `@record`-carrier variant (which has `fqClassName` set and does not go through R75's `NoBacking` promotion). The plain-SDL variant — the exact `createFilmsPayload(in: [FilmCreateInput!]!): FilmPayload` shape declared at `graphitron-sakila-example/src/main/resources/graphql/schema.graphqls:1183` for R134's compilation-tier regression — has no rejection coverage and no execution-tier coverage, and it generates code that drops data.

The comment exclusion at `MutationInputResolver.java:148-150` became a stale invariant claim when R75 Phase 1 added the bypass path. This item repairs that claim at the validator layer.

## Decision: reject at validate time (option a), not lift the emit shape (option b)

The Backlog body sketched two directions. (a) extends Invariant #15 to the `ResultReturnType` arm; (b) introduces a `MutationBulkDmlRecordField` permit with a list-shaped data field and a `.fetch()`-based emit.

**This item commits to (a).** Three reasons:

1. **Mirror principle.** Invariant #15 is sealed-root-uniform over the admitted return-type arms: when the `@table` input is list-shaped, the return must be list-shaped. The predicate is `listInput && !returnType.wrapper().isList()`. The classifier opened a new admitted arm (R75 Phase 1's `SingleRecordCarrierResolution.Ok` path); the validator should mirror across that arm by the same mechanism, not by routing to an out-of-band guard. "Validator mirrors classifier invariants" applies.
2. **No real schema wants the dropped-rows shape.** A mutation that takes N inputs and returns a single carrier with one row is a footgun by construction — the response cannot carry N rows, the DML wrote N, and there is no honest mapping. If a real schema *did* want "bulk INSERT, single domain payload" (e.g. a summary count, no row data), the right surface is a `@service` mutation; the DML emitter has nothing useful to do there.
3. **(b) is feature work, not a guardrail.** A bulk-carrier permit introduces a new sealed leaf (`MutationBulkDmlRecordField`?), a list-element data-field classifier, a `Result<...>`-keyed response-SELECT predicate (`PK in (source.stream().map(...).toList())`), and a load-bearing classifier check pairing the new data-field cardinality to the emit shape. That is a separate plan; conflating it with R138 muddles the audit trail.

A sibling Backlog item should be filed for (b) under a slug like `bulk-input-single-carrier-list-data-field` if and when a real schema needs it. R138 stays a pure validator-mirror-classifier fix.

## Implementation

### Validator change

In `MutationInputResolver.validateReturnType`:

- Lift the `listInput && !returnType.wrapper().isList()` predicate to apply over every admitted return-type arm. Cleanest encoding: factor the per-arm shape acceptance (Invariant #14 + payload-list + polymorphic-not-supported) into one helper, run it first, and apply the cardinality check uniformly after on the admitted arms. The redirect message generalises to "use a list-shaped return type to avoid silent drop of all-but-last-row data (Invariant #15)"; the author's own return-type name appears in the message so the redirect remains obvious.
- Implementer's choice: keep the cardinality check mirrored per-arm (three near-identical `if` blocks, one new on `ResultReturnType`) if preserving the per-arm "use [Film!]!" specificity is judged worth the duplication. Either encoding satisfies the principle; the lifted form mirrors the predicate's sealed-root uniformity, the per-arm form keeps the existing message strings exact. Recommended: lift.
- Delete the comment exclusion at `MutationInputResolver.java:148-150` ("The Payload arm is excluded from #15 ..."). The new code structure removes the exclusion; the doc claim is stale post-R75 and confuses any reader who reaches the line.

### Schema fixture removal

- Drop the `createFilmsPayload(in: [FilmCreateInput!]!): FilmPayload @mutation(typeName: INSERT)` declaration at `graphitron-sakila-example/src/main/resources/graphql/schema.graphqls:1183` (and the surrounding R134 explanatory comment block). The compilation-tier coverage that this field anchored (R134's `newRecord(...)` regression pin) is no longer reachable through this shape, because the validator now rejects it. Move the `newRecord(...)` regression coverage onto `createFilmPayload` (the single-input + single-payload variant at line 1171), whose empty-list short-circuit is unaffected by R134's fix because it never enters the bulk arm at all — but the `dataIsList = false` arm still exercises the `newRecord(...)` empty constructor when the `tia.list()` branch is not taken. Verify by reading the generated source for `createFilmPayload` in the sakila-example target; if the `newRecord(...)` path isn't exercised by the single-input variant, leave the fixture in place with the validator-rejection assertion-flip, or fold the regression onto a new dedicated `*Payload` field that does exercise it.

### Classifier-tier rejection coverage

Add a row to the truth table in `GraphitronSchemaBuilderTest`'s parameterised mutation-classification source, mirroring `DML_INSERT_LIST_SINGLE_T_REJECTED` (`GraphitronSchemaBuilderTest.java:5524-5537`):

```java
DML_INSERT_LIST_PLAIN_PAYLOAD_REJECTED(
    "DML INSERT with listed input + plain SDL Object payload return → UnclassifiedField (Invariant #15, R75 carrier-arm)",
    """
    type Film @table(name: "film") { title: String }
    type FilmPayload { film: Film }
    input FilmInput @table(name: "film") { title: String }
    type Query { x: String }
    type Mutation { createFilmsPayload(in: [FilmInput!]!): FilmPayload @mutation(typeName: INSERT) }
    """,
    schema -> {
        var f = (UnclassifiedField) schema.field("Mutation", "createFilmsPayload");
        assertThat(f.reason())
            .contains("must return a list")
            .contains("Invariant #15");
    }),
```

The fixture's diagnostic value is the contrast with `DML_INSERT_LIST_PAYLOAD_DEFERRED` (line 5554): that row uses an `@record`-carrier payload and lands in the deferred-rejection arm; this row uses a plain SDL Object and lands in the validator-rejection arm. Both shapes are rejected, but for different reasons surfaced at different layers. Keeping both rows in the truth table documents the split.

## Tests

- **L3 (classifier).** `GraphitronSchemaBuilderTest.DML_INSERT_LIST_PLAIN_PAYLOAD_REJECTED` row above. UPDATE and UPSERT variants share the same input + return shape; sibling rows for those two kinds are worth adding for completeness, but a single INSERT row demonstrates the validator change is correct. Implementer's call on whether to enumerate all three kinds.
- **L6 (execution), pre-fix only.** Add a pin-the-bug test in `SingleRecordCarrierDmlTest` (or a sibling file) that exercises the current broken behaviour against PostgreSQL:

  ```java
  @Test
  void createFilmsPayload_silentlyDropsAllButLastRow_R138_PRE_FIX() {
      // Documents the R138 bug: bulk-input + single-record-payload mutation classifies
      // successfully (MutationDmlRecordField with tia.list()=true, dataIsList=false).
      // The emitted body runs valuesOfRows(...).returningResult(...).fetchOne(), which
      // jOOQ resolves to "last row only" — N-1 rows commit to the DB but only the
      // last row's PK flows back into the response payload's film data field.
      String m1 = randomMarker("R138-A");
      String m2 = randomMarker("R138-B");
      String m3 = randomMarker("R138-C");
      try {
          Map<String, Object> data = execute("""
              mutation {
                  createFilmsPayload(in: [
                      { title: "%s", languageId: 1 },
                      { title: "%s", languageId: 1 },
                      { title: "%s", languageId: 1 }
                  ]) { film { title } }
              }
              """.formatted(m1, m2, m3));

          long dbCount = dsl.fetchCount(DSL.table("film"),
              DSL.field("title", String.class).in(m1, m2, m3));
          assertThat(dbCount)
              .as("all three rows commit to the DB")
              .isEqualTo(3);

          Map<String, Object> payload = (Map<String, Object>) data.get("createFilmsPayload");
          Map<String, Object> row = (Map<String, Object>) payload.get("film");
          assertThat(row.get("title"))
              .as("but only the last row's PK flowed to the response; m1 and m2 are dropped")
              .isEqualTo(m3);
      } finally {
          dsl.deleteFrom(DSL.table("film"))
              .where(DSL.field("title").in(m1, m2, m3)).execute();
      }
  }
  ```

  This test asserts on row counts at the GraphQL/DB boundary, no generated-source body strings. It exists *only* to demonstrate the hole; once the implementation lands, the schema field is removed and the test is deleted in the same commit. The replacement is the classifier truth-table row above.

  The test could also be filed as a pre-implementation reproducer commit on the `claude/r138-execution-fix-bBLh4` branch ahead of the validator change, so a fresh reviewer can run it against `main`, observe the silent drop, then re-run after the rejection lands and observe the schema-validation rejection. Implementer's call on whether to land the pre-fix test in a separate commit or fold it into the implementation commit's `git revert`-able history.

## Acceptance criteria

- `MutationInputResolver.validateReturnType` rejects `listInput && !returnType.wrapper().isList()` for every admitted return-type arm, including `ResultReturnType`. The Invariant #15 rejection message contains the strings `"must return a list"` and `"Invariant #15"` consistent with the existing `[ID!]!` / `[T!]!` rejections.
- The comment exclusion at `MutationInputResolver.java:148-150` is deleted; the corresponding doc claim in `validateReturnType`'s Javadoc (lines 136-152) is updated to reflect uniform #15 coverage across the three admitted arms (`ScalarReturnType(ID)`, `TableBoundReturnType`, `ResultReturnType`).
- `graphitron-sakila-example/src/main/resources/graphql/schema.graphqls` no longer declares `createFilmsPayload(in: [FilmCreateInput!]!): FilmPayload`. The R134 `newRecord(...)` compilation-tier regression coverage is verified to still hold via the single-input `createFilmPayload` field (or another dedicated fixture) — `mvn install -Plocal-db` passes and the generated source for `createFilmPayload` exercises the `dataIsList = false` empty-arm `newRecord(...)` path.
- `GraphitronSchemaBuilderTest` carries a `DML_INSERT_LIST_PLAIN_PAYLOAD_REJECTED` row covering the plain-SDL payload variant; the row's assertion message names the validator-tier rejection (`"Invariant #15"`), not the deferred-guard message used by `DML_INSERT_LIST_PAYLOAD_DEFERRED`.
- No execution-tier test asserts the dropped-rows behaviour after the fix lands. If a pre-fix pin-the-bug test was added, it is deleted in the same commit as the validator change.
- `mvn -f graphitron-rewrite/pom.xml install -Plocal-db` passes end-to-end with the schema fixture removed and the new classifier-tier coverage in place.

## Roadmap entries (siblings / dependencies)

- **Follow-up from** [R134 / `mutation-empty-input-short-circuit-newrecord.md`](changelog.md): R134 fixed the empty-list arm of the same shape (`newRecord(...)` vs `newResult(...)`); R138 closes the non-empty arm by rejecting the shape at classify time so the broken emit path is no longer reachable.
- **Mirrors** [`DML_INSERT_LIST_SINGLE_T_REJECTED`](../graphitron/src/test/java/no/sikt/graphitron/rewrite/GraphitronSchemaBuilderTest.java) and `DML_INSERT_LIST_SINGLE_ID_REJECTED`: same Invariant #15 mechanism, extended to the third admitted return arm.
- **Defers** "bulk DML with a single carrier wrapping a list-shaped data field" (`.fetch()`-based emit, new `MutationBulkDmlRecordField`-style permit, list-element data-field classifier) to a future Backlog item. File under a slug like `bulk-input-single-carrier-list-data-field` if a real schema surfaces a need for it; the principles call here is that R75 Phase 1's surface is "single-record carrier" and bulk-carrier is the next plan, not this one.
