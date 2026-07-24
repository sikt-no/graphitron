---
id: R518
title: "argMapping grouping form for multi-target service fan-out (GG-376)"
status: Backlog
bucket: architecture
priority: 6
theme: classification-model
depends-on: []
created: 2026-07-24
last-updated: 2026-07-24
---

# `argMapping` grouping form for multi-target service fan-out (GG-376)

Carved out of R97 (`consumer-derived-input-tables`) as its own item, the way
R457/R514/R515 were split off the mutation write-target axis. R97's body noted
this phase "shares no code path, dependency, or gate with the directive-retirement
axis; its only tie is the 'convention + argMapping escape valve' rationale," so it
lives here now that R97 has narrowed to the consumer-derived resolution it
actually shipped. Orthogonal: schedule freely, independent of R519/R520.

Closes JIRA GG-376 (the proposed `@param` directive becomes `argMapping`
grouping here).

## Problem

Today's `argMapping` (with R84's path expressions) handles single-source to
single-target: the right-hand side is a path into the input arg, the left-hand
side a service-method param name. It cannot express **multi-source to
single-target** fan-out, where one input's fields scatter across several
service-method params (each a distinct jOOQ record / POJO). GG-376 proposed a new
`@param` directive for that; R97's architectural line is to extend the existing
escape valve rather than add a directive.

## What to build

A **grouping form** on `argMapping`:

```graphql
type Mutation {
    createOrder(input: CreateOrderInput!): Order
        @service(service: {
            className: "OrderService", method: "create",
            argMapping: """
                order:  { orderNumber: input.orderNumber, customerId: input.customerId },
                shipTo: { street: input.street, city: input.city }
            """
        })
}
```

The classifier introspects `OrderService.create`'s signature (`OrderRecord order`,
`AddressRecord shipTo`) and fills each param from its grouping entry.

Subsumes GG-376's `@param`: `name` → grouping entry LHS; `target` → derived from
the service method's param type; `fields` → the entry's RHS paths. `@nodeId`
decoding inside a group works as it does in today's `argMapping`.

### Rules (GG-376 validation, restated as `argMapping` extensions)
- Each participating input field appears in exactly one grouping entry's RHS.
- The entry LHS must match a service-method parameter name (needs `-parameters`).
- The RHS field set must match (or be a subset of) the target type's canonical
  constructor params (record) or settable fields (POJO).
- Convention defaults (R94 Layer 2 `Constructed` by-name resolution) still apply
  when `argMapping` provides no grouping for a param. The overlap is intentional;
  Spec-stage review should confirm the boundary is clean.

## Implementation surface (from R97's Phase 1 sketch)
- Parser change in the `argMapping` value parser (`PathExpr`, R84's path-expression
  parser, is the precedent).
- Resolver change in the `argMapping` consumers (`ServiceDirectiveResolver` /
  `ArgBindingMap`).
- Sealed-result extension to `ArgBinding` to carry grouping outcomes.
- Compact-constructor-enforced grouping invariants on the new carrier.

## Tests / acceptance
- Pipeline-tier: SDL with a multi-target service method → emitted fetcher
  constructs each target from the grouped input fields.
- Execution-tier: a sakila mutation that fans out across two jOOQ records,
  round-tripping against PostgreSQL.

Acceptance: `argMapping` grouping works end-to-end for at least one sakila
fixture; existing single-source `argMapping` is unchanged.

## Risk
`argMapping` grouping syntax could become unwieldy for large fan-outs. Keep the
grouping form one level deep; defer multi-level nesting to a follow-up if real
schemas need it (most production fan-outs are 2-3 targets).
