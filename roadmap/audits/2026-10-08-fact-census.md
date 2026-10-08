# What the store states of R333's facts, and what the walk rejects on each

Taken 2026-10-08 against the walk as it stood on `claude/r876--unmaterialize` that day, for the R876
node "What the store states of R333's facts is counted". A read of R333's fact catalog, the store
schema and the walk; nothing run. Per fact rather than per leaf, R333 calling a leaf a denormalized
view over the facts.

For each fact: whether store relations state it, and the walk's rejections on it grouped into rules,
each with its basis in the defect model's terms (`SPECIFICATION`, `GRAPHITRON`, `UNSUPPORTED`).

## How it was counted

465 rejection calls in `graphitron/src/main/java/.../rewrite/`: 355 `structural`, 39 `deferred`, 29
`invalidSchema`, 16 `directiveConflict` and 26 named factories. Each was assigned to exactly one
fact, or to one unmapped group, and a mechanical diff confirmed the assignment is total. A handful of
rejections constructed directly rather than through a factory are named under their fact and not
counted. `rejection_validation_error` holds all of them as a transcription and is not counted as
coverage anywhere.

## Per fact

| fact | store states it | walk sites | rules | codes already in `graphitron_defect_type` |
|---|---|---|---|---|
| `operation` | partial: every member decoded, no operation relation, no address | 170 | 33 | `READ_SURFACE_ON_WRITE`, `CONNECTION_RETURN`, `MULTIPLE_ROUTINE_NODES`, the three `CODE_REFERENCE_*` |
| `joinPath` | partial: field-site hops whole; argument-site hops `intent_` only; the lift arm only as `graphitron_ast_source_row_entry` | 55 | 8 | `ROUTE_AMBIGUOUS`, `ELEMENT_UNRESOLVED`, `NO_ROUTE_FROM_DEPARTURE` |
| `node` | partial: types, keys and input decode stated; output projection, encode and slot `intent_` only | 53 | 9 | the four `NODE_*`, plus five `intent_*_defect` views |
| `tableExpr` | partial: the routine arm's bindings stated, column mapping as pairs | 24 | 5 | `MULTIPLE_ROUTINE_NODES`, `TABLE_NAMES_ROUTINE` |
| `sourceObject` | partial: the table-bound arm stated; the backing class `intent_type_backing` only | 23 | 5 | `TABLE_UNRESOLVED`, `TABLE_AMBIGUOUS`, `TABLE_NAMES_ROUTINE` |
| `reference` | partial: authored arm whole; inferred arm `intent_` only; no coalesced relation | 20 | 8 | none |
| `accessor` | partial: argument and input columns stated; output column `intent_column_match_claim` only | 19 | 5 | `COLUMN_UNRESOLVED`, `CODE_REFERENCE_*` |
| `errorGuard` | `intent_` only, no handler set | 16 | 5 | none |
| `discrimination` | partial: signals as written only, no resolved signal or partition | 9 | 6 | none |
| `source` | none: no relation states Root, OnlyChild or Child | 7 | 3 | none |
| `referencedTable` | partial: `graphitron_field_table` per table field, not iff a reference | 6 | 2 | `NO_ROUTE_TO_TARGET`, `CHAIN_WITHOUT_TARGET` |
| `target` | partial: the wrapper total, the shape unstated | 5 | 3 | none |
| `enum` | partial: value set and runtime value as written only | 3 | 2 | none |
| `resolvedTable` | total where one table resolves, as `graphitron_field_column_scope` | 1 | 1 | none |
| `sourceLocation` | partial by design, absent for built-ins | 0 | 0 | none |
| `authoredClaim` | `intent_` only | 0 | 0 | `intent_authored_claim_conflict` |
| `inferredClaim` | `intent_` only, one classifier of many | 0 | 0 | none |

`operation` by member: `serviceCall` 52 sites, DML 47, `condition` 28, lookup 16, `paginate` 12,
`orderBy` 10, `select` 5.

## Totals

- 411 of 465 sites are constraints on some fact, in about 95 distinct rules.
- About 40 are `UNSUPPORTED`, all 39 `deferred` among them, and about 5 are near `SPECIFICATION`, a
  minted name or a field duplicated. The rest are `GRAPHITRON`: a schema graphql-java refuses never
  reaches the walk, its verdict being `graphql_schema_problem`'s.
- About 15 of the 95 rules have a code already.

## What belongs to no fact: 54 sites

| group | sites | what it means for the model |
|---|---|---|
| consequential, restating another rejection | 12 | goes with the walk; a violation sits on the fact that broke |
| generator-internal: launcher and projection-unit names, a field classified twice | 7 | name collisions are `GRAPHITRON` rules on the generated namespace; the rest are walk bugs, not verdicts |
| `@scalarType` binding | 9 | R333 names no scalar fact; the store already has codes for it |
| tenancy | 9 | R333 names no tenancy fact |
| federation `@key` and `@link` | 7 | R333 names no federation fact |
| retired directives | 7 | a directive the vocabulary no longer means; one rule, not seven |
| recursion during expansion | 2 | `UNSUPPORTED` |
| a reference to a graphitron-internal support type | 1 | `GRAPHITRON` |

R333 says its catalog is closed, every active directive's effect owned by a fact. Scalars, tenancy
and federation are directives whose effect no fact in the catalog owns, so the catalog is not closed
over them. That is R333's to settle and nothing waits on it: the walk rejects on these directives, and
their rules move with the rest as rules on the relations that state them.

## What it says

- The facts are mostly stated, and where a fact is not, the shortfall is the arm `intent_` still
  holds: backing classes, the inferred reference, output columns, encode, error channels. Those
  move to the `graphitron_` family with the rest of `intent_`, and their constraints with them.
- `source` and `target`'s shape are the two facts no relation states, and both are total. They are
  the first facts to store, since totality is what the defect model's gate checks.
- `operation` is the bulk of the rules, 170 sites, and it has no relation of its own. Its members are
  decoded separately, so its constraints can move member by member.
