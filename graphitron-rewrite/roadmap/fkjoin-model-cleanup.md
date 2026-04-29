---
title: "`FkJoin` model cleanup: `JoinConditionRef` wrapper"
status: Backlog
bucket: cleanup
priority: 5
theme: model-cleanup
depends-on: []
---

# `FkJoin` model cleanup: `JoinConditionRef` wrapper

`FkJoin.whereFilter` and `ConditionJoin`'s condition method are typed `MethodRef` today, but they're not arbitrary method references: the generator calls them with a fixed `(srcAlias, tgtAlias)` two-argument convention via `JoinPathEmitter.emitTwoArgMethodCall`. The same `MethodRef` interface is also implemented by `ConditionFilter`, which carries the separate `WhereFilter` calling convention. The two shapes share a type today; conflating them has been a recurring source of confusion (the field name `whereFilter` on `FkJoin` is itself a misnomer, since it's a join-condition, not a filter).

Introduce a wrapper type, e.g. `JoinConditionRef`, that surfaces the join-condition calling convention at the type level. Replace `MethodRef whereFilter` on `FkJoin` and `ConditionJoin` with the new type, and rename the `whereFilter` field in the process.

Open design questions:

- Wrapper record around `MethodRef`, or a new sealed variant?
- Naming: `JoinConditionRef`? `JoinPredicate`? `JoinCondition`? How does it relate to the existing `WhereFilter` sealed hierarchy (which already has a `ConditionFilter` arm)?
- Whether to also tighten the call-site contract: `JoinPathEmitter.emitTwoArgMethodCall` could take the new type directly, removing the need for callers to extract a raw `MethodRef`.

Touchpoints: `JoinStep.FkJoin` and `JoinStep.ConditionJoin` records, `BuildContext.synthesizeFkJoin` and `parsePathElement`, `BuildContext.resolveConditionRef`, plus the readers in `InlineTableFieldEmitter`, `InlineLookupTableFieldEmitter`, `SplitRowsMethodEmitter`, and `JoinPathEmitter`.

The two trivial subscopes that originally rolled up here (`originTable` rename and `parsePathElement` `{key:}` delegation to `synthesizeFkJoin`) have shipped; this remaining piece needs a small design pass before code.
