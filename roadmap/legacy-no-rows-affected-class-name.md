---
id: R1170
title: "An @error handler naming Graphitron 9 NoRowsAffectedException claims the zero-rows UPDATE refusal"
status: Backlog
bucket: dx
priority: 3
theme: error-channel
depends-on: []
created: 2026-11-11
last-updated: 2026-11-11
---

# An @error handler naming Graphitron 9 NoRowsAffectedException claims the zero-rows UPDATE refusal

## Goal

A schema written for Graphitron 9.4 that maps a write which changed no rows with `{handler: GENERIC, className: "no.sikt.graphql.exception.NoRowsAffectedException"}` keeps that mapping on Graphitron 10: the handler claims the `ZeroRowsMatchedException` Graphitron 10's payload-returning `UPDATE` throws when an input row's key matches nothing, and the client gets the same `@error` member it got from Graphitron 9. Today the class name names a Graphitron 9 runtime class that Graphitron 10 neither ships nor throws, so the handler is either unresolvable at build time or never fires.

The first consumer studied (sis, on Graphitron 9.4.4) maps it in 12 unions, through one `@error` type (`FantIngenOppforingAaEndre`) on `@mutation(typeName: UPDATE)` fields. Graphitron 9.4 throws it from `QueryHelper.requireRowsAffected`, after a batched write whose statements report a zero row count.

Open at pickup: whether Graphitron 9.4's set of writes that throw (any batched write, including deletes) matches the set where Graphitron 10 throws `ZeroRowsMatchedException` (list-input `UPDATE`); whether the answer is a classify-time alias of the class name, a rejection whose message names the replacement, or both; and how the message differs, since a handler without `description:` answers the exception's own text.
