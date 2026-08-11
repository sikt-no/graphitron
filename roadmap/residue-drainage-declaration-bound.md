---
id: R632
title: "Bind the residue drainage declaration to the diagnostic view arms"
status: Backlog
bucket: test
priority: 6
theme: diagnostics
depends-on: []
created: 2026-08-11
last-updated: 2026-08-11
---

# Bind the residue drainage declaration to the diagnostic view arms

The rejection residue's drainage declaration is half-enforced. `RejectionResidueDrainageTest`
(`graphitron/src/test/java/no/sikt/graphitron/rewrite/diagnostics/RejectionResidueDrainageTest.java`)
carries two declarations. `RESIDUE_LEAVES` is genuinely pinned: it is compared against a
reflective walk of the sealed `Rejection` hierarchy, so a new rejection cause fails the test (and
`RejectionFacts`' exhaustive switch) until someone edits the declaration and decides its columns,
which is the property that keeps the residue from silently enlarging. `MIGRATED_FAMILY_VIEWS`,
the ledger of families that have gone store-native and therefore left the residue, is asserted
only non-empty. Nothing forces an edit when a family actually migrates: the flip leaves the leaf
set unchanged, as the test's own javadoc concedes (drainage is per family, not per leaf, and the
leaves stay because other walk sites still mint them), so the ledger is prose that happens to sit
in a test file. The drainage count is what the residue's transitional claim rests on, and a
ledger nothing maintains is exactly the failure the item that built it named twice: a javadoc
claiming transience over a test that cannot enforce it.

The binding is available. Each migrated family is a derivation arm of the `diagnostic` union
view, and the arms are enumerable: the view's `FROM` relations partition into the transcription
families (`rejection_`, `lint_`, `build_warning_`, `javac_`) and the derivation arms
(`intent_authored_claim_conflict` is the first). Assert `MIGRATED_FAMILY_VIEWS` against the
derivation arms read off the store's own metadata rather than off a second hand-written list, and
a new derivation arm forces the ledger edit on the commit that adds it. Worth settling in Spec:
whether the arm set is readable from jOOQ's generated metadata or from H2's
`information_schema.view_table_usage` (and whether the latter is stable enough to pin on), and
whether the same mechanism should also pin the converse direction, that every relation the view
unions is declared in exactly one of the two buckets, which would catch an arm added to the view
and declared nowhere. That converse is the shape `DiagnosticDimensionCoverageTest` already uses
for the dimension partition, so the mould is in the tree.

