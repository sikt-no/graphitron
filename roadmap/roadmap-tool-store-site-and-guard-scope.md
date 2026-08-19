---
id: R737
title: "roadmap-tool stands a fact store up outside the harness, in a module the guard does not walk"
status: Backlog
bucket: cleanup
priority: 4
theme: testing
depends-on: []
created: 2026-08-19
last-updated: 2026-08-19
---

# roadmap-tool stands a fact store up outside the harness, in a module the guard does not walk

`StoreFixtureGuardTest` states one rule: a test does not stand a fact store up for itself, it takes one from
the harness that owns its subject. One test source in the reactor still does, and the guard cannot see it.
`roadmap-tool/src/test/java/no/sikt/graphitron/roadmap/SchemaReferencePagesTest.java:26` calls
`GraphitronModelStore.open()` directly, and `roadmap-tool` is deliberately absent from
`GuardScope.IN_SCOPE_MODULES`, which the three guards sharing that list walk. The exclusion is not an
oversight and cannot simply be reverted: `roadmap-tool`'s whole domain is roadmap items, so an `R<n>` in its
sources is a legitimate reference rather than a stale citation, and `RoadmapReferenceGuardTest` would start
failing on the module's own subject matter the moment the list grew.

So the reader of the guard is currently told something slightly untrue. The lists are honest about the
modules they cover and say nothing about the one they do not, and a reader who takes "the tree is clean" from
a passing guard is one module wrong. Two shapes settle it, and the choice is the item's first question.
Either the store-fixture guard gets a walk scope of its own that includes `roadmap-tool` while the two prose
guards keep theirs, and the one site adopts `FactStores` (the module already depends on `graphitron-model`,
so nothing but a test-jar dependency is needed); or the exclusion stands as a deliberate boundary and says so
at `GuardScope.IN_SCOPE_MODULES`, naming the module the store-fixture guard does not reach and why, so the
gap is declared rather than inferred.

Two smaller pieces of the same drift belong here rather than in items of their own:

* `GuardScope`'s class javadoc still reads "Shared walk scope for the prose guards
  (`RoadmapReferenceGuardTest` and `RetiredVocabularyGuardTest`) ... the repository-root anchor both guards
  walk from". Three guards read it now; `StoreFixtureGuardTest` is not a prose guard and the sentence has no
  room for it. Whichever shape the question above settles on, this sentence is the place it gets written down.
* The guards' anti-vacuity floors are per-guard integers (`MIN_SCANNED_TEST_FILES = 400` in
  `StoreFixtureGuardTest`, against 740 files actually walked). Nothing is wrong with the number; it is worth
  a look only because the scope question moves the population it is a floor on.

