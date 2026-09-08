---
id: R936
title: "The federation library is reported in graphitron's voice, not thrown in the library's"
status: Backlog
bucket: dx
priority: 3
theme: diagnostics
depends-on: []
created: 2026-09-08
last-updated: 2026-09-08
---

# The federation library is reported in graphitron's voice, not thrown in the library's

## Goal

An author whose schema carries a federation `@link`, the directive that imports the Apollo federation
spec's directives into their schema, learns from graphitron whether the library that supplies those
definitions is present and whether it supports the spec version their `@link` names. Today they learn
it from the library instead: `federation-graphql-java-support` throws
`UnsupportedFederationVersionException` for a spec version it does not implement, and that exception
reaches the build with the library's vocabulary rather than a sentence naming the `@link`, its file
and line, and the versions that would work. The outcome is a rejection graphitron writes itself,
consistent with how it reports every other schema problem, so an author who bumped an `@link` URL is
told what to do about it.

The facts this rests on are cheap to establish and none of them are recorded today. Whether the
library is on the classpath at all is a classpath question the census already answers for every other
jar. Which version resolved is available from the artifact. Which spec versions that version
implements is a property of the library's own bundled SDL, and the two are related by a table the
library effectively owns: v2.6 and v2.7 ship a duplicate `@tag` declaration that
`FederationLinkApplier.buildCollisionMessage` already has to explain to the author, which is the shape
of remediation this item generalises. Presence and version are separately worth having in the store,
where a diagnostics reader can see them without re-resolving a classpath.

Scope note, since the neighbouring question is easy to conflate with this one. This is not about which
source owns the injected definition rows or which refresh deletes them. That is settled elsewhere: the
`@link` is the trigger, the library only supplies the text, and the rows follow the `@link`. This item
is about telling someone what is wrong with their build, so the library's identity is a fact to report
and not a partition to attribute rows to.
