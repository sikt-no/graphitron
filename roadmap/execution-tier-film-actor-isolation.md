---
id: R946
title: "A mutation fixture writing film_actor is visible to a concurrent read test"
status: Backlog
bucket: bug
priority: 3
theme: testing
depends-on: []
created: 2026-09-11
last-updated: 2026-09-11
---

# A mutation fixture writing film_actor is visible to a concurrent read test

## Goal

The execution tier stops reporting a read test's answer as wrong when the only thing wrong is that
another test wrote the row it counted. A build that fails for this reason costs a reviewer the
17-minute wall clock to tell a real regression from a scheduling accident, and it teaches the habit
this suite is built to refuse, that a red execution test might be nobody's fault.

Observed on 2026-09-11, on a rebased tree whose own diff touched none of it:
`GraphQLQueryTest.splitTableField_conditionJoin_returnsActorsPerFilm` asserts the seeded
`film_actor` mapping (film 3 owns actor 1 alone) and saw actors 1 and 2 under film 3. The seeded
rows in `graphitron-sakila-db`'s `init.sql` are correct and the table held exactly the seven seeded
rows after the run, so the extra row was present only while the two tests overlapped: a mutation
fixture that inserts into `film_actor` (the composite-node-id assignment family is the candidate)
is visible to a reader that counts those rows. The same class passed on a targeted re-run, which is
the signature of an ordering-dependent read rather than a defect in either test's own logic.

The fix is isolation the suite states rather than a retry: decide whether a writing fixture owns its
own rows (a film or actor no reader asserts on), rolls back, or forces the two apart, and say which
on the fixture. Whatever the pick, the reader's assertion should be one a concurrent writer cannot
move, since the next such pair will not announce itself.
