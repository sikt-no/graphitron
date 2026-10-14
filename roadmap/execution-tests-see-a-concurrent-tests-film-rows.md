---
id: R1028
title: "An execution test sees another test's transient film rows under parallel test order"
status: Backlog
bucket: bug
priority: 5
theme: testing
depends-on: []
created: 2026-10-14
last-updated: 2026-10-14
---

# An execution test sees another test's transient film rows under parallel test order

## Goal

The `graphitron-sakila-example` execution tier passes regardless of which tests happen to run beside each other. Today `GraphQLQueryTest.inputFieldCondition_customScalarDefaults_bindAsBigDecimalBounds` fails intermittently: it expects `filmsByRentalRateRangeDefaulted` to return `ACE GOLDFINGER` alone, and gets `["ACE GOLDFINGER", "renamed"]`. The extra row is one `NestedInputDmlExecutionTest.updateNested_honorsAbsentVsNullVsValuePerLeaf` inserts at sakila's default `rental_rate` of 4.99 and renames to `renamed`. That test deletes the row in its `finally`, so the failure needs the two tests to overlap, which the reactor's parallel test execution (`mvnd`, CI's `-T 1C`) allows. It failed in two of three full `mvn install -Plocal-db` runs in one session on 2026-10-13/14, and passed in isolation and on a resumed run. Any test that asserts an exact film set over the shared `film` table is exposed the same way, so the fix is a question of isolation (seeded rows a range query cannot match, or tests that write `film` not running beside tests that read it exactly), not of this one pair.
