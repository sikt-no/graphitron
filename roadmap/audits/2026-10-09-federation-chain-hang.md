# Why a capture of sis hung in the directive anchor, and what storing the federation chain bought

Measured 2026-10-09 on a store the sis capture held in its own transaction at the moment
`EmittedAnchor.directives` began its upsert, dumped with H2's `SCRIPT TO` from inside that session
and loaded into a fresh database. sis held 1,911 types, 7,134 fields, 6,892 authored directive
applications and 7,734 configured tags. Timings are single runs with a 60 or 120 second cap and
`OPTIMIZE_REUSE_RESULTS FALSE`.

## Where the time went

The capture sat at one core for 80 minutes inside the upsert, which reads the authored, configured
and minted directive applications. The minted set reads `graphitron_inherited_directive`, which
reads `graphitron_carrier_directive` three times, which joins `graphitron_configured_tag` to
`graphitron_minted_coinage`.

| Relation | Rows | Time |
|---|---|---|
| `graphitron_configured_tag` | 7,734 | 0.45 s |
| `graphitron_minted_coinage` | 654 | 0.13 s |
| `graphitron_carrier_directive` | 436 | 55 s |
| `graphitron_inherited_directive` | | over 60 s |
| `graphitron_directive_application_minted` | | over 60 s |

The configured tags end in a window function, so no join condition reaches inside them, and H2
put them outermost and ran the coinage view once per tag: 7,734 runs at about 7 ms. An `IN`
rewrite does not help, H2 turning it into the same join. The inherited view then runs the carrier
view again per row of its own joins.

## What storing bought

Each variant replaces views with indexed tables of the same rows on a scratch copy.

| Stored | Carriers | Inherited | Upsert input |
|---|---|---|---|
| nothing | 55 s | over 120 s | over 120 s |
| configured tags | 6.9 s | over 120 s | over 120 s |
| configured tags and coinage | 0.3 s | 28 s | 34 s |
| carriers only | 31 s to build | 4.5 s | 4.7 s |
| all three | 0.3 s | 0.16 s | 0.23 s |

Storing one layer moves the per-row re-run up a layer; only the three together end it, and building
them costs under a second. With them stored by `EmittedAnchor` the whole sis capture, to its
validation errors, takes 140 s.

## The type-backing rule on the same store

Timed after the fix with the probe of `2026-10-09-argmapping-and-derivation-timings.md`, interleaved
sweeps, on the store the fixed capture wrote: 622 backings either way.

| Rule | Average |
|---|---|
| exclusion through `graphitron_code_reference_site` (a view) | 9.8 s |
| exclusion through the per-site service and external-field entries | 3.9 s |
| exclusion through `graphitron_directive_application` (stored) | 4.0 s |
| argMapping through `graphitron_argmapping_entry` | 10.1 s |
| argMapping through the pair relation, keyed to the reference | 10.0 s |

The view in the exclusion was re-run per hop row; the stored applications answer the same question.
The argMapping move costs nothing, so the site relation needs no index for it. What is left, about
four seconds, is the recursion itself: the seed answers in 49 ms and the hop alone in 1.2 s.
