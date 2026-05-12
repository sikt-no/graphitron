---
id: R151
title: "Harden CarrierFieldRoleCoverageTest from grep-based to reflection-based"
status: Backlog
bucket: structural
priority: 3
theme: testing
depends-on: []
created: 2026-05-12
last-updated: 2026-05-12
---

# Harden CarrierFieldRoleCoverageTest from grep-based to reflection-based

R141 shipped `CarrierFieldRoleCoverageTest` as a build-time audit that every consumer of `CarrierFieldRole` carries an explicit dispatch arm per permit. The current implementation greps consumer source files for the permit name (`DataChannel`, `ErrorChannelRole`), which passes on any string match including javadoc prose and no-op `case ErrorChannelRole ignored ->` arms; it does not verify that the dispatch arm has behaviour or even that the consumer compiles against the permit. The check is correct in spirit (catches a forgotten consumer when a new permit lands) but brittle in practice: it passes today partly because `SingleRecordCarrierShape.java` mentions both permits in javadoc, and it would continue to pass if a consumer's switch arm were deleted as long as the name still appears in a comment. Lift the check to mirror `GeneratorCoverageTest.everyGraphitronFieldLeafHasAKnownDispatchStatus`'s shape: reflect over `CarrierFieldRole.class.getPermittedSubclasses()` and verify each consumer site (registered by a marker or a tracked set) compiles a real sealed switch against the closed permit set, so a missing arm fails at the audit rather than at runtime in a new R12-shaped scenario.
