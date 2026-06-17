---
id: R321
title: "One-shot mojos must render ValidationFailedException.errors() in the failure output (parity with DevMojo and the SchemaProblem branch)"
status: Ready
bucket: bug
priority: 3
depends-on: []
created: 2026-06-17
last-updated: 2026-06-17
---

# One-shot mojos must render ValidationFailedException.errors() in the failure output

When `mvn graphitron:validate` (or `generate`) fails on a schema raised
`ValidationFailedException`, the consumer sees only the count, never the errors.
A federation subgraph build (`opptak-subgraph`) surfaced this: the Maven failure
tail read `5 schema validation error(s)` with no `file:line:col` detail anywhere
in the log.

## Root cause

`ValidationFailedException` carries its structured `errors()`, but the one-shot
mojos route through `AbstractRewriteMojo.runGenerator`
(`graphitron-maven-plugin/src/main/java/no/sikt/graphitron/rewrite/maven/AbstractRewriteMojo.java`),
which special-cases `SchemaProblem` (formats a rich diagnostic into the
`MojoExecutionException` message) but lets `ValidationFailedException` fall
through to the generic `catch (RuntimeException e)` arm. That arm rethrows
`new MojoExecutionException(e.getMessage(), e)`, and the message is just the
count (`ValidationFailedException(List<ValidationError>)` sets
`super(errors.size() + " schema validation error(s)")`).

The detail-logging surface, `GraphQLRewriteGenerator.validateAndLogErrors`, emits
the clang-style `file:line:col: error: <message>` lines, but only for errors
returned by `GraphitronSchemaValidator`. Errors raised *before* that point never
pass through it. Two such early-throw sites:

- `GraphitronSchemaBuilder.buildBundle` (federation-recipe rewrap of a
  `SchemaProblem` from `makeExecutableSchema`, the path that bit
  `opptak-subgraph`).
- `TagLinkSynthesiser.apply`.

Both throw `ValidationFailedException` during the build phase, ahead of
`validate()`'s own log-then-throw step, so their carried errors are emitted
nowhere.

## The fix shape

`DevMojo.runGeneratorPass` already treats `ValidationFailedException` as
first-class: a dedicated `catch (ValidationFailedException e)` renders
`e.errors()` via `WatchErrorFormatter`, independent of where the exception was
raised. Give `AbstractRewriteMojo.runGenerator` a sibling
`catch (ValidationFailedException e)` arm (next to the existing `SchemaProblem`
arm) that formats `e.errors()` into the `MojoExecutionException` message, so the
build fails with the diagnostics in the copyable summary regardless of which
stage raised them.

The throw is the build-fail signal and stays; the gap is purely that the boundary
does not catch-and-render this exception type the way it does `SchemaProblem`.
For the federation-recipe case, accumulate-and-continue is not available
(`makeExecutableSchema` failing leaves no assembled schema to keep classifying),
so rendering at the mojo boundary is the right delivery point.

## Acceptance

- A schema that fails only at the `buildBundle` federation-recipe stage produces
  the per-error `file:line:col` detail in the Maven failure output, not just the
  count.
- Parity check: `ValidateMojo` / `GenerateMojo` failure output carries the same
  error detail that `DevMojo` already renders for the identical schema.
- Decide whether to reuse the clang-style single-line rendering from
  `validateAndLogErrors` or the grouped `WatchErrorFormatter` output, and factor
  the chosen renderer so the one-shot and dev paths cannot drift.
