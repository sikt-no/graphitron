---
id: R167
title: Unify schema file extension handling between schemaInputs and graphitron:dev
status: In Review
bucket: feature
priority: 5
depends-on: []
created: 2026-05-15
last-updated: 2026-05-15
---

# Unify schema file extension handling between schemaInputs and graphitron:dev

> Three places in `graphitron-maven-plugin` independently decide "what counts
> as a schema file": the `<schemaInputs>` post-scan filter (today implicit in
> the user's glob), the `graphitron:dev` watcher (hard-coded `.graphqls`), and
> `SchemaProblemDiagnostic`'s orphan scan (hard-coded `.graphql`/`.graphqls`).
> Consumers with `.graphql` files hit each of the three as a separate
> problem. Lift the list onto a single `<schemaFileExtensions>` Mojo
> parameter, thread it through `RewriteContext`, and let all three sites read
> it.

## Motivation

The three drift sites:

1. **`SchemaWatcher.GRAPHQLS_SUFFIX`** (`SchemaWatcher.java:41`). Hard-coded
   `.graphqls`. The `graphitron:dev` schema watcher passes this through
   `SchemaWatcher`'s two-arg constructor (`SchemaWatcher.java:58`), so an edit
   to `regelverkMutations_exp.graphql` produces no debounced trigger and the
   generator never re-runs. Today the only workaround is to rename every
   `.graphql` to `.graphqls`.

2. **`<schemaInputs>` glob expansion** (`SchemaInputExpander.expand`,
   `SchemaInputExpander.java:32`). The expansion runs whatever pattern the
   user writes. Consumers with mixed `.graphqls`/`.graphql` trees either
   duplicate `<schemaInput>` blocks (one pattern per extension) or write a
   wider pattern like `**/*` and accept noise from unrelated files. There is
   no policy-level "schema files end in these suffixes" knob; the policy
   smears into the user's glob.

3. **`SchemaProblemDiagnostic.findOrphanSchemaFiles`**
   (`SchemaProblemDiagnostic.java:121-124`). Hard-codes both extensions
   directly:
   ```java
   .filter(p -> {
       String n = p.getFileName().toString();
       return n.endsWith(".graphql") || n.endsWith(".graphqls");
   })
   ```
   A third independent list, with a different default from the watcher.

Concrete consumer pain: Opptak's `regelverkMutations_exp.graphql` ships under
a `<schemaInput><pattern>**/*</pattern></schemaInput>` block so the build
loads it, but `graphitron:dev` silently ignores edits to it, and any orphan
diagnostic only happens to mention it because of the diagnostic's separate
hard-coded `.graphql` branch. Three answers to the same question, none of
them centralised.

A secondary motivation: the trailing `/*.graphqls` on each `<pattern>`
conflates "where to look" with "what counts as a schema file". A
`<pattern>schema/**</pattern>` plus a separate `<schemaFileExtensions>`
parameter cleanly separates the two axes; the conflated form keeps working
unchanged for backward compatibility, but newer setups can use the cleaner
shape.

## Design

A single Mojo parameter on `AbstractRewriteMojo`:

```java
/**
 * File-name suffixes that count as GraphQL schema files. Drives the
 * <schemaInputs> post-scan filter, the graphitron:dev watcher's trigger
 * filter, and the SchemaProblemDiagnostic orphan scan. Suffixes are matched
 * with String.endsWith on the file-name component; leading dots are
 * optional (both ".graphqls" and "graphqls" normalise to ".graphqls").
 * Case-sensitive on case-sensitive filesystems; Graphitron does not
 * lower-case.
 */
@Parameter
List<String> schemaFileExtensions;
```

The Mojo populates `RewriteContext.schemaFileExtensions(): Set<String>`
through the existing `buildContext` path. Three consumer sites read it:

| Site | Today | After |
|------|-------|-------|
| `SchemaInputExpander.expand` | Trusts whatever the glob matched | Drops scanner matches whose filename does not end in any configured extension |
| `SchemaWatcher` (schema mode) | Hard-coded `.graphqls` suffix | Accepts any configured extension; constructor takes `Set<String>` instead of single `String` |
| `SchemaProblemDiagnostic.findOrphanSchemaFiles` | Hard-coded `.graphql`/`.graphqls` | Reads `RewriteContext.schemaFileExtensions()` |

`RewriteContext` grows one field:

```java
public record RewriteContext(
    List<SchemaInput> schemaInputs,
    Set<String> schemaFileExtensions,   // NEW
    Path basedir,
    ...
)
```

The six- and seven-arg overloads default it to the standard
`[".graphqls", ".graphql"]` set so unit-tier callers stay one-liners.

