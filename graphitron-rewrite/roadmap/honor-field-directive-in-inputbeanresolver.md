---
id: R200
title: "Honor @field(name:) in InputBeanResolver for SDL input bean/record member binding"
status: Backlog
bucket: bug
priority: 5
theme: service
depends-on: []
created: 2026-05-20
last-updated: 2026-05-20
---

# Honor @field(name:) in InputBeanResolver for SDL input bean/record member binding

`InputBeanResolver` (the resolver that populates a `@service` Java parameter from an SDL `INPUT_OBJECT` by matching SDL field names to JavaBean setters or canonical record components) reads zero directives. It iterates `sdlOrder(iot)` or `recordOrder(beanClass)` and looks up the SDL name in `javaMembersBySdlName`, built either from `setX` setter tails (`InputBeanResolver.java:353-…`) or from `RecordComponent.getName()` (`:338-345`). When the Java member name diverges from the SDL field name, the field silently drops on a JavaBean (`:303-308` ultimately surfaces "no fields matching the SDL input type") or fails fatally on a record (`:260-265`). `@field(name: "<javaMemberName>")` on the SDL input field is the obvious author escape hatch and the directive's docstring at `directives.graphqls:29-39` would naturally cover it ("names the underlying-binding target", which on an `INPUT_FIELD_DEFINITION` whose parent is a free-form `@record`-shaped Java bean is the Java accessor / component / setter base). The fix is symmetric to R191's table-bound / polymorphic-hub thread: read `DIR_FIELD` on each SDL input field; when present, use the directive value as the lookup key into `javaMembersBySdlName` instead of the SDL field name. R195's jOOQ-record arm is a *different* shape (column → jOOQ TableField → setter walk) and is out of scope here; this item only fixes the consumer-authored bean / Java-record case where the directive should plain-old-name the Java member. The two items together restore `@field` symmetry across input and output for free-form `@record`-shaped parents.
