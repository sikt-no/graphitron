---
id: R1198
title: "Two plugin executions sharing a graph name are refused, or captured apart, instead of colliding on a duplicate key"
status: Backlog
bucket: bug
priority: 5
theme: tooling
depends-on: []
created: 2026-11-17
last-updated: 2026-11-17
---

# Two plugin executions sharing a graph name are refused, or captured apart, instead of colliding on a duplicate key

## Goal

Two `graphitron-maven-plugin` executions that share one `<graphName>` no longer collide in the fact store: today, when both schemas declare a type of the same name, the second capture fails with a duplicate-key violation in a relation keyed by graph and type, and nothing tells the two executions apart. Either a shared graph name is refused up front with a message naming both executions, or the relation's key and its mark and sweep include the execution so one capture cannot collide with or sweep another's. Seen in graphitron-sakila-example, where the accepting execution was given `<graphName>accepting</graphName>` to work around it; dropping that line on the `r1119-8a-reference` branch reproduces it.
