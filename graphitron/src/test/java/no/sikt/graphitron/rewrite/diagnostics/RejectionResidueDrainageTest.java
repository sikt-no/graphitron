package no.sikt.graphitron.rewrite.diagnostics;

import no.sikt.graphitron.rewrite.model.Rejection;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The residue's drainage declaration: what still routes through the {@code rejection_} residue,
 * and what has left it. The declared leaf set is the sealed {@link Rejection} hierarchy's full
 * reflective walk, so a new rejection cause cannot silently enlarge the residue: it fails here
 * (and in {@link RejectionFacts}'s exhaustive switch) until someone edits the declaration and
 * decides its columns. The migrated set names each rejection family whose violations are
 * detection-minted from its own derivation view arm and therefore structurally absent from the
 * residue loader's input (the walk's error stream, taken before the detection violations are
 * appended); migrating a family store-native must add its view here, which is the edit the
 * drainage count is read off. The set is scoped to the {@code rejection_} residue alone: the
 * advisory arm is permanent and sits outside it, so this declaration's complement means "still
 * awaiting migration" and nothing else.
 *
 * <p>Leaf classes appear here in the residue's own {@code variant} spelling (the package-
 * stripped canonical name) rather than as class literals, deliberately: several leaves and
 * their sub-seals are the vocabulary being retired, and the day one is deleted this
 * declaration must be edited in the same commit, which a string set forces exactly as a
 * literal would while keeping the declaration readable as the drainage ledger it is.
 */
@UnitTier
class RejectionResidueDrainageTest {

    /**
     * Every {@link Rejection} leaf a walk-minted error can carry into the residue. The claim
     * conflict family's members ({@code DirectiveConflict}, {@code Deferred}) stay in this set
     * because other walk sites still mint the same leaves; drainage is per family, not per
     * leaf, which is why the migrated set below names derivation views rather than classes.
     */
    private static final Set<String> RESIDUE_LEAVES = Set.of(
        "Rejection.AuthorError.UnknownName", "Rejection.AuthorError.Structural",
        "Rejection.AuthorError.AccessorMismatch", "Rejection.AuthorError.RecordBindingMultiProducer",
        "Rejection.AuthorError.TypeConflict",
        "Rejection.AuthorError.MultiProducerDomainTypeDisagreement",
        "Rejection.AuthorError.SortEnumMissingOrder",
        "Rejection.AuthorError.TenantColumnTypeDisagreement", "Rejection.AuthorError.NoTenantBinding",
        // the ten lspCode()-bearing sub-seals' leaves, by sub-seal:
        "ServiceMethodCallError.MultipleDslContextSlots", "ServiceMethodCallError.ParameterUnbindable",
        "ServiceMethodCallError.InstanceHolderUnconstructible",
        "ServiceMethodCallError.ArgumentParameterMismatch", "ServiceMethodCallError.DtoSourcesUnsupported",
        "ServiceMethodCallError.UnrecognizedSourcesType", "ServiceMethodCallError.SourcesOnPkLessParent",
        "ReflectionError.ClassNotLoaded", "ReflectionError.ReturnTypeMismatch",
        "ReflectionError.ParameterNamesMissing", "ReflectionError.AmbiguousMethod",
        "ReflectionError.SeamParameterMissing", "ReflectionError.SeamCandidateAmbiguous",
        "ReflectionError.HookNotStatic", "ReflectionError.HookThrowsChecked",
        "ReflectionError.HandleTypeMismatch",
        "UpdateRowsError.NoUniqueKeyCoverage", "UpdateRowsError.NoSetFields",
        "UpdateRowsError.MixedCarrierKeyMembership", "UpdateRowsError.UnsupportedInputFieldShape",
        "UpdateRowsError.OverrideConditionNotSupported", "UpdateRowsError.PlainColumnCollision",
        "DeleteRowsError.NoUniqueKeyCoverage", "DeleteRowsError.UnsupportedInputFieldShape",
        "DeleteRowsError.OverrideConditionNotSupported",
        "MutationTableArgError.UnsupportedVerb",
        "ErrorChannelWalkerError.MultipleErrorsFields",
        "ErrorChannelWalkerError.NonNullableSuccessProjectionField",
        "ErrorChannelWalkerError.NonNullableErrorsField", "ErrorChannelWalkerError.ChannelRuleViolation",
        "ErrorChannelWalkerError.HandlerSourceAccessorMissing",
        "WireCoercionError.Assignability", "WireCoercionError.EnumConstantDivergence",
        "ServiceCarrierShapeError.ProducerArrivalMismatch",
        "ServiceCarrierShapeError.DataFieldArrivalConflict",
        "PivotError.NonNullSlot", "PivotError.NonScalarSlot", "PivotError.DivergentSlotType",
        "PivotError.VocabularyNotTextEnum", "PivotError.SlotMissingFromVocabulary",
        "PivotError.DuplicateSlotToken", "PivotError.ColumnUnresolved",
        "PivotError.ValueTypeMismatch", "PivotError.ListReturn",
        "PivotError.UnsupportedReferencePath", "PivotError.RecordBackedParent",
        "PivotError.InvalidProjectionType",
        "JooqRecordInputError.LiveColumnCollision",
        "Rejection.InvalidSchema.DirectiveConflict", "Rejection.InvalidSchema.CaseFoldCollision",
        "Rejection.InvalidSchema.Structural", "Rejection.Deferred");

    /**
     * The rejection families that have migrated store-native, named by their derivation view:
     * their violations are minted by a detection reading the store and never enter the walk's
     * error stream, so the residue never holds them. One entry per per-family flip.
     */
    private static final Set<String> MIGRATED_FAMILY_VIEWS = Set.of(
        "intent_authored_claim_conflict");

    @Test
    @DisplayName("the declared residue leaf set equals the sealed hierarchy's reflective walk")
    void declaredSetEqualsTheReflectiveWalk() {
        var walked = new java.util.ArrayList<String>();
        walk(Rejection.class, walked);
        assertThat(walked)
            .as("two leaves sharing a simple name would make the residue's variant column "
                + "ambiguous; qualify the spelling before it happens")
            .doesNotHaveDuplicates();
        assertThat(new LinkedHashSet<>(walked))
            .as("a new Rejection cause edits this declaration; it cannot silently enlarge the residue")
            .containsExactlyInAnyOrderElementsOf(RESIDUE_LEAVES);
        assertThat(MIGRATED_FAMILY_VIEWS).isNotEmpty();
    }

    private static void walk(Class<?> node, java.util.List<String> out) {
        var permits = node.getPermittedSubclasses();
        if (permits == null || permits.length == 0) {
            if (Rejection.class.isAssignableFrom(node)) {
                out.add(node.getCanonicalName().substring(node.getPackageName().length() + 1));
            }
            return;
        }
        for (var permitted : permits) {
            walk(permitted, out);
        }
    }
}
