# R985 acceptance on sis: the capture composes the federation @link

Observed 2026-10-05 on the sis schema (`sis-graphql-spec`, `mvn graphitron:dev` and
`mvn generate-sources`), comparing two jars built from trunk: the baseline at `c74984cab`, the
commit before R985's first code commit, and the fix at `64192113c`. Outside `roadmap/` the two
trees differ by R985's two commits (`5235a5859`, `96506ea67`) and nothing else, so every
difference below is R985's. Both jars were installed into session-private local repositories
chained over the shared one, so sis resolved its own modules from the shared repository and
graphitron from the jar under test.

sis is federated and configures `<schemaInput tag>` on all three of its inputs, so the run covers
both halves of the composition: the federation `@link`'s imports and the tag rewrites.

## Result

| measure | baseline | fix |
|---|---|---|
| store freshness (`status`) | Previous | Current |
| `graphql_schema_problem` rows | 109 | 0 |
| of which `ASSEMBLY` / `DirectiveUndeclaredError` | 109 | 0 |
| `intent_type_domain` rows | 0 | 2320 |
| rows in the five domain-scoped defect relations, unscoped (below) | 0 | 0 |
| MCP `diagnostics` entries | 2461 | 2352 |
| generator build errors (`generate-sources` and the dev goal's initial run) | 615 | 615 |

The five defect relations are `intent_node_id_decode_defect`, `intent_node_id_decode_landing_defect`,
`intent_node_id_polymorphic_decode_defect`, `intent_authored_claim_conflict` and
`intent_field_unlowerable_ordering`.

The undeclared-directive count is zero, as the item requires. The 109 rows (the R876 audit counted
108 on an older schema) are the only diagnostics that differ: a full diff of the two `diagnostics`
listings, keyed on severity, source, coordinate, message and location, finds those 109 only in
the baseline and nothing only in the fix.

## What the newly live checks raise

Nothing. The domain is populated after the fix, so the checks that scope by it now run on sis,
and they report no coordinate. Their underlying relations are empty before any domain filter is
applied, so the silence is not the filter: none of these rules fires anywhere on this schema. No
false positive was found, so nothing is added to the item's `depends-on`, and no true defect was
found either.

## The build errors are sis's own, and unchanged

Both jars fail sis's build with the same 615 errors, all tenancy: 595
`Rejection.AuthorError.NoTenantBinding` and 20 `Rejection.AuthorError.UnroutedServiceCall`. They
come from sis's spike `<tenantColumn>INSTITUSJONSNR_EIER</tenantColumn>` (marked "not for commit"
in its pom) and are what the R876 audit already recorded, then 636. R985 neither adds nor removes
any.

## Out of scope here

The remaining store diagnostics are the same on both jars and are not R985's: the entry defects
the R876 audit covers (`GRAPHITRON_NO_ROUTE_FROM_DEPARTURE` 98, `GRAPHITRON_CHAIN_WITHOUT_TARGET`
48), the larger `GRAPHITRON_CODE_REFERENCE_METHOD_NOT_FOUND` (809) and
`GRAPHITRON_ELEMENT_UNRESOLVED` (419) populations, and lint warnings.

A second federated consumer, `opptak-subgraph`, showed 242 `DirectiveUndeclaredError` rows on a
pre-fix jar before this run started. It was not re-run on the fix.
