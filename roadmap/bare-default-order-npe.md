---
id: R783
title: "A @defaultOrder with no arguments crashes the generator with a NullPointerException instead of rejecting"
status: Backlog
bucket: bug
priority: 6
theme: diagnostics
depends-on: []
created: 2026-08-21
last-updated: 2026-08-21
---

# A @defaultOrder with no arguments crashes the generator with a NullPointerException instead of rejecting

Writing `@defaultOrder` with no arguments at all, rather than one of `primaryKey:`, `index:` or
`fields:`, aborts the whole build with a bare `NullPointerException` and no located message. The
author gets a Maven stack trace naming `java.util.Objects.requireNonNull` and has to read the
generator's source to find out which coordinate is at fault.

The mechanism is one unguarded read. `OrderByResolver.resolveOrderEntries` falls through the
`index:` and `primaryKey:` arms to the `fields:` arm, and when the directive carries no `fields:`
argument either, `dir.getArgument(ARG_FIELDS)` still answers non-null (graphql-java materialises the
declared argument), so the null-check on the argument passes and the null *value* reaches
`List.of(value)`, which throws. Every other unresolvable `@defaultOrder` shape in that method
returns `null` and surfaces as a located author error instead, so the crash is a gap in one arm
rather than a missing rule.

What a fix has to decide is only what the argument-less spelling should mean. Two readings are
defensible and the crash is neither: reject it as an author error naming the three ways to spell an
ordering, or treat it as the primary-key default (which is what most authors writing the bare form
probably intend) and reject only when the table has no primary key, the way the `primaryKey:` arm
already does. Whichever it is, the acceptance is that the message is located at the directive and
the build fails cleanly, with a validator-tier fixture pinning it.

Found while authoring the fan-fixture ordering for the participant alias-namespace work; the
workaround there was to spell `@defaultOrder(primaryKey: true)`.
