---
id: R790
title: "Nothing enforces that the sakila-example suite stays green when another class writes the tables it reads"
status: Backlog
bucket: cleanup
priority: 3
theme: tooling
depends-on: []
created: 2026-08-21
last-updated: 2026-08-21
---

# Nothing enforces that the sakila-example suite stays green when another class writes the tables it reads

`graphitron-sakila-example` now runs its test classes four at a time, and on the `local-db` path
that CI and a contributor sandbox use, all of them share one PostgreSQL database. Eleven classes
write `film` and two write `content` while their siblings read those tables, so a reader that
asserts what a table holds rather than what its own query means fails on rows it never wrote. The
property the module needs is therefore stronger than "the suite is green": it is "the suite is green
while another class's rows are in the tables it reads". Nothing enforces the second.

What closed sixty-three of those sites was a deterministic detector, not concurrency: insert rows
shaped like the ones the module's own writers create, run the suite, and every table-dependent case
fails on the spot instead of one time in five. That detector was a manual procedure both times it
ran, and both times its coverage was the cross product of the columns it varied against the
orderings the tests use. The first pass varied `rating`, `length`, `release_year` and the content
parent but not the *title*, so every row it inserted sorted after the seed, no title-ordered page
ever moved, and three cases survived into In Review. A procedure a reviewer has to remember to run,
and to remember the shape of, is the wrong home for the module's central invariant.

The shape worth exploring is a fixture that seeds a small adversarial row set for the duration of
the module's own test run: a film sorting ahead of the seed, one into the middle of it, one trailing
it, all at the column defaults the module's writers leave alone (`film.rating` defaults to `'G'`,
`film.length` and `film.release_year` to null), plus a content row on the film the content writers
attach to. Then a case that asserts what a table holds fails in ordinary CI rather than under an
interleaving nobody reproduces. Two things to settle in the plan: where such rows live so they do
not leak into a contributor's database (the seed is currently restored by nothing, so the rows must
clean up after themselves or be inserted by something that owns the database's lifetime), and how a
case that legitimately needs an exact count states that it is bounding its query rather than being
excused from the rule. A related question is whether this belongs to this module alone or to any
module whose classes share one database.

