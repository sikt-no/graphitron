---
id: R254
title: "Generated GraphitronSchema emission must have bounded chain depth"
status: Backlog
bucket: bug
depends-on: []
created: 2026-05-27
last-updated: 2026-05-27
---

# Generated GraphitronSchema emission must have bounded chain depth

Generated `GraphitronSchema.java` is one long chained expression whose depth scales with schema element count: every type, directive application, and federation `@link` import folds another `.with…(...)` / `.argument(...)` link onto the same fluent call. Under `quarkus:dev` + `graphitron:dev`, any regenerate-then-incremental-compile cycle blows javac's stack with the canonical chained-call attribution loop (`Attr.attribTree → visitApply → visitSelect → …` repeating; top frames are incidental class-load / name-table work that happened to push it over). Cold `mvn install` builds mask the problem because batch compilation primes name tables and class symbols, so per-frame work is cheap; the dev loop's single-file incremental compile pays those costs cold and tips over. Federation `@link` version bumps surface it because they expand the imported directive set, but any regen of a non-trivial schema is over the safe budget. Fix: break emission in the schema-class generator and `AppliedDirectiveEmitter` into a sequence of statements with local-variable hand-offs so per-element chain depth stays O(1) regardless of schema size. Bumping `-Xss` treats the symptom, not the cause. Test the structural property directly (assert no expression statement in the generated source exceeds a fixed chain-depth bound, e.g. 32) rather than trying to reproduce the StackOverflowError, which depends on `-Xss`, JVM version, and warm-vs-cold compile state.
