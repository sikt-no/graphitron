---
title: "`FkJoin` model cleanup"
status: Backlog
bucket: cleanup
priority: 5
theme: model-cleanup
depends-on: []
---

# `FkJoin` model cleanup

Three small follow-ups on `JoinStep.FkJoin` that share files and reviewer context. Do as one sweep.

## Rename `sourceTable` → `originTable`

`JoinStep.FkJoin.sourceTable` is the traversal-origin table of the hop, not the FK-holder side. The docstring at `model/JoinStep.java:77-87` already documents this correctly, but the field name fights the docstring and tripped readers in the past (see `BuildContext.synthesizeFkJoin:487` and `parsePathElement:580`, plus the one current reader at `generators/TypeClassGenerator.fkMirrorSourceColumns:300`). Renaming the field to `originTable` retires the ambiguity without touching semantics.

A construction-time invariant assertion was considered and dropped: by construction the source SQL name is always validated against the FK's two sides upstream, so a runtime check would be tautological.

## `JoinConditionRef` wrapper

Distinguish the `MethodRef` that carries an FK-join `condition:` sub-argument (used by `ConditionJoin` / `FkJoin`) from the `MethodRef` that carries a `WhereFilter` predicate. They share the same record today, but the two calling conventions differ; a thin wrapper type surfaces the distinction at compile time.

## Unify `FkJoin` construction in `parsePathElement`

The `{key:}` branch at `BuildContext.java:556-587` hand-builds an `FkJoin`. Delegate the source-validated success path to `synthesizeFkJoin` (lines 487-499), keeping the null-source fallback and connectivity-error arms bespoke. Pure duplication-removal.
