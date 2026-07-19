---
id: R443
title: "Sealed tri-state ConditionResolution result (post-R438 residue)"
status: Ready
bucket: structural
priority: 5
theme: docs
depends-on: []
created: 2026-07-08
last-updated: 2026-07-19
---

# Sealed tri-state ConditionResolution result

R438's In Review to Done review surfaced this residue, captured here as a cleanup since R438 is closed. (Its sibling residue, the stale `fkjoin-alias-dead-storage` Backlog item R120, was already resolved by discarding that item on 2026-07-13, see the changelog.)

## The drift

`BuildContext.ConditionResolution` (`BuildContext.java:2245`) is a two-nullable-field record whose javadoc (`:2240`) asserts "exactly one of `ref` and `error` is non-null." That is false. `resolveConditionRef` (`:2260`) returns `new ConditionResolution(null, null)` in two places:

* `:2270`, when `className` / `methodName` / `svc` is absent.
* `:2292`, when `reflectTableMethod` fails (`result.failed()`).

So the real contract is tri-state: `ref` non-null (resolved), `error` non-null (an actionable message the caller surfaces directly), or both null (unresolved). All three `parsePathElement` callers (`:1870`, `:1902`, `:1942`) already handle it, guarding `res.error() != null` first, then `res.ref() == null` (those `ref() == null` guards are R438's review-fix). The code is correct; only the record's javadoc lies, and it lies because it conflates the record's own cardinality with what the callers do with the both-null case ("maps to a generic resolution error" is caller behaviour, not this record's contract).

## Why restructure, not just re-document

A nullable pair whose legal-state cardinality is asserted only in prose is the drift smell itself, and it already rotted once: nothing failed when the javadoc went stale, and a fourth caller that forgets the `ref() == null` guard would compile clean and misbehave at runtime. Correcting the prose fixes this instance while leaving the shape that produced it.

The decisive point is that `ConditionResolution` is the anomaly among its own neighbours. Every other resolution outcome threaded through these same three branches is already a sealed switch: `FkJoinResolution.{Resolved, UnknownTable, UnknownForeignKey}`, `ConditionJoinTargetResolution.Resolved`, and inside `resolveConditionRef` itself `ArgBindingMap.Result.{Ok, UnknownArgRef, PathRejected}` and `ArgBindingMap.ParsedArgMapping.{ParseError, Ok}`. A nullable pair here is the outlier. Making it sealed removes ceremony rather than adding it, and it hands the tri-state contract to the compiler (an exhaustive `switch` in place of an ordered, hand-maintained null-guard convention duplicated across three sites).

## The change

Replace the record with a sealed result:

    private sealed interface ConditionResolution {
        record Resolved(MethodRef ref) implements ConditionResolution {}
        record Failed(String message) implements ConditionResolution {}
        record Unresolved() implements ConditionResolution {}
    }

* `resolveConditionRef`'s five returns map one-to-one: the two `(null, null)` sites (`:2270`, `:2292`) return `Unresolved`; the three error sites (`:2275`, `:2281`, `:2286`) return `Failed(message)`; the success tail (`:2292`) returns `Resolved(ref)`.
* Each `parsePathElement` caller (`:1870`, `:1902`, `:1942`) switches over the three arms in place of the ordered `error() != null` / `ref() == null` guards. The `Failed` arm keeps `errors.add(message)`; the `Unresolved` arm keeps the existing `"condition method '" + extractConditionQualifiedName(condMap) + "' could not be resolved"` message; the `Resolved` arm carries `ref` onward exactly as today.
* The arm javadoc states each arm's meaning as a plain fact; the false "exactly one non-null" sentence is gone, with no roadmap-id citation in its place (main-source comment, scanned by `RoadmapReferenceGuardTest`).

## Behaviour and coverage

Behaviour is preserved exactly: the same observable messages reach `errors` in the same cases, so no generated output changes. The `Failed` arm stays `Failed(String)`, not `Failed(Rejection)`; the surrounding `parsePathElement` surface is string-based `errors.add(...)` throughout, and lifting this path to typed `Rejection` is separate, larger scope.

Existing pipeline coverage stands and is the enforcer: condition-join resolution is exercised by `QueryConditionsPipelineTest`, `TypeConditionsGeneratorTest`, and `ReferenceFilterRemoteColumnPipelineTest` (resolved path, compiled against the sakila catalog), and the "could not be resolved" / path-step `@condition` messages are asserted in the builder-tier tests that already cover the failure and unresolved cases. No new unit test asserting `CodeBlock` string equality; the tri-state is now compiler-enforced, which is the coverage the doc-only fix could not provide.

## Non-goals

* No lift to typed `Rejection`; `Failed(String)` matches the local `errors.add` idiom.
* No change to the neighbouring sealed results (`FkJoinResolution`, `ConditionJoinTargetResolution`, `ArgBindingMap.*`); they are already correct and are cited only as the idiom this item aligns to.
* No generated-output or behaviour change; this is an internal refactor of a private, single-file, three-caller helper.
