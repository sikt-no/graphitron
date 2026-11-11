---
id: R1167
title: "Error-channel dispatch routes every throw where Graphitron 9 routed it"
status: Backlog
bucket: dx
priority: 3
theme: error-channel
depends-on: []
created: 2026-11-11
last-updated: 2026-11-11
---

# Error-channel dispatch routes every throw where Graphitron 9 routed it

## Goal

A schema that ran on Graphitron 9 puts each exception its services and mutations throw into the same `@error` member, with the same `message:`, on Graphitron 10: a database error raised through jOOQ is offered to the `DATABASE` handlers before any `GENERIC` one, and any other exception to the `GENERIC` handlers before any `DATABASE` one, each group in the order the schema declares it, as Graphitron 9 did. Today Graphitron 10 walks every handler in one declared order, so a broad `GENERIC` catch-all declared ahead of a `DATABASE` member silently takes the database errors that member used to get.
