---
id: R304
title: "Reify @error PayloadAccessor errors fetcher into a named method"
status: Backlog
bucket: architecture
depends-on: []
created: 2026-06-14
last-updated: 2026-06-14
---

# Reify @error PayloadAccessor errors fetcher into a named method

R303 reified every datafetcher onto a named `<Type>Fetchers` method except one: the `@error`-type
errors field on the `Transport.PayloadAccessor` arm, which still registers graphql-java's
`PropertyDataFetcher.fetching(name)` (a runtime reflective property read off the parent payload).

R303's Validator-mirror section proposed reifying it "via the same `recordBackedAccessorRead`
helper the class-backed path uses," but that helper needs an `AccessorResolution.Resolved`, and
`ChildField.ErrorsField` / `Transport.PayloadAccessor` carry no resolved accessor: the read is
deliberately left to graphql-java's runtime reflection. Resolving it at generation time means the
classifier (`FieldBuilder.liftToErrorsField`) must reflect the payload class and produce an
`AccessorResolution.Resolved` for the errors slot, a classifier-model change R303 explicitly
excluded ("the classifier model is untouched"). So R303 deferred it here rather than reach past the
parse-boundary with emit-time reflection.

Scope when this is picked up: resolve the errors-field accessor at classify time (mirroring the
`PropertyField` / `RecordField` accessor resolution on a class-backed parent), reify the
`PayloadAccessor` arm in `FetcherEmitter.bind` to a source-only `LightFetcher`-wrapped read, then
reconcile `FetcherEmitter.resolvesViaPropertyDataFetcher` and the R268
`validateOutcomeChildArmSwitch` rule (the `PropertyDataFetcher` escape the predicate guards no
longer exists once reified), and retire `DataFetcherKind.PROPERTY_FETCHER`.

