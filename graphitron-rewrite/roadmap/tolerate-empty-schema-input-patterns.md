---
id: R175
title: Tolerate empty <schemaInput> pattern matches (warning, not hard error)
status: In Review
bucket: dx
priority: 3
theme: structural-refactor
depends-on: []
created: 2026-05-18
last-updated: 2026-05-18
---

# Tolerate empty <schemaInput> pattern matches (warning, not hard error)

> Per-pattern empty match in `<schemaInputs>` becomes a build warning; the hard
> error is reserved for the aggregate-empty case (every configured pattern
> matched zero, after extension filtering). Consumers can stub feature buckets
> ahead of having content without wedging `graphitron:dev` and
> `graphitron:generate`.

## Motivation

`SchemaInputExpander.expand` (`graphitron-rewrite/graphitron-maven-plugin/src/main/java/no/sikt/graphitron/rewrite/maven/SchemaInputExpander.java:46-49`)
throws `MojoExecutionException` the moment any single `<schemaInput>` pattern
resolves to zero matching files:

```java
if (matches.isEmpty()) {
    throw new MojoExecutionException(
        "<schemaInput pattern='" + b.pattern + "'> matched no files (entry #" + i + ")");
}
```

Consumers commonly stub feature buckets ahead of having content. The
opptak-subgraph pom declares three patterns side by side:

```xml
<schemaInputs>
    <schemaInput><pattern>schema/stable/**/*.graphqls</pattern>           <tag>stable</tag></schemaInput>
    <schemaInput><pattern>schema/beta/**/*.graphqls</pattern>             <tag>beta</tag></schemaInput>
    <schemaInput><pattern>schema/experimental/**/*.graphqls</pattern>     <tag>experimental</tag></schemaInput>
</schemaInputs>
```

An empty `beta/` directory (e.g. holding only a `description-suffix.md` and no
`.graphqls` files yet) wedges `graphitron:dev` and `graphitron:generate` with
a hard build error, even though `stable/` and `experimental/` each match
content and the aggregate schema set is well-formed. The fix today is to
either (a) delete the `<schemaInput>` entry for the empty bucket and re-add it
when content arrives, or (b) drop a placeholder `.graphqls` stub into the
bucket. Both are paper cuts that nudge the schema set away from the shape the
author actually wants to express.

The aggregate-empty case (every configured pattern matched zero, or the only
pattern was empty) is genuinely a misconfiguration: the generator has no
schema to read. That should stay a hard error.

## Design

Two semantic changes to `SchemaInputExpander.expand`:

1. **Per-pattern empty match warns and continues.** Instead of throwing, the
   expander records a structured warning for the empty pattern, skips the
   binding, and proceeds with the next entry.
2. **Aggregate-empty error survives.** When the bindings list is non-empty
   but every pattern resolved to zero matches (so the returned input list is
   empty), the expander throws `MojoExecutionException` with a message that
   names every empty pattern and entry index, so the user sees the same
   actionable detail today's "matched no files" error gives but rolled up.

The bindings-list-empty case (caller passed `null` or `List.of()`) is
unchanged: the expander returns `List.of()` silently. That branch represents
"the consumer configured no `<schemaInputs>` at all", which is the
plugin-default no-schema case; a downstream check rejects it (or not) per
goal. This plan does not move that gate.

### Return shape

