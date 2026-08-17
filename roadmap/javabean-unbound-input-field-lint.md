---
id: R695
title: "Lint an SDL input field that binds to no member on a JavaBean-target @service bean"
status: Backlog
bucket: cleanup
priority: 6
theme: service
depends-on: []
created: 2026-08-17
last-updated: 2026-08-17
---

# Lint an SDL input field that binds to no member on a JavaBean-target @service bean

`InputBeanResolver.bindJavaBean` is partial by design: an SDL input field whose binding key
names no setter on the bean is skipped, and the bean simply does not populate it. Only the
all-fields-miss case rejects. The partiality is deliberate (a bean may legitimately ignore
fields the input carries), but it means a typo in a field name, or in a `@field(name:)`
value, produces a member that stays null with nothing in the build saying so. The record arm
has no such hole: its direction-B check fails a field that names no component, on the
grounds that the value would otherwise be silently dropped.

The gap deserves a warning rather than a rejection, since a hard reject would break beans
that are deliberately partial. That needs a new `LintRule` arm, a `SourceLocation` for the
SDL input field (which `InputBeanResolver` does not currently thread), and a call on how much
noise the rule produces against beans that ignore fields on purpose. A `@field(name:)`-carrying
field that misses is the strongest candidate for the rule, since the directive is an authored
claim that a named member exists.

Filed out of R693's spec pass, which answered the equivalent open question by deferral. R693
fixes the specific silent drop it names (a nested grouping input whose leaves match members
now binds them rather than dropping the group) and leaves this pre-existing contract
untouched, so the two items do not overlap.
