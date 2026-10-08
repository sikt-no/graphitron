---
id: R1003
title: "createFilm execution test counts the film table, so a parallel writer fails it"
status: In Review
bucket: cleanup
priority: 3
theme: testing
depends-on: []
created: 2026-10-08
last-updated: 2026-10-08
---

# createFilm execution test counts the film table, so a parallel writer fails it

## Goal

`GraphQLQueryTest.createFilm_insertsRowAndReturnsProjectedFilm` in `graphitron-sakila-example` proves that the generated INSERT mutation writes exactly one row, and it should fail only when that is false. Today it counts every row in `film` before and after the mutation. Test classes in that module run concurrently against one PostgreSQL instance, and about ten other classes write and delete films, so the count can move for reasons the mutation has nothing to do with. The module's `junit-platform.properties` names this exact pattern as the one to avoid. It failed twice in a row in a verification build on 2026-10-08 (6 rows against an expected 7) and passed when run alone. After this item the test counts only rows carrying its own UUID-marked title, before and after, which is what "inserted exactly one row" means.

## Plan

Replace the two unfiltered `dsl.fetchCount(table("film"))` calls with counts filtered on `title = marker`: zero before the mutation, one after. Nothing else in the test changes, and the `finally` cleanup already deletes by the same marker.

## Gate waiver

The user waived the Spec and Ready gates for this item on 2026-10-08 (a one-line test fix blocking another item's verification build), so it moved from Backlog straight through to In Progress in the implementing session. In Review to Done still goes to an independent session.
