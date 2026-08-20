---
id: R633
title: "The aggregate rejects unknown argument values instead of defaulting"
status: Spec
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

## Implementation

`DiagnosticFacets`:

* Add an `Ordering` enum beside `Dimension` with `COUNT("count")` and `KEY("key")`, an `of`
  resolver in `Dimension.of`'s shape (exact match, refusing with both values named), and a
  `wireNames()` for the input schema. Exact match rather than case-insensitive, on
  `Dimension.of`'s standing precedent: the refusal carries the whole set, so a `"COUNT"` costs
  one retry with the answer already in hand, and the server stays no more lenient than the
  `enum` its input schema declares.
* Read the argument the way `groupByDimensions` reads `groupBy`: the raw value from the map,
  absent means `COUNT`, anything present goes through `String.valueOf` into `Ordering.of`. This
  is what makes a non-string (`orderBy: 5`) a refusal too, where `McpWire.stringArg` would drop
  it to the default; `stringArg` is the lenient coercion the numeric arguments want and the
  wrong reader for a closed vocabulary.
* Add a nested `Spelling` enum (`AS_STORED`, `LOWER_CASE`, `UPPER_SNAKE`) with a
  `normalise(String)`, root-locale throughout, and a fourth `Dimension` constructor argument
  carrying it. A three-argument constructor delegates with `AS_STORED`, so the four declaring
  rows are the whole diff and the default is the no-op: normalising is the claim that needs a
  justification, storing as written is not. `SEVERITY` and `SOURCE` take `LOWER_CASE`, `KIND` and
  `ATTEMPT_KIND` take `UPPER_SNAKE`.
* Apply the spelling in `coerce`, so the one boundary both tools already share is the one place
  it happens. `matchesStored` stays untouched, which is what keeps the aggregate's own group keys
  out of the normalisation path entirely.

`DiagnosticsTool`: drop the `severity` sugar's own `toLowerCase(Locale.ROOT)`, now redundant
against the boundary, and the `Locale` import with it if nothing else needs it.

`GraphitronMcpServer`: declare `"enum", DiagnosticFacets.Ordering.wireNames()` on the aggregate
tool's `orderBy` property, beside its description, the way `groupBy` declares the dimension
names.

No documentation change. `docs/manual/how-to/mcp-agent-context.adoc` describes the tool one
sentence deep and names no arguments; the input schema and the tool description are the contract
here, and both are rendered from the code this item edits.

## Tests

`DiagnosticsAggregateTest`, on the existing SDL fixture and its store-backed build, asserting on
tool answers rather than internals as the class already does:

* An unknown `orderBy` is refused, with both orderings named in the message, for `"cuont"` and
  for `"COUNT"`.
* `orderBy: "count"` answers identically to the argument being absent, and `orderBy: "key"`
  orders by the group key. This is the first coverage the argument has had either way.
* The `severity` sugar and the `where` path agree on casing: `severity: "ERROR"`,
  `where: {severity: "ERROR"}` and `where: {severity: "error"}` all return the same entries.
* The kind spelling `diagnostics` renders (`invalid-schema`) reads back through
  `where: {kind: ...}` as the same count the aggregate reports for the stored `INVALID_SCHEMA`
  group.
* Every distinct value the store holds for every dimension is its own normal form, read through
  the aggregate's group keys. This is the pin that makes normalisation safe: it fires if a
  dimension declares a spelling the store contradicts, which is the only way normalisation could
  drop a row that a raw comparison would have matched.
