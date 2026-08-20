---
id: R633
title: "The aggregate rejects unknown argument values instead of defaulting"
status: In Review
bucket: feature
priority: 7
theme: diagnostics
depends-on: []
created: 2026-08-11
last-updated: 2026-08-20
---

# The aggregate rejects unknown argument values instead of defaulting

`diagnostics.aggregate` bets against a query language on the ground that a closed vocabulary
"cannot fail to parse" and that a name outside it "fails with the full vocabulary instead of a
parse error" (`DiagnosticFacets`' own class javadoc, and the reasoning the aggregate's design
rests on). The dimension names hold that bet: `Dimension.of` refuses an unknown name and names
every dimension in the refusal. Two scalar arguments do not.

`orderBy` is read as `stringArg(args, "orderBy").map("key"::equals).orElse(false)`
(`graphitron-mcp/src/main/java/no/sikt/graphitron/mcp/DiagnosticFacets.java`), so every value
that is not exactly `key` silently means `count`. An agent that writes `"cuont"`, or `"count
desc"`, or the enum-ish `"COUNT"`, gets a well-formed answer ordered the other way with nothing
in the response saying the argument was ignored, which is the same guess-and-retry cost the
closed set exists to remove, minus the retry: the agent has no signal that anything went wrong.
The tool's input schema does not declare an `enum` for the argument either, so the value is not
discoverable from the schema the way `groupBy`'s is.

`severity` casing is the second, smaller case. `DiagnosticsTool`'s `severity` sugar lowercases
its argument before comparing, while the shared `where` path does not, so `severity: "ERROR"`
filters and `where: {severity: "ERROR"}` matches nothing. Both spellings are documented as the
same filter over the same column, and the aggregate's own group keys are lowercase, so
group-key drill-down parity is unaffected and no shipped pin fails; what is affected is an agent
that types the wire value by hand from the tool description's `"error"` / `"warning"` pair in
the wrong case and reads zero rows as a clean schema.

## What decides the answer

The item left one question open for this spec: whether a `where` value outside a closed
taxonomy should be normalised or refused. Two facts settle it, and together they give one rule
covering both halves of the item.

The first is who owns the vocabulary a value is drawn from. `orderBy`'s two values are the
aggregate's own: they name the sort the code applies and are defined nowhere else, so the module
can name the whole set in a refusal and the set cannot rot behind that refusal. The value sets
behind `severity`, `source`, `kind` and `attemptKind` belong to the store. They are declared in
the `diagnostic` view's own literals, in the `rejection_validation_error.kind` CHECK, and in
`AttemptKind.name()`, and they grow as detections migrate store-native. A closed decode over
them would copy a taxonomy this module does not define, and the copy goes stale the first time
the store adds a value. That is the argument against the stronger form the item flagged, and it
holds independently of the null-safe-absence complication it also flagged.

The second is what a value names. `orderBy` names the shape of the answer, so ignoring it hands
back an answer to a question nobody asked, and nothing in the response says so. A `where` value
names data, so a value that is not in the data is a question with an honest empty answer, and
`null` keeps meaning the absent bucket because it is a value in the data too.

The rule: the module refuses values from vocabularies it owns, and normalises spelling for
vocabularies the store owns. Neither half ever silently answers a different question. The one
place a `where` value can look right and answer empty anyway is spelling, which is what the
severity half of the item is, so spelling is the part normalisation has to close.

Casing is safe to normalise here precisely because the module is not claiming to know the value
set, only the spelling the store writes it in: the view emits `severity` and `source` as
lower-case literals, and `kind` and `attemptKind` as upper-case names with underscores.
Normalisation is therefore the identity on every value the store actually holds, which keeps
group-key drill-down exact (an aggregate group key is a stored value, and it survives the
`where` boundary unchanged), and a test pins that property against the store rather than
trusting the reading.

`attemptKind` joins the three the item named because it is the same shape as `kind`, an
upper-case enum name, and because the `where` example in the tool's own description is
`{"attemptKind": "COLUMN"}`: a lower-case paste of that is the identical trap. `kind` gets the
hyphen-to-underscore swap along with the casing, which closes a second spelling hole between the
two tools: `diagnostics` renders the stored `INVALID_SCHEMA` on the wire as
`rejectionKind: "invalid-schema"`, and pasting that back into `where: {kind: ...}` currently
matches nothing.

The numeric arguments (`limit`, `minCount`, `examples`) keep clamping rather than refusing, and
the difference is not inconsistency: a clamped `limit` reports itself. `elidedGroups` and
`elidedCount` say exactly what the cap folded, which is the property that keeps a truncated
aggregate from reading as a complete one. A silently defaulted `orderBy` reports nothing.

## Landed

`DiagnosticFacets` grew two nested vocabularies beside `Dimension`. `Ordering` (`count`, `key`)
resolves through an `of` in `Dimension.of`'s shape, refusing an unknown value with both named;
the aggregate reads it off the argument map the way `groupBy` is read, so a blank or non-string
value is a refusal too rather than the silent default `McpWire.stringArg` would have given.
`Spelling` (`AS_STORED`, `LOWER_CASE`, `UPPER_SNAKE`) is a fourth `Dimension` constructor
argument, defaulted by a delegating three-argument constructor so only the four declaring rows
carry one: `severity` and `source` lower-case, `kind` and `attemptKind` upper-case with hyphens
folded to underscores. It applies in `coerce`, the one boundary both diagnostics tools already
share, so `DiagnosticsTool`'s `severity` sugar dropped its own `toLowerCase` and now agrees with
`where` by construction. `matchesStored` is untouched, which is what keeps an aggregate group key
out of the normalisation path and the drill-down exact. The aggregate tool's input schema declares
`enum` on `orderBy`, so the two values are discoverable without a failed call.

Five pins in `DiagnosticsAggregateTest`: an unknown `orderBy` refused with both orderings named
(a typo, a fuller sort expression, `"COUNT"`, and a non-string); `orderBy: "count"` answering
identically to the argument being absent while `"key"` sorts ascending on the group key with the
absent bucket at the tail; the `severity` sugar and three casings of the `where` value filtering
the same rows, with the group key coming back in the store's spelling; the kebab-case `kind` an
entry renders reading back through `where` as the same count; and, over every dimension, every
distinct value the store holds being its own normal form, which is the pin that makes a declared
spelling safe rather than merely plausible.

## Workflow note for the Done gate

This item went Backlog to In Review in one session at the user's direction ("create a worktree
and implement R633"), so the `Spec -> Ready` sign-off was not an independent review: the author,
the reviewer of record and the implementer are the same session. The spec body above is therefore
un-gated design, and the reviewer at this gate is the first fresh context to read it. Read the
"What decides the answer" section as a proposal rather than as an approved design, and reopen to
`Spec` if the refuse-what-you-own / normalise-what-the-store-owns split is the wrong rule.
