---
id: R777
title: "Re-source the @error union TypeResolver ladder onto ErrorRouter.Mapping[]"
status: Backlog
bucket: cleanup
priority: 4
theme: error-channel
depends-on: []
created: 2026-08-21
last-updated: 2026-08-21
---

# Re-source the @error union TypeResolver ladder onto ErrorRouter.Mapping[]

"Does handler H fire on throwable T" has two spellings in the tree. The
`ErrorRouter.Mapping.match` family is the load-bearing one: all three dispatch arms
(`ErrorRouter.dispatch`, `ErrorRouter.dispatchToLocalContext`, and `ChannelCatchArmEmitter`,
which inlines the loop) go through it, and the `@error` type's `message:` fetcher now walks it
too. The second is a hand-rolled `instanceof` ladder in
`GraphitronSchemaClassGenerator.buildErrorPolymorphicResolver`, which re-implements the same
three discriminators in emitter code to pick a union member by source class.

The two have already drifted, which is the concrete reason to collapse them rather than a
tidiness argument: `ErrorMappingsClassGenerator.bestGuessOrObject` falls back to `Object.class`
on a malformed exception class name, while `buildErrorPolymorphicResolver` calls
`ClassName.bestGuess` bare and throws on the same input. So one SDL shape is a tolerated
degradation on the dispatch side and a generator crash on the resolver side.

Collapsing the ladder to a `Mapping[]` walk leaves one spelling of the predicate and closes that
drift. The blast radius is the TypeResolver emission, which is why it was held out of the item
that surfaced it (the `description:` read-side override, shipped; see `roadmap/changelog.md`)
rather than folded in. Note that the ladder is the *weaker* of the two spellings: it is not a
pattern to copy, and any work here moves toward `Mapping[]`, not away from it.
