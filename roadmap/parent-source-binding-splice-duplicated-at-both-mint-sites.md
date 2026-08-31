---
id: R886
title: "ParentSourceBinding.of is the single producer of the last step only; both call sites splice its inputs by hand"
status: Backlog
bucket: cleanup
priority: 4
theme: codegen-correctness
depends-on: []
created: 2026-08-31
last-updated: 2026-08-31
---

# ParentSourceBinding.of is the single producer of the last step only; both call sites splice its inputs by hand

When a generated data fetcher reads its parent object, the generator decides once per GraphQL
type how to get at that object: straight off the source, or after narrowing the error-channel
`Outcome` wrapper. `ParentSourceBinding` holds that decision, and its `of` method describes
itself as "The producer". It is the single producer of the last step only. The two facts `of`
needs are spliced by hand at both of its call sites: `parentTable != null ? SourceShape.Table :
SourceShape.Record` and `FetcherEmitter.hasWrapperArmErrors(fields)` appear identically in
`TypeFetcherGenerator.generateTypeSpec` and in `FetcherRegistrationsEmitter.parentSourceBinding`.
That second site is a private helper that exists only to hold its copy, and its javadoc is a
hand-maintained assertion that the copy matches the other one. A prose claim that two copies agree,
with nothing binding them, is a derived fact maintained apart from its source: if the registration
site and the fetcher body ever splice differently, the registration points at a method whose
arm-switch it does not match, and nothing fails until a request does.

The likely shape is to have `of` take the facts rather than the answer (the parent's table
backing and the field list), do both derivations inside, and delete both hand-splices along with
`FetcherRegistrationsEmitter.parentSourceBinding`. That also removes a bare `boolean` from a
producer signature where nothing stops a caller passing the wrong predicate, and drops the
`sourceIsOutcome` local at `TypeFetcherGenerator.generateTypeSpec` as a consequence. That local is
the last live spelling of the derivation's retired name; retiring it as a side effect of collapsing
the duplication is the honest route to a `RetiredVocabularyGuardTest.REGISTRY` entry for the token,
which R883 declined to take on the grounds that killing a name to qualify it for the registry
inverts what registry entries assert. Whether the registry entry is worth adding is a question for
this item's Spec, not a premise of it.

Recorded as observed and non-blocking at R873's Done gate (see `roadmap/changelog.md`), alongside
the prose-scrub observation that became R883. R883 ships the scrub and explicitly leaves this here.
No behaviour change expected: both call sites derive the same binding today, and the point is to
make that structural rather than asserted.
