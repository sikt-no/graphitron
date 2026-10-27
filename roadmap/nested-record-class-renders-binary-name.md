---
id: R1109
title: "A @service result type that is a nested class renders as Outer$Inner in generated fetchers and fails to compile"
status: Backlog
bucket: bug
priority: 6
theme: service
depends-on: []
created: 2026-10-27
last-updated: 2026-10-27
---

# A @service result type that is a nested class renders as Outer$Inner in generated fetchers and fails to compile

## Goal

A `@service` whose return type is a nested class (a `record` declared inside the service class, say) generates code that compiles. Today the generated payload fetchers refer to it by its binary name: a service `MutationScopeService` returning its nested `record MutationScopeRefusedPayload` produced `import no.sikt.graphitron.rewrite.test.services.MutationScopeService$MutationScopeRefusedPayload;` and a cast to `MutationScopeService$MutationScopeRefusedPayload` in `MutationScopeRefusedPayloadFetchers`, which javac rejects with "cannot find symbol". The name likely reaches JavaPoet as `Class.getName()` text through `ClassName.bestGuess` or `ClassName.get(package, simpleName)`, which read `$` as part of one top-level simple name; the record-source cast is built in `ParentSourceBinding`. A consumer whose payload classes are nested today has to move them to top level, with no build-time message saying why.

Found while writing R1101's execution fixtures, which moved the payload to a top-level `MutationScopeRefusedPayload` instead. The plan should find every place a reflected class name becomes a `ClassName` (an enclosing-class-aware `ClassName.get(Class)` where the `Class` is live, the canonical name split on its enclosing classes where only text is), and pin a nested return type at the pipeline or compilation tier.
