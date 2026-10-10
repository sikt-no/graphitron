---
id: R1010
title: "The sakila catalog regenerates whenever its inputs change: a gate refuses an init.sql or codegen change that leaves jooq.codegen.schema.version where it was"
status: Backlog
bucket: dx
priority: 2
theme: tooling
depends-on: []
created: 2026-10-10
last-updated: 2026-10-10
---

# The sakila catalog regenerates whenever its inputs change: a gate refuses an init.sql or codegen change that leaves jooq.codegen.schema.version where it was

## Goal

**An incremental build regenerates the sakila jOOQ catalog whenever what it is generated from
changes.** `graphitron-sakila-db` regenerates only when `jooq.codegen.schema.version` in its pom
changes, and the pom asks a contributor to increment it by hand whenever `init.sql` changes. Twice in
two days that was missed: `film_bookmark` was added without the bump, and so were `recordless_note`
and its records exclusion. A clean build regenerates regardless, so CI and the author's own clean
install pass, while every session building over an existing `target/` packages the old catalog and
fails on a table the database declares, in a module the change never touched. When this lands, a
change to the catalog's inputs that leaves the version where it was fails the build that makes it,
or the version stops being a hand-kept number at all.

## What changes

A Backlog sketch; the plan is written at Spec.

* The inputs are `init.sql` and the codegen configuration in the module's pom (the
  `jooq-codegen-maven` executions, excludes and record excludes included).
* Two shapes to choose between at Spec. A gate, beside the module's other build checks, that fails
  when a hash of the inputs differs from one recorded beside the version. Or no hand-kept version:
  the `schemaVersionProvider` takes a hash of the inputs, so regeneration follows them by
  construction and there is nothing to forget. The second removes the step rather than policing it,
  and is the one to try first.
