---
id: R740
title: "Retire the oracle-diff shadow tests, and stop the anchor gate from manufacturing them"
status: Backlog
bucket: cleanup
priority: 2
theme: testing
depends-on: []
created: 2026-08-19
last-updated: 2026-08-19
---

# Retire the oracle-diff shadow tests, and stop the anchor gate from manufacturing them

Five tests in `graphitron/src/test/java/no/sikt/graphitron/rewrite/derive/` are named `*ShadowTest`, and the name covers two unrelated things. Three of them make the classification walk the expected value of an assertion, which pins the walk's bugs as invariants and makes the list of known differences the real specification. Two of them compare two spellings that both ship today, which is a consistency invariant about what a user sees and is not an oracle diff at all. Calling both "shadow" is what makes the family unreadable: a reader who opens `NodeTypeShadowTest` expecting scaffolding finds a live invariant, and a reader who opens `DemandShadowTest` expecting a specification finds a residue list.

## The oracle diffs

`DemandShadowTest` (422 lines) asserts the demand relations agree with `ClaimDomain.of(bundle.model())` outside five named residue populations, and it needs `derive/DemandResidue.java`, a class in *main* sources whose only job is to enumerate the walk's holes so the test can subtract them. The relations it anchors already have a specification test that does not mention the walk: `graphitron-model/src/test/java/no/sikt/graphitron/model/intent/DemandRuleTest.java`, 687 lines, seeded row by row against intended semantics. So the diff's entire marginal claim is "and it also matches the walk, except here", and the exception list is where the design content has ended up.

`ColumnMatchShadowTest` (268 lines) is the same shape over `ColumnMatchClaimTest`, and its own javadoc says the walk "is the other side of every assertion here".

`InputOccurrenceShadowTest` (367 lines) is weaker still: its javadoc names the drift it structurally cannot catch, because a store predicate that is too narrow suppresses no walk verdict while the walk still evaluates its own threaded `enclosingOverride` boolean.

All three should be deleted outright, with no accounting of what they were asserting. Converting their residues into fixtures first would keep the assumption that produced them: that the project owes the walk an explanation for every difference. It does not. Consumers come off the walk when a bug report or a feature request gives a reason to move one, and the requirement that motivated the move is the specification for the test written then. A difference between the walk and a derivation is therefore never a debt to record; it is either a walk bug the derivation already fixes, in which case reproducing it would be the defect, or an unmigrated consumer waiting for its own reason to move, in which case nothing is owed yet. The spec-side anchors already say what the relations should answer, and they keep saying it after the diffs are gone.

## The two that stay, renamed

`NodeTypeShadowTest` (152 lines) compares `NodeDeclaration#isNodeType`, which four generator consumers call, against `intent_node_type`, which the store readers call. Both answers ship. `TypeBackingShadowTest` (226 lines) is the same situation and says so at its own line 34: the editor reads the derivation while `RecordBindingResolver` still binds record types for the leaf model, so a disagreement is a user seeing the editor and the emitted code contradict each other.

Neither is testing implementation. Both are asserting that two live surfaces agree, which is a property no end-to-end fixture catches well. What they need is the framing fixed: a name that says consistency rather than shadow, and an assertion written symmetrically so neither side reads as the expected value and neither is described as the oracle.

## The gate that manufactures them

`FactCaptureAgreementTest`'s `Arm` enum (`CONTAINMENT`, `EQUALITY`, `DERIVED`, `ORACLE`) requires every relation to register how its contents are pinned, which is the right rule. The weakness is that `DERIVED` means only "anchored somewhere else" and records nothing about what the anchor stands on, so `intent_type_domain`, `intent_field_demand_rule`, `intent_resolved_field_demand`, `intent_resolved_type_demand`, `intent_column_match_claim` and the three `intent_input_occurrence_*` relations satisfy the gate with a diff against the code they replace, indistinguishably from a relation anchored against a specification. The `ORACLE` arm is not the escape hatch: it marks relations *written by* an oracle (`javac_diagnostic`, `rejection_validation_error`, the `lint_` findings), not derivations *anchored against* one.

The fix is a registration that distinguishes the two, so choosing an oracle diff as a derivation's anchor is a visible, expiring decision rather than the cheapest way through the gate. Without that, this cleanup grows back.

## What drains as a consequence

The backing half of this has already drained. R870 deleted `walk_type_backing_class`, the last resident of its family, along with the family itself, `TypeBackingClassRows`, and the two `docs/architecture/explanation/fact-model.adoc` paragraphs that cited the family as the shipped exemplar; `TypeBackingClasses` left main sources for the test tier, where the comparison it feeds now runs. What that leaves this item is the comparison's own cleanup, which R870 deliberately did not take: the rename away from "shadow" and the symmetric assertion.

`DemandResidue` leaves main sources, and so does the `ClaimDomain` value the demand shadow diffs against, R743 having left it with that one reader and nothing reified behind it.

## Out of scope

The `walk_` family's membership grains used to be carved out here, on the ground that they were a live conflict-detection gate rather than shadow scaffolding and so needed an author-facing reason of their own before they could move. That carve-out is closed: R743 supplied the reason (the walk retires, so the gate loses its writer) and deleted both relations along with the projection that wrote them, so there is nothing left here to exclude. Read this section as one item narrower than it was. The `rejection_` family's charter still ties its lifetime to the walk's clock and remains separate.

## Exit criteria

The three oracle diffs and `DemandResidue` are gone, and no replacement record of what they compared exists. The two consistency tests are renamed and their assertions symmetric. The anchor registration tells an oracle-anchored derivation apart from a specification-anchored one, and nothing in the tree is registered the first way without a stated expiry.
