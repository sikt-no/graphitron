---
id: R994
title: "The @nodeId manual does not describe a list argument decoded into a java.util.List producer parameter, nor the shape refusal"
status: Backlog
bucket: docs
theme: docs
depends-on: []
created: 2026-10-07
last-updated: 2026-10-07
---

# The @nodeId manual does not describe a list argument decoded into a java.util.List producer parameter, nor the shape refusal

## Goal

The user manual's `@nodeId` section "Decoding into a producer parameter named for the argument" (`docs/manual/reference/directives/nodeId.adoc`) describes what the build now does with a list argument. A `[ID!]!` `@nodeId` argument on a `@service` field reaches a `java.util.List` parameter of that name with one decoded value per id, and the element follows the single-id rules: the key column's own type at a one-column key, the node type's generated record at a composite key, or a record supertype for a polymorphic `typeName:`. A list argument at any other container (a `Set`, a `Collection`) and a single id at a multi-valued parameter fail the build, and the message names the declaration that would take the value. Today the section shows only single ids and says "Two ways to get this wrong", so an author meets the third refusal only as a build error. The section gains a list example beside `byKey` / `inStock`, plus the shape refusal as a third way to get it wrong, in the section's existing register.
