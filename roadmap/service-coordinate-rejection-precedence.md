---
id: R649
title: "Coordinate-level rejections outrank parameter-binding rejections on record-backed-parent @service"
status: Backlog
bucket: bug
priority: 3
theme: diagnostics
depends-on: []
created: 2026-08-13
last-updated: 2026-08-13
---

# Coordinate-level rejections outrank parameter-binding rejections on record-backed-parent @service

`ServiceDirectiveResolver.resolve` reflects the service method, and therefore binds its parameters, before it classifies the coordinate's return type. Parameter binding rejects first and short-circuits, so a problem that belongs to the coordinate is reported as a problem with the author's Java signature. On a record-backed parent the batch key is unavailable by construction, the SOURCES shape is discarded, and the surviving diagnostic is an argument-name mismatch that tells the author their parameter matched no GraphQL argument or context key. On a field that declares no arguments this reads as "available GraphQL arguments: (none); available context keys: (none)" followed by advice to add a GraphQL argument or register a context key, neither of which can ever apply. The honest rejection for the coordinate exists two arms below in the same classifier and is unreachable.

## Desired outcome

Coordinate-level rejections outrank parameter-binding rejections on this path. Whatever the classifier knows about the coordinate is decided, or at least consulted, before a parameter-name mismatch is allowed to become the reported failure.

## Why this survives the feature work

R648 makes the record-backed-parent coordinate legal when the author declares a key. It does not remove this path, it makes it load-bearing. The author who writes the field and has not yet declared a key is precisely the entry point to R648's decision tree, and under today's precedence their case is still swallowed by the argument-mismatch arm and answered with advice about GraphQL arguments. R648's diagnostic cannot surface until this ordering is fixed, so this is a prerequisite rather than a subset.

## Scope notes

* Scoped to the precedence rule, not to any particular message. The replacement text for the record-backed-parent case belongs to R648, whose design determines what the right guidance is; writing it here would mean writing it twice.
* The interim (reporting the existing deferred rejection instead of the argument mismatch, better than today even though its wording prescribes the wrong fix) was worth doing only while R648 was unstarted. R648 is now in Spec, so drop it and do the real fix.
* R648's Spec proposes the shape: split the service boundary into decode (reflect the method into a typed signature fact carrying per-parameter name, declared type, and the recognised `SourcesShape`), classify the coordinate over that fact, then bind parameters. That makes this item's precedence rule a reordering of pure steps rather than surgery inside `ServiceCatalog.reflectServiceMethod`'s parameter loop, and it lets R648 pass its coordinate answer as a value instead of a callback. Read that section before picking this up; the two items must not each install their own ordering at this seat, and this one owns it.
* Check whether the same ordering masks coordinate-level rejections elsewhere in the resolver family before fixing it narrowly at one call site.
