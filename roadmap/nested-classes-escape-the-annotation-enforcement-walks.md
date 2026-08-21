---
id: R789
title: "The annotation enforcement walks skip nested classes, so an annotation on one goes unenforced"
status: Backlog
bucket: cleanup
priority: 4
theme: tooling
depends-on: []
created: 2026-08-21
last-updated: 2026-08-21
---

# The annotation enforcement walks skip nested classes, so an annotation on one goes unenforced

Two meta-tests in `graphitron-sakila-example` enforce a class-level annotation rule by walking
`target/test-classes` and reflecting on what they find:
`no.sikt.graphitron.rewrite.test.internal.TierAnnotationEnforcementTest` requires every test class
to declare a tier, and `no.sikt.graphitron.rewrite.test.internal.QuarkusTestLockEnforcementTest`
requires every `@QuarkusTest` class to lock the shared deployment key. Both filter out any class
file whose name contains `$`, which is how the compiler names a nested class, so a rule violation on
a nested class is invisible to the walk that exists to catch it. For the lock walk that is the more
expensive miss: a nested `@QuarkusTest` would rejoin the failure mode the lock was introduced to
close, `io.quarkus.test.junit.QuarkusTestExtension` crossing its static per-test bookkeeping slots
between two classes in flight, and the resulting failure names a method on the wrong class, three
files from the edit that caused it.

The `$` filter is presumably there to skip the synthetic and anonymous classes a walk like this
trips over, and the fix is to distinguish those from a named nested class rather than to drop the
filter: `Class.isAnonymousClass`, `isLocalClass` and `isSynthetic` answer that question directly,
where the file name does not. Worth deciding at the same time whether a nested test class should be
in scope for the tier rule at all, since JUnit's `@Nested` classes inherit their outer class's
lifecycle and a tier annotation on them may be redundant rather than required; the two walks may
want different answers, which is a reason to state each rule's scope explicitly instead of letting
a shared file-name filter decide it for both.

No known live violation: this is a gap in enforcement rather than an escaped defect, and it was
noticed while reviewing the item that added the second walk.

