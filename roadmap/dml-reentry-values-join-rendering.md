---
id: R489
title: "Normalize the DML reentry correlation onto the VALUES-join primitive"
status: In Progress
bucket: architecture
priority: 5
theme: classification-model
depends-on: []
created: 2026-07-16
last-updated: 2026-07-21
---

# Normalize the DML reentry correlation onto the VALUES-join primitive

## Review feedback (In Review -> Ready, 2026-07-21) — addressed

The one blocking finding (the classify-time PK-less rejection in
`FieldBuilder.buildDmlField` had no enforcer, while the `DmlReturnExpression` arms' non-null
correlation javadoc leaned on it) is closed by two model-tier rejection cases in
`GraphitronSchemaBuilderTest` (`DML_TABLE_RETURN_PKLESS_TABLE_REJECTED` and its list-cardinality
sibling): a `@mutation` returning a type bound to `film_list` (the fixture catalog's no-PK
table) classifies to `UnclassifiedField`, with the message asserted to name the table, the
missing primary key, and the return-ID remedy. Everything else stands as shipped; the next
In Review pass only needs to verify the rejection cases and re-run the build.

## In one paragraph

R314 named the DML reentry unit (the `rows<Name>` companion holding the projected / discriminated
mutation's follow-up SELECT) but kept its correlation rendering as recorded residue: a keys-IN
condition, where R333 names one primitive for every keyed re-query (a `VALUES(idx, key...)` derived
table joined over a correlation, PK self-identity as the degenerate case). This item normalized the
companion onto the primitive and converted two pieces of undefined bulk behavior into a deliberate,
execution-tested contract: payload rows align one-to-one, in order, with the rows the write
reported through RETURNING.

## Shipped

Everything below is implemented; nothing is pending beyond review.

- **Carried correlation.** The classifier attaches the PK-self-identity
  `ParentCorrelation.OnLiftedSlots` fact (over the bound table's primary key) to the four reentry
  return-shape arms: `DmlReturnExpression.ProjectedSingle` / `ProjectedList` /
  `DiscriminatedSingle` / `DiscriminatedList` now carry a `reentryCorrelation` component,
  constructed once in `FieldBuilder.buildDmlReturnExpression`. `emitProjected` /
  `emitDiscriminated` read the carried fact; no emit site derives the key column set.
- **VALUES-join rendering.** The bulk companion body renders through the shared primitive: a
  `keyRows` typed row array plus `keysInput` derived table built through `ValuesJoinRowBuilder`
  (`TypeFetcherGenerator.emitReentryValuesJoinDecls`), joined to the target over the correlation
  (`buildReentryValuesJoinOn`), with `ORDER BY idx` (`reentryIdxField`). The single arm renders
  the legible degenerate, plain key equality (`buildReentryKeyEquality`), byte-identical to the
  retired spelling. The discriminated bulk arm threads the same primitive through
  `buildTableInterfaceReprojection` with the discriminator filter starting from `noCondition()`.
- **Helper retirement.** `buildPkKeysCondition` deleted; `buildKeysInCondition` narrowed to the
  routine-write step-2 re-read, its javadoc naming that caller as the sole sanctioned one.
- **Validator.** `GraphitronSchemaValidator.validateDmlReentryKeyArity` (wired from the INSERT /
  UPDATE / UPSERT DML validators) rejects a list-cardinality reentry key above 21 columns at
  validate time, mirroring `ValuesJoinRowBuilder`'s Row22 cap; single arms are exempt (no idx
  slot).
- **Registry untouched.** The `rows<Name>` unit's identity is unchanged; `MethodCommandRegistry`
  and `ReentryCommandClosureTest` are untouched.
- **Tests.** Execution tier (`DmlBulkMutationsExecutionTest`): `createFilms` order pin tightened
  to `containsExactly`; a new `createKeyedNodes` fixture (client-supplied PK, input ordered
  against the key's natural order, over a new `createKeyedNodes` schema surface) as the
  discriminating order proof; a missed-row fixture pinning payload cardinality = written rows;
  and a direct companion-seam call (`rowsUpdateFilms` with a hand-built duplicate-keys `Result`)
  pinning row-per-write and keys-order alignment. Discriminated bulk order + `__typename` routing
  in `DmlTableInterfaceReturnExecutionTest` over a new `createContents` surface. Pipeline tier
  (`FetcherPipelineTest`): bulk companion renders the VALUES join ordered by idx with no keys-IN;
  single companion keeps plain equality. Model tier: `GraphitronSchemaBuilderTest` /
  `MutationDmlNodeIdClassificationTest` assert the carried correlation. Validation tier:
  `MutationInsertTableFieldValidationTest` gained the Row22-cap rejection and single-arm
  exemption cases.
- **Docs.** `reference/directives/mutation.adoc` rewritten to the shipped two-step emit
  (reconciling the pre-existing WITH-wrap one-round-trip drift) and gained the ordering /
  cardinality contract sentence; `tutorial/05-mutations.adoc` mechanics passage and SQL snippets
  updated to the two-step shape. `how-to/split-vs-inline.adoc` untouched (read-side page, still
  accurate). Stale single-round-trip comments in `GraphQLQueryTest` refreshed.

## Deviations from the Ready spec

Recorded for the In Review pass; none changes the design's shape.

- **Carrier choice.** The spec left the carrier as a bounded fork (`MutationField.DmlTableField`
  or the reentry-side fact); the correlation landed on the `DmlReturnExpression` reentry arms,
  which is where the reentry fork already lives (`Encoded*` arms carry nothing).
- **PK-less table-bound returns now reject at classify time.** A non-null carried correlation
  requires a non-empty key tuple, so `FieldBuilder.buildDmlField` rejects a `@table` return whose
  table has no primary key (typed AuthorError naming the fix). This replaces `emitProjected`'s
  fallback branch (a single-round-trip `returningResult($fields)` emit whose guarding rejection
  the fallback's own comment claimed already existed but did not); no fixture, corpus schema, or
  sakila surface exercised the fallback.
- **Duplicate-write fixture is a direct companion call.** The GraphQL surfaces cannot produce
  duplicate RETURNING keys (bulk UPDATE's duplicate-tuple guard fires before SQL; INSERT never
  repeats a PK; PostgreSQL reports each target row at most once through RETURNING), so the
  row-per-write pin calls `rowsUpdateFilms(keys, env)` directly with a hand-built duplicate-keys
  `Result` and a synthetic environment, the independently-assertable seam framing the companion's
  javadoc already documents.

## The contract (as shipped)

Bulk projected / discriminated mutation payloads align one-to-one, in order, with the rows the
write reported through RETURNING: the companion orders by `idx`, where `idx` indexes the RETURNING
result, and emits one payload row per written row (row-per-write, not row-per-distinct-key).
Single-row arms are behavior-identical to the prior emit. The execution-tier fixtures are the
enforcer; the user-manual sentence ("one entry per written row, in the order the rows were
written") describes what they pin.

## Out of scope (unchanged)

- The routine-write step-2 re-read stays keys-IN until `Operation.RoutineWrite` joins the reentry
  family; if that framing changes, its re-read joins this unit and the surviving keys-IN helper
  retires with it.
- Scatter on the DML companion (arrival-gated; never fires on the single-anchor case).
- Any RETURNING semantics change; `emitKeysTransaction` is untouched.

## Roadmap ripple

On Done: update the R314 shipped-note in R333 (`coordinate-lowers-to-datafetcher-queryparts.md`),
whose residue sentence names this item as the owner of the keys-IN vs VALUES-join normalization,
and record the landing in `changelog.md` per the Done convention.
