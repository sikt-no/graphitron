# What the graphitron decodes actually need, and what a stored literal would have cost

An analysis artifact, not a roadmap item: it lives in the subdirectory the roadmap-tool ignores, and
it is Markdown so `check-adoc-tables` leaves it alone.

It carries the measurements of a discarded plan. That plan proposed that the directive decode read
the transcribed rows rather than the AST, and, once it did, that the transcription store an
argument's value as a tree of rows or decode the printed literal inside an H2 function. Its move one
shipped and has since been reversed: the walk decodes each application from the value the parser
built, so nothing reads a printed literal back and the two alternatives have no consumer left. The
counts below were taken against the shipped family and a real corpus and are worth more than the
plan they were taken for, which is why they are here rather than in the deleted file.

The H2 function surface itself is measured in `2026-08-05-h2-functions-jooq-spike.md`, which reaches
the same conclusions about `LATERAL`, correlated table functions and the `SYSTEM_RANGE` idiom. What
is added below is the cost of driving that idiom with a graphql-java parse, and the classloader
argument against binding one behind an alias at all.

## The census: what each decode needs from an argument value

63 `graphitron_` tables at the time of counting, no views. Written by the decode through
`newRecord` (55), through its generic `marker` helper (5), and by macro expansion (3).

| Class | Relations | What the decode needs |
|---|---|---|
| presence-only marker | 5 | nothing; a filtered projection of `graphql_field_directive` |
| scalar or object literal, one row per application | 36 | value-literal reading |
| list literal, one row per element | 11 | value-literal reading plus a row-expansion idiom |
| sub-grammar inside a string | 11 | one of three parsers |

The eleven needing a real parser cluster on three of them. `GraphQLSelectionParser.parseEntries`
serves the eight `*_arg_mapping_pair` / `*_column_mapping_pair` relations, and served
`graphitron_argument_path_segment` until that relation was retired; `ArgMappingSigil.scan` serves
`graphitron_service_arg_mapping_sigil`; `FieldSetGrammar.paths` serves
`graphitron_federation_key_field`.

All three parsers are dependency-free: 274, 132 and 92 lines plus a 17-line record, `java.*` imports
only. So the module-inversion objection below applies to a graphql-java-backed function and not to
this family's actual parsing needs.

## Frequency, which is a different question from relation count

Across the corpus's nine `.graphqls` files and roughly 4236 directive applications: 64
`argMapping:`, 16 `columnMapping:`, 42 `@key(fields:)`. About 122 applications, near 3 per cent,
trip a sub-grammar; the rest are value literals. The distribution's head is `@field`, `@service`,
`@table`, `@nodeId`, `@reference`, all scalar or object literals.

Nine object-literal call sites are the ones that would have argued for structuring the value at
capture rather than re-parsing per read: `codeReference` for `@service`, `@condition`, `@record`,
`@enum` and `@externalField`, and `referenceElement` for the three `*_reference_step` relations.

## What parsing a stored literal cost

Measured against H2 2.4.240 and graphql-java 25, over 20 000 rows: 1690 ms parsing per call, about
85 microseconds each, essentially all of it graphql-java constructing an ANTLR parser per
invocation. 81 ms with a `ConcurrentHashMap` memo keyed on the input text, which made 202 actual
parses because directive argument values repeat heavily. 19 ms for a pure-SQL equivalent.

Two properties shape how such a view would have to be written. H2 caches whole query results, so an
identical second read cost 0 ms and made no calls, but the full cost returns after any write to the
driving table. And `DETERMINISTIC` does not memoize per value, so the same expression twice in one
row costs two calls, which the `ARRAY_GET` plus `CARDINALITY` idiom incurs by naming the function in
both.

## Why such a function must not have been graphql-java-backed

`CREATE ALIAS` fails loudly at DDL time when the class is missing, so every host booting the store
would need the class and its dependencies on its classloader: the language server, the Maven plugin,
the MCP surface, the tests, and the codegen driver, which executes this same DDL to generate the
`Tables` classes. `graphitron-model` depends on jOOQ, H2 and jooq-codegen, not graphql-java, so this
inverts a module dependency to put a large library below the schema module.
`GraphitronModelStore.connect()` already records that these hosts "all hand it a loader the
service-loaded driver was not registered under", so this adds a second class to a known hazard.

The degradation is asymmetric, which is the part worth remembering. A fresh boot without the class
fails loudly, but reopening an existing file without it does not: H2 drops the aliases silently and
the view becomes unreadable, and `store_stamp` covers the DDL hash and generator version, not the
classpath.

## The coverage surface, which still exists

`graphitron_undecoded_argument` records site, directive, argument and verbatim `value_sdl` for every
argument the decode declined, so any value grammar's coverage can be measured against a real corpus
instead of asserted. It held no rows for the sakila example when this was counted.
