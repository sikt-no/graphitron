---
id: R1135
title: "A custom scalar coercing an array or a parameterized list has no valid shape"
status: Backlog
bucket: bug
priority: 4
theme: codegen-correctness
depends-on: []
created: 2026-10-30
last-updated: 2026-10-30
---

# A custom scalar coercing an array or a parameterized list has no valid shape

## Goal

A custom scalar whose `Coercing` produces a Java array or a parameterized list classifies against
a column of that shape (a `TEXT[]` column, say), or is rejected as an author error naming why. Today
the array form crashes the generator and the list form is read erased.

## The defect

* `ScalarTypeResolver.recoverInputType` (near line 266 in RC46) calls `ClassName.get(box(input))`,
  which throws `IllegalArgumentException: array types cannot be represented as a ClassName` for
  `Coercing<String[], ...>`. Nothing catches it, so the generator crashes instead of reporting.
* `inputFromInterface` treats a parameterized `Coercing<List<String>, ...>` as erased.

So a custom scalar over a `TEXT[]` column has no valid shape. kreg switched the field to `[String]`,
which works.

## Direction

Represent the coerced type as a `TypeName` rather than a `ClassName` so arrays and parameterized
types survive, match it against the column's type, and turn whatever remains unsupported into a
rejection at the scalar's coordinate. Pipeline-tier cases for both forms.

Provenance: reported by the kreg Graphitron 10 port session (fs-plattform's kreg module on 10.0.0-RC46, branch `worktree-kreg-g10` at `7858137af4`, not pushed), 2026-10-30; the reporter found it present on graphitron `origin/main` the same day.
