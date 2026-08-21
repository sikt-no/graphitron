package no.sikt.graphitron.rewrite.generators;

import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guarantee marker: the Stage-2 per-typename SELECT in
 * {@code MultiTablePolymorphicEmitter.buildPerTypenameSelect} threads
 * {@code PolymorphicSelectionSet.restrictTo(env.getSelectionSet(), "<Type>")}
 * into the emitted {@code <Type>.$project(...)} call. Passing the unfiltered parent
 * selection set instead re-introduces the over-selection the wrapper closes.
 *
 * <p>The classifier does not reject this shape (no classifier guarantee applies),
 * and the rewrite bans code-string assertions on <em>emitted</em> method bodies,
 * so the pin follows the precedent set by {@link UnifiedEmissionPinsTest}: a
 * regex scan over <em>generator source files</em> that counts occurrences and
 * asserts the expected enumeration.
 *
 * <p>Scoping the second pin to a single file (rather than reusing
 * {@link #countAcrossGenerators}) is deliberate: the same direct-arg shape
 * appears in many non-polymorphic emit sites across {@code FetcherEmitter},
 * {@code SplitRowsMethodEmitter}, and several {@code TypeFetcherGenerator}
 * sites that are correct as-is per the "Filter at the call site, not inside
 * {@code $project}" reasoning. (The same-table interface emit this caveat once named
 * relocated to {@code no.sikt.graphitron.render.DiscriminatedTableFragments}, outside
 * this scan's folder.) A folder-wide count would couple the pin to those unrelated
 * correct sites; a single-file scope pins exactly the Stage-2 invariant.
 *
 * <p>The Stage-2 SELECT is no longer {@code restrictTo}'s only consumer: the single-table
 * discriminated fold in {@code DiscriminatedTableFragments} calls it too, at the selective arity
 * that restricts only the field names whose alias the participant type qualifies. That emit site
 * is in {@code render}, outside this non-recursive scan, so this pin's count still speaks only for
 * the generators package, which is the scope its assertion message states.
 */
@UnitTier
class PolymorphicProjectionFilterPinTest {

    private static final Path GENERATORS_DIR =
        Path.of("src/main/java/no/sikt/graphitron/rewrite/generators");

    private static final Path STAGE_2_EMITTER =
        GENERATORS_DIR.resolve("MultiTablePolymorphicEmitter.java");

    @Test
    void restrictToCalledAtExactlyOneEmitSite() throws IOException {
        // Matches the JavaPoet emit-site shape `$T.restrictTo(env.getSelectionSet(`. The $T
        // placeholder is the ClassName slot for the helper, present only in addStatement /
        // addCode strings — prose mentions of `PolymorphicSelectionSet.restrictTo` in javadoc
        // use the qualified-class form, so they don't contribute. Files.list is non-recursive,
        // so generators/util/ (which contains the helper-class generator) is naturally outside
        // the scan.
        long restrictToCalls = countAcrossGenerators(
            Pattern.compile("\\$T\\.restrictTo\\(env\\.getSelectionSet\\(\\)"));
        assertThat(restrictToCalls)
            .as("Every PolymorphicSelectionSet.restrictTo emit site in the generators package "
                + "(the discriminated fold's own site is in render/, outside this scan) "
                + "is the one Stage-2 site in MultiTablePolymorphicEmitter.buildPerTypenameSelect. "
                + "A handcrafted regression that reverts that site to the unfiltered shape removes "
                + "the call; a new Stage-2 dispatcher that bypasses buildPerTypenameSelect would "
                + "land a second call (and should — or it has the same over-selection bug R108 "
                + "closed). Either direction trips this pin.")
            .isEqualTo(1);
    }

    @Test
    void stage2EmitterPassesNoUnfilteredSelectionSetToFields() throws IOException {
        // The unfiltered projection call is composed through the shared call emitter
        // (ProjectionCall.fromEnvSelection); expected count 0 in the Stage-2 emitter: every
        // Stage-2 SELECT threads the selection set through PolymorphicSelectionSet.restrictTo.
        String content = Files.readString(STAGE_2_EMITTER);
        long directArgs = Pattern.compile("ProjectionCall\\.fromEnvSelection\\(")
            .matcher(content).results().count();
        assertThat(directArgs)
            .as("No emit-site in MultiTablePolymorphicEmitter.java composes the unfiltered "
                + "projection call — every Stage-2 SELECT must thread the selection set "
                + "through PolymorphicSelectionSet.restrictTo so the per-typename projection sees "
                + "only SelectedFields matching that participant. A regression that reverts the "
                + "Stage-2 site to the unfiltered shape re-introduces a match.")
            .isZero();
    }

    private static long countAcrossGenerators(Pattern pattern) throws IOException {
        try (var stream = Files.list(GENERATORS_DIR)) {
            return stream
                .filter(p -> p.getFileName().toString().endsWith(".java"))
                .mapToLong(p -> {
                    try {
                        return pattern.matcher(Files.readString(p)).results().count();
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                })
                .sum();
        }
    }
}