The static `expand` helper currently returns `List<SchemaInput>`. To surface
per-pattern warnings without giving the helper a `Log` dependency (which would
couple it to Maven's logging API and complicate the test surface), the return
type widens to a plain carrier record:

```java
record ExpansionResult(List<SchemaInput> inputs, List<EmptyPattern> emptyPatterns) {
    record EmptyPattern(int entryIndex, String pattern) {}
}
```

This follows *Builder-step results are sealed, not strings or out-params* in
spirit: the helper hands back typed multi-value data and lets the caller
decide how to surface it. (The record is not literally sealed since there is
no variant axis: one shape, two list slots.) Tests assert on
`emptyPatterns()` directly; the `AbstractRewriteMojo` caller iterates and
forwards each entry to `getLog().warn(...)`.

### Aggregate-empty detection

After the per-binding loop, the expander checks:

```java
if (!bindings.isEmpty() && expanded.isEmpty()) {
    // every pattern was empty; aggregate-empty is an error
    throw new MojoExecutionException("...");
}
```

The error message lists each empty pattern with its `entryIndex`, e.g.:

```
<schemaInputs> matched no files. Empty patterns:
  entry #0: schema/stable/**/*.graphqls
  entry #1: schema/beta/**/*.graphqls
  entry #2: schema/experimental/**/*.graphqls
```

This preserves the actionable detail the current single-pattern error
provides (caller can see exactly which pattern is empty), while making the
aggregate case visually distinct.

### Warning text

Per `getLog().warn` call:

```
<schemaInput pattern='schema/beta/**/*.graphqls'> (entry #1) matched no files; skipping
```

Mirrors the existing error wording so anyone with muscle memory for the old
message recognises it. The `; skipping` suffix signals that this is a
soft-fail, not a hard one.

## Implementation

- `graphitron-rewrite/graphitron-maven-plugin/src/main/java/no/sikt/graphitron/rewrite/maven/SchemaInputExpander.java`
  - Add nested record types `ExpansionResult` and `ExpansionResult.EmptyPattern`.
  - Change `expand`'s return type from `List<SchemaInput>` to `ExpansionResult`.
  - On per-binding empty match, append to a local `emptyPatterns` list instead
    of throwing; continue the loop.
  - After the loop, if the input bindings list was non-empty and the expanded
    `inputs` list is empty, throw `MojoExecutionException` with the
    aggregate-empty message listing every empty pattern.
  - Otherwise return `new ExpansionResult(expanded, emptyPatterns)`.

- `graphitron-rewrite/graphitron-maven-plugin/src/main/java/no/sikt/graphitron/rewrite/maven/AbstractRewriteMojo.java`
  - At the `SchemaInputExpander.expand(...)` call site in `buildContext`
    (`AbstractRewriteMojo.java:124`), capture the `ExpansionResult`, forward
    each `EmptyPattern` to `getLog().warn(...)` with the warning text above,
    then thread `result.inputs()` into the `RewriteContext` constructor.

- `graphitron-rewrite/graphitron-maven-plugin/src/test/java/no/sikt/graphitron/rewrite/maven/SchemaInputExpanderTest.java`
  - Update existing assertions that destructure the returned list to read
    `result.inputs()` instead.
  - Repoint `zeroMatchPattern_throwsMojoExecutionException` and
    `expand_zeroMatchAfterExtensionFilter_throwsMojoExecutionException` at
    the new aggregate-empty message (single empty pattern still throws
    because the aggregate set is empty).
  - Add new tests below.

## Tests

All pipeline-internal; the expander is a pure helper, so unit-tier is the
correct tier (per *Pipeline tests are the primary behavioural tier*; this
isn't a pipeline-level concern, it's a single-class behavioural change).

New cases in `SchemaInputExpanderTest`:

- `multiplePatterns_oneEmpty_warnsAndContinues`: three bindings, middle one
  matches nothing. Assert `result.inputs()` contains the matches from the
  other two; `result.emptyPatterns()` holds exactly one entry, naming the
  middle binding's pattern and `entryIndex = 1`.
- `multiplePatterns_oneEmpty_afterExtensionFilter_warnsAndContinues`: a
  binding whose raw glob matches files but whose extension filter culls them
  all (e.g. a bucket holding only `description-suffix.md`). Same shape as
  above: recorded as an empty pattern, not an error, when other bindings
  match content.
- `allPatternsEmpty_throwsAggregateEmpty`: two bindings, neither matches.
  Assert the thrown `MojoExecutionException` message names both patterns and
  both entry indices.
- `singlePatternEmpty_throwsAggregateEmpty` (replaces today's
  `zeroMatchPattern_throwsMojoExecutionException`): one binding, no matches.
  Assert the thrown message follows the aggregate-empty shape.
- Existing positive-path tests are updated to call `result.inputs()` rather
  than `result` directly. No new positive-path coverage needed.

The opptak-subgraph pattern (three buckets, middle empty) is the motivating
shape; the multi-binding test above directly mirrors it.

## Non-goals

- Promoting the empty-pattern warning to a configurable severity (e.g.
  `<onEmptyPattern>warn|error|ignore</onEmptyPattern>`). The plan treats
  "empty pattern is fine, aggregate-empty is not" as the right default; a
  per-pattern severity knob would solve a problem nobody has reported.
- Touching the bindings-list-empty branch
  (`bindings == null || bindings.isEmpty()`). Whether "no `<schemaInputs>` at
  all" is an error is a separate question owned by the goal-specific code
  (validate vs. generate vs. dev) downstream of `buildContext`.
- Surfacing the warning anywhere other than `getLog().warn(...)`. The dev
  watcher (`graphitron:dev`) re-runs `buildContext` on each rebuild and will
  re-emit the warning on each cycle; that is the desired behaviour (the
  warning is a live state, not a one-shot event).
- Mining for other "throws on empty" sites in the maven plugin. This plan
  fixes the one site the consumer hit; an audit of similar gates is a
  separate backlog item if it surfaces a pattern.
