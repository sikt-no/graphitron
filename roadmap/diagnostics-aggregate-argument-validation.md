---
id: R633
title: "The aggregate rejects unknown argument values instead of defaulting"
status: Backlog
bucket: feature
priority: 7
theme: diagnostics
depends-on: []
created: 2026-08-11
last-updated: 2026-08-11
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
discoverable from the schema the way `groupBy`'s is. The fix is the same shape as
`Dimension.of`: a two-value closed decode, refusing anything else with both values named, plus
the `enum` in the input schema.

`severity` casing is the second, smaller case. `DiagnosticsTool`'s `severity` sugar lowercases
its argument before comparing, while the shared `where` path does not, so `severity: "ERROR"`
filters and `where: {severity: "ERROR"}` matches nothing. Both spellings are documented as the
same filter over the same column, and the aggregate's own group keys are lowercase, so
group-key drill-down parity is unaffected and no shipped pin fails; what is affected is an agent
that types the wire value by hand from the tool description's `"error"` / `"warning"` pair in
the wrong case and reads zero rows as a clean schema. Settle in Spec whether the answer is
normalising at the `where` boundary for the closed-taxonomy dimensions (`severity`, `source`,
`kind`, `actionable` already coerces) or refusing a value outside a declared closed set, which
is the stronger form and the one the null-safe-absence discipline complicates: `null` must keep
meaning the absent bucket rather than becoming an invalid value.

