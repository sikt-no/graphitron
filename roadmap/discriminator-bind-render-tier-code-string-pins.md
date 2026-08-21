---
id: R774
title: "The discriminator typed-bind render-tier code-string pins duplicate what the SQL baselines already pin"
status: Backlog
bucket: tech-debt
priority: 3
theme: testing
depends-on: []
created: 2026-08-21
last-updated: 2026-08-21
---

# The discriminator typed-bind render-tier code-string pins duplicate what the SQL baselines already pin

The enum-discriminator fix landed the same fact at two tiers, and the cheaper one is the
authoritative tier. Four assertions now read the emitted method body as a string to check that a
`@discriminator(value:)` operand is `DSL.val(..., <COL>.getDataType())`:
`RootLauncherRendererTest.discriminatedSource_appliesDiscriminatorFilter`,
`discriminatedSource_joinedDetailOnClause_comparesAgainstAColumnTypedBind`,
`discriminatedSource_crossTableGate_comparesAgainstAColumnTypedBind`, and
`TypeFetcherGeneratorTest.queryTableInterfaceField_discriminatorSubselectGate_qualifiesOffTableInstance`.
The same four emission sites are pinned as rendered SQL by the enum-discriminated fixture families:
`RootLauncherSqlBaselineTest` carries the `IN` filter, the joined-detail ON clause and the
cross-table gate as `cast(? as "public"."content_kind")` / `"subject_kind"`, `DmlSqlBaselineTest`
carries the `values(...)` write, and `BatchedChildSqlBaselineTest` carries the batched arms. The SQL
baselines are what `development-principles.adoc` names as the code-string ban's replacement ("the
compile and execution tiers replace them"), so the render-tier strings are the redundant copy, and
the one that breaks on any javapoet or fragment refactor that leaves behaviour intact.

Two smaller things travel with it. `TypeFetcherGeneratorTest`'s carve-out is documented as being
about *qualification* ("the discriminator column must qualify to the table jOOQ renders in the FROM
clause"), and its two siblings truncate their pinned string at `.as("__discriminator__")` and `.in(`
respectively for exactly that reason; the subselect-gate one now pins the operand as well, which is
scope its stated rationale does not cover. And `RootLauncherRendererTest`'s class javadoc still says
"Structural properties only", explaining at length why body strings are avoided for the connection
arm, while the class holds 35 `contains(` assertions.

The work is to decide which render-tier pins earn their place, delete the rest, and make the
surviving carve-out's rationale explicit in the class javadoc so the next reader knows why these
are the exception. Filed at the enum-discriminator item's Done gate, where the additions were
contract-conformant (its spec asked for them) but the redundancy was not weighed.
