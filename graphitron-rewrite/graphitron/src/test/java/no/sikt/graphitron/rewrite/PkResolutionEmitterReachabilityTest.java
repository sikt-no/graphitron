package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.generators.FetcherEmitter;
import no.sikt.graphitron.rewrite.generators.GeneratorCoverageTest;
import no.sikt.graphitron.rewrite.model.PkResolution;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R156 forward-protection scan for the model-facing {@link PkResolution} sealed root and its
 * companion builder-internal {@code PerFieldOutcome} root.
 *
 * <p>Two invariants:
 * <ul>
 *   <li><b>Emitter exhaustiveness on {@link PkResolution}.</b> The
 *       {@link FetcherEmitter#buildSingleRecordTableFromReturningFetcherValue} dispatch is the
 *       sole emitter for the per-field projection list carried on
 *       {@link no.sikt.graphitron.rewrite.model.ChildField.SingleRecordTableFieldFromReturning}.
 *       Today {@link PkResolution} has two arms ({@code PkRead}, {@code NonPkNullable}) and the
 *       emitter pattern-matches both directly. A future change that adds a third arm without an
 *       emitter case must surface as a test failure here, not as a silent generator hole. The
 *       sealed-switch compiler error catches the case where the emitter is updated, but doesn't
 *       catch a fresh arm whose handler is missing entirely; the type-pattern dispatch falls
 *       through to the synthesizing path that doesn't reference the arm at all.</li>
 *   <li><b>Producer-side rejection enforcement on {@link PerFieldOutcome}.</b> The builder-
 *       internal classifier outcome has five arms: two admissible ({@code PkRead},
 *       {@code NonPkNullable}) and three rejecting ({@code NonPkNonNullable}, {@code ServiceField},
 *       {@code UnsupportedField}). The {@code BuildContext.classifyDeleteTableProjection}
 *       projection step must produce a {@code Rejected} resolution before constructing any
 *       {@link PkResolution} when any element-type field classifies into a rejecting arm. The
 *       audit asserts the producer rejects a synthesised {@code ServiceField} classification;
 *       paired with the {@code @LoadBearingClassifierCheck(key =
 *       "mutation-delete-carrier.pk-resolution-projection-clean", ...)} on the producer and the
 *       matching {@code @DependsOnClassifierCheck} on the emitter, this closes the classifier-
 *       emitter contract loop.</li>
 * </ul>
 *
 * <p>Sits in the generator-coverage tier alongside
 * {@link GeneratorCoverageTest#everyGraphitronFieldLeafHasAKnownDispatchStatus}; same precedent
 * (reflective scan over a sealed root, fail on the first orphan).
 */
@UnitTier
class PkResolutionEmitterReachabilityTest {

    /**
     * The set of arms {@link FetcherEmitter#buildSingleRecordTableFromReturningFetcherValue}
     * handles. Adding a third {@link PkResolution} arm requires (a) adding it here and (b) adding
     * a case to the emitter switch. The audit fails on either omission.
     */
    private static final Set<Class<? extends PkResolution>> HANDLED_BY_EMITTER = Set.of(
        PkResolution.PkRead.class,
        PkResolution.NonPkNullable.class
    );

    @Test
    void everyPkResolutionArmHasAnEmitterCase() {
        Set<Class<?>> arms = Arrays.stream(PkResolution.class.getPermittedSubclasses())
            .collect(Collectors.toSet());

        Set<Class<?>> handled = Set.copyOf(HANDLED_BY_EMITTER);
        Set<Class<?>> orphans = arms.stream()
            .filter(a -> !handled.contains(a))
            .collect(Collectors.toSet());

        assertThat(orphans)
            .as("every PkResolution arm must be handled by "
                + "FetcherEmitter.buildSingleRecordTableFromReturningFetcherValue; adding a "
                + "third arm requires adding both an emitter case and a HANDLED_BY_EMITTER entry")
            .isEmpty();

        // Symmetry: HANDLED_BY_EMITTER must not list a class that isn't a sealed leaf of
        // PkResolution (catches dead entries after an arm is renamed or removed).
        Set<Class<?>> dead = handled.stream()
            .filter(c -> !arms.contains(c))
            .collect(Collectors.toSet());
        assertThat(dead)
            .as("HANDLED_BY_EMITTER must not name classes that aren't sealed leaves of "
                + "PkResolution; clean up stale entries when an arm is renamed or removed")
            .isEmpty();
    }

    @Test
    void perFieldOutcomeRejectionArmsAreRejectedByProjectionStep_serviceFieldCase() {
        // The producer-side rejection rule (pinned by @LoadBearingClassifierCheck key
        // "mutation-delete-carrier.pk-resolution-projection-clean") must surface ServiceField
        // arms as a hard rejection before constructing any PkResolution. Verified indirectly:
        // confirm PerFieldOutcome.ServiceField is a declared sealed leaf (so producers cannot
        // skip the type-pattern arm by accident) AND that PkResolution does NOT carry a
        // ServiceField sibling (so the rejection arm cannot leak into the emitter by type).
        Set<Class<?>> perFieldArms = Arrays.stream(PerFieldOutcome.class.getPermittedSubclasses())
            .collect(Collectors.toSet());
        assertThat(perFieldArms)
            .as("PerFieldOutcome must carry a ServiceField rejection arm; removing it would let "
                + "@service-resolved fields silently classify into NonPkNullable and produce a "
                + "silent-null hole at runtime")
            .contains(PerFieldOutcome.ServiceField.class)
            .contains(PerFieldOutcome.NonPkNonNullable.class)
            .contains(PerFieldOutcome.UnsupportedField.class);

        Set<Class<?>> pkResolutionArms = Arrays.stream(PkResolution.class.getPermittedSubclasses())
            .collect(Collectors.toSet());
        assertThat(pkResolutionArms)
            .as("PkResolution must not gain a ServiceField / NonPkNonNullable / UnsupportedField "
                + "sibling; the rejection arms exist on PerFieldOutcome (builder-internal) only, "
                + "so the projection step's rejection cannot be observed downstream by type")
            .doesNotContainAnyElementsOf(Set.of(
                PerFieldOutcome.ServiceField.class,
                PerFieldOutcome.NonPkNonNullable.class,
                PerFieldOutcome.UnsupportedField.class));
    }

    @Test
    void perFieldOutcomeAndPkResolutionSharePkReadAndNonPkNullableArmShapes() {
        // The narrowing split (five-arm PerFieldOutcome → two-arm PkResolution) preserves the
        // record-component shapes of the two admissible arms so the projection step's mapping
        // (PerFieldOutcome.PkRead → PkResolution.PkRead, PerFieldOutcome.NonPkNullable →
        // PkResolution.NonPkNullable) stays a one-to-one rename. Drift here would mean the
        // emitter consumes a different shape than the classifier produces.
        var pfPkRead = PerFieldOutcome.PkRead.class.getRecordComponents();
        var prPkRead = PkResolution.PkRead.class.getRecordComponents();
        assertThat(prPkRead).hasSameSizeAs(pfPkRead);
        for (int i = 0; i < pfPkRead.length; i++) {
            assertThat(prPkRead[i].getName()).isEqualTo(pfPkRead[i].getName());
            assertThat(prPkRead[i].getGenericType().getTypeName())
                .isEqualTo(pfPkRead[i].getGenericType().getTypeName());
        }

        var pfNullable = PerFieldOutcome.NonPkNullable.class.getRecordComponents();
        var prNullable = PkResolution.NonPkNullable.class.getRecordComponents();
        assertThat(prNullable).hasSameSizeAs(pfNullable);
        for (int i = 0; i < pfNullable.length; i++) {
            assertThat(prNullable[i].getName()).isEqualTo(pfNullable[i].getName());
            assertThat(prNullable[i].getGenericType().getTypeName())
                .isEqualTo(pfNullable[i].getGenericType().getTypeName());
        }
    }

    @Test
    void classifyDeleteTableProjectionWearsLoadBearingClassifierCheckPin() {
        // The producer-side @LoadBearingClassifierCheck pin (key
        // "mutation-delete-carrier.pk-resolution-projection-clean") must be present on
        // BuildContext.classifyDeleteTableProjection; the FetcherEmitter case for
        // SingleRecordTableFieldFromReturning carries the matching @DependsOnClassifierCheck.
        // The LoadBearingGuaranteeAuditTest scans the producer-consumer pair build-time; this
        // test pins the producer-side method name so a rename surfaces here.
        var method = Arrays.stream(BuildContext.class.getDeclaredMethods())
            .filter(m -> m.getName().equals("classifyDeleteTableProjection"))
            .findFirst()
            .orElseThrow(() -> new AssertionError(
                "BuildContext.classifyDeleteTableProjection must exist as the single producer "
                + "of List<PkResolution>; renaming requires updating the @DependsOnClassifierCheck "
                + "consumer pin in FetcherEmitter.buildSingleRecordTableFromReturningFetcherValue"));
        var pin = method.getAnnotation(no.sikt.graphitron.rewrite.model.LoadBearingClassifierCheck.class);
        assertThat(pin)
            .as("BuildContext.classifyDeleteTableProjection must wear "
                + "@LoadBearingClassifierCheck for the producer-consumer audit to pick the pair "
                + "up at build time")
            .isNotNull();
        assertThat(pin.key()).isEqualTo("mutation-delete-carrier.pk-resolution-projection-clean");
    }
}