### Default

`Set.of(".graphqls", ".graphql")`. Rationale:

- Matches the orphan scanner's existing behaviour. A narrower default
  (`.graphqls` only) would silently shrink the orphan diagnostic from "both
  extensions" to "one extension" — a usability regression on a code path
  whose purpose is to be louder than the silent default.
- A consumer who has loaded `.graphql` files via a wide `<pattern>` and runs
  `graphitron:dev` should see saves trigger regeneration without first
  discovering that the parameter exists. Default-off there is a footgun.
- Teams that want strict policy ("only `.graphqls` is schema; `.graphql`
  means client query documents") opt in to the tighter list explicitly:
  `<schemaFileExtensions><extension>.graphqls</extension></schemaFileExtensions>`.

The Backlog body raised "default to `[\".graphqls\"]` (boss's suggestion)"
as an alternative. The tradeoff is real, but the orphan-scanner regression
plus the dev-mode silent-ignore behaviour both point the same direction;
the strict policy is a one-line POM override, the lenient policy is the
ergonomic floor.

### Validation

- Empty list rejected at Mojo execute with a clear message
  ("`<schemaFileExtensions>` must contain at least one entry; omit the
  parameter to accept the default"). An empty list means "watch nothing,
  scan no orphans, filter every match away" — a footgun whose only correct
  recovery is to remove the configuration.
- Each entry trimmed; entries without a leading dot get one prepended; case
  preserved (Linux matches case-sensitively, the user's choice).
- Duplicate entries collapsed silently into the `Set`.

### What does *not* change

- The `<pattern>` glob semantics. `<pattern>**/*.graphqls</pattern>` keeps
  working unchanged: the filter is a no-op on already-matching files. A
  follow-up cleanup item (out of scope here) can lift the trailing
  `/*.graphqls` off each pattern so patterns describe directories only, but
  R167 is purely additive.
- `SchemaWatcher`'s `.class` mode for the classpath watcher. That
  constructor still takes a single suffix string; only the schema-mode
  constructor moves to `Set<String>`.

## Implementation

File-by-file changes. Single commit unless review surfaces a split.

1. **`RewriteContext`** (`graphitron/src/main/java/no/sikt/graphitron/rewrite/RewriteContext.java`).
   - Add `Set<String> schemaFileExtensions` after `schemaInputs`.
   - `Objects.requireNonNull` + `Set.copyOf` in the canonical constructor.
   - Update the six- and seven-arg overloads to default to
     `Set.of(".graphqls", ".graphql")`.

2. **`AbstractRewriteMojo`**
   (`graphitron-maven-plugin/src/main/java/.../AbstractRewriteMojo.java`).
   - New `@Parameter List<String> schemaFileExtensions;` field.
   - New `private Set<String> effectiveSchemaFileExtensions()` helper:
     normalises (leading-dot, trim), rejects empty, returns `Set.of(...)`
     default when the field is null.
   - `buildContext` calls it once and passes the resulting `Set<String>`
     both into `SchemaInputExpander.expand` and into the `RewriteContext`
     constructor.

3. **`SchemaInputExpander`** (`SchemaInputExpander.java`).
   - `expand` signature gains `Set<String> schemaFileExtensions`.
   - After the scanner returns matches, filter out any whose filename does
     not end in a configured extension. Keep the existing "matched no
     files" error path (the post-filter result, not the pre-filter result,
     is what's reported empty — message updates accordingly).

4. **`SchemaWatcher`** (`watch/SchemaWatcher.java`).
   - Add a new constructor variant taking `Set<String> filenameSuffixes`,
     storing it as a `Set<String>` field.
   - `dispatch` checks `filenameSuffixes.stream().anyMatch(relative.toString()::endsWith)`.
   - Keep the existing single-suffix constructor for the `.class` classpath
     watcher (delegates to the new one with `Set.of(filenameSuffix)`).
   - The schema-mode call site (`DevMojo.startSchemaWatcher`) reads
     `ctx.schemaFileExtensions()` and passes the set in.

5. **`SchemaProblemDiagnostic`** (`SchemaProblemDiagnostic.java`).
   - `format` signature gains `Set<String> schemaFileExtensions` (or, more
     naturally, take the full `RewriteContext` since the call site already
     has it). `findOrphanSchemaFiles` switches its hard-coded predicate to
     `extensions.stream().anyMatch(filename::endsWith)`.
   - Caller in `AbstractRewriteMojo.runGenerator` passes
     `ctx.schemaFileExtensions()` through.

6. **`DevMojo`** (`DevMojo.java`).
   - `startSchemaWatcher` uses the new `SchemaWatcher` constructor with
     `ctx.schemaFileExtensions()`.
   - Javadoc on the class updates `Watches {@code <schemaInputs>} for
     {@code .graphqls} writes` to reflect the configurable suffix set.

## Tests

Test seams stay where they already are. Each existing test file gets at
least one new case.

- **`SchemaInputExpanderTest`**
  - `expand_filtersFilesNotMatchingConfiguredExtensions`: glob matches
    `schema.graphqls` and `README.md`, extensions `[".graphqls"]`, only the
    schema is returned.
  - `expand_acceptsDotlessExtensionInput`: extensions `["graphqls"]` are
    normalised to `[".graphqls"]` and match.
  - `expand_emptyListRejected`: empty set rejected by the
    `effectiveSchemaFileExtensions` normaliser (test at the Mojo seam, not
    the expander, since the expander never sees the empty set).
  - `expand_dotGraphqlAccepted`: extensions `[".graphqls", ".graphql"]`,
    files `schema.graphqls` and `extras.graphql` both come back.
- **`SchemaWatcherTest`**
  - `dispatch_triggersOnDotGraphql_whenConfigured`: suffix set
    `{".graphqls", ".graphql"}`, synthesised `a.graphql` event fires the
    callback.
  - `dispatch_ignoresUnconfiguredSuffix`: suffix set `{".graphqls"}`,
    `a.graphql` event does not fire.
  - Existing `.graphqls` cases continue to pass under the default set.
- **`SchemaProblemDiagnosticTest`**
  - `findOrphanSchemaFiles_respectsConfiguredExtensions`: tighten the
    parameter to `{".graphqls"}`, drop a `.graphql` file under
    `src/main/`, assert it is *not* reported as an orphan. Loosen the
    parameter to `{".graphqls", ".graphql"}`, same setup, assert it *is*
    reported.
- **`AbstractRewriteMojo` / `RewriteContext`** unit coverage for the
  normaliser: empty rejected, missing-dot normalised, duplicates collapsed.

No pipeline- or execution-tier test is needed; the change is purely a
configuration plumbing lift and the three behaviours it drives are unit
testable in isolation.

## User documentation (first-client check)

A new short section in `docs/getting-started.adoc`, placed near the existing
`<schemaInput>` description, drafted here:

> **Configuring schema file extensions.**
>
> By default, Graphitron treats files ending in `.graphqls` or `.graphql` as
> schema. Both extensions are accepted out of the box; you do not need to
> configure anything if you mix them, or use only one.
>
> If your team's convention reserves `.graphql` for client query documents
> (executable operations), restrict Graphitron to schema files only:
>
> ```xml
> <plugin>
>   <groupId>no.sikt.graphitron</groupId>
>   <artifactId>graphitron-maven-plugin</artifactId>
>   <configuration>
>     <schemaFileExtensions>
>       <extension>.graphqls</extension>
>     </schemaFileExtensions>
>     ...
>   </configuration>
> </plugin>
> ```
>
> The configured set drives three places: which scanner matches are kept
> when expanding `<schemaInputs>`, which file saves trigger
> `graphitron:dev`'s regenerate loop, and which files under `src/main/` are
> reported as orphans when schema validation fails. Keeping all three in
> sync is the point: a list of extensions you cannot edit is the kind of
> hard-coded default that surfaces as silent dev-loop misses.

The first-client check: a consumer who has `.graphql` files and reads the
default behaviour ("both extensions are accepted out of the box") should
need zero further configuration. A consumer enforcing the stricter
convention sees a one-block override. If the docs read more complex than
that, the design is wrong.

When the feature ships, this section moves into its real home in
`getting-started.adoc` next to the `<schemaInput>` configuration paragraph
(around line 158); the existing prose on `.graphqls` is updated to say
`.graphqls`/`.graphql` where appropriate.

## Out of scope

- **Pattern cleanup.** Dropping the trailing `/*.graphqls` off
  `<pattern>` declarations (so patterns describe directories only) is a
  separate refactor; pre-existing patterns keep working unchanged. File a
  follow-up Backlog item if/when the cleaner pattern shape is desired.
- **Case folding.** Linux is case-sensitive; we do not lower-case
  extensions. A user who writes `.GraphQLs` gets `.GraphQLs`. If a
  cross-platform mismatch ever surfaces, a separate Backlog item can lift
  the policy.
- **Bundled directive files** (e.g. `directives.graphqls` loaded by
  `LspVocabulary`). Those are classpath resources, not consumer-configured
  schema inputs; their file names stay hard-coded.
