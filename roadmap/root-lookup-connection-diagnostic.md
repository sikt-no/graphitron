---
id: R670
title: "Root @lookupKey plus @asConnection reports a @table error the author cannot act on"
status: Backlog
bucket: bug
priority: 3
theme: diagnostics
depends-on: []
created: 2026-08-14
last-updated: 2026-08-14
---

# Root @lookupKey plus @asConnection reports a @table error the author cannot act on

Combining `@asConnection` with `@lookupKey` on a *root* field is rejected, which is correct, but the
message names an invariant the author never violated. Measured on a root field
`filmById(film_id: [Int!]! @lookupKey, first: Int, after: String): FilmConnection @asConnection`, the
field classifies to `UnclassifiedField` with the reason `@lookupKey requires a @table-annotated
return type`. The author did not write an untyped return; they wrote `@asConnection` on a lookup, and
the connection type the promoter minted is what is not `@table`-annotated. Following the advice as
given (annotate `FilmConnection` with `@table`) leads nowhere.

The cause is ordering. `LookupKeyDirectiveResolver.resolveAtRoot` checks only the target-table
invariant and never inspects the wrapper, so it fires first on the promoted connection type. Two
better messages already exist but are unreachable at this arm: `resolveAtChild` emits
`@asConnection on @lookupKey fields is invalid: @lookupKey establishes a positional correspondence
… which pagination would break. Drop @asConnection or drop @lookupKey`, and
`GraphitronSchemaValidator.validateRootLookup` emits `lookup fields must not return a connection`.
The validator message appears unreachable from real root SDL for the same ordering reason;
`RootLookupValidationTest`'s `CONNECTION_RETURN` cell reaches it only through a hand-built model,
so the cube is green while the path is dead from SDL.

The likely fix is a wrapper check in `resolveAtRoot` ahead of the table-boundness check, reusing the
child arm's message so both arms say the same thing, plus a pipeline-tier cell driving real SDL
through `TestSchemaHelper` (the existing root coverage is validator-tier only, which is how the
ordering gap stayed invisible). Worth deciding at Spec time whether the now-dead validator branch
should stay as a defence-in-depth invariant or be removed in favour of the classifier check.

Provenance: found at the third In Review gate on the lookup positional-contract item, while
verifying that item's claim that the rejection holds "on root and child fields alike". The rejection
does hold; only the message is wrong, which is why this is filed separately rather than blocking it.

