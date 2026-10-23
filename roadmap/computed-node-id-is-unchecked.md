---
id: R1095
title: "A computed Node.id is not checked against the node decoder"
status: Backlog
bucket: bug
priority: 5
theme: codegen-correctness
depends-on: []
created: 2026-10-23
last-updated: 2026-10-23
---

# A computed Node.id is not checked against the node decoder

## Goal

On a node type (a `@table` type implementing `Node`), `id: ID! @externalField(...)` classifies as a computed field: the response carries whatever the author's method returns, while `node(id:)`, `nodes(ids:)` and a federation `@key(fields: "id")` still decode an incoming id with the node type's own decoder over its key columns. Nothing checks that the two agree, so a computed `Node.id` that does not spell the node encoding returns ids the server cannot resolve, and the build says nothing. When this lands, the build either refuses that shape or states the contract the method must meet and holds it to it.

## Background

`FieldBuilder.classifyChildFieldOnTableType` runs its `@externalField` arm ahead of the `Node.id` arm, so the directive is an explicit reading of the coordinate, like `@nodeId` and `@field`. The shape has always classified on a node type whose table has no `id` column. The column-name collision rejection in `ExternalFieldDirectiveResolver` happened to refuse it on the common node shape, a table with an `id` column; dropping that rejection widens the shape to every node type, and the `GraphitronSchemaBuilderTest` truth-table row pinning that verdict is the place a future enforcer flips. `EntityResolutionBuilder.build` mints `KeyAlternative.NodeId` from `nodeKeyColumns` whatever `id` classified to.

The fork for the Spec: a narrow rejection (a `ComputedField` at `Node.id` on a `NodeType`, whatever the catalog holds), or an admitted shape with a stated contract. The rejection is cheap but refuses a consumer who computes the encoding exactly; nothing in the repo writes the shape today.
