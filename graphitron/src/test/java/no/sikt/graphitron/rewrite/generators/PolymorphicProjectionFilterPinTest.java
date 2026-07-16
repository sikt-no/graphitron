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
 * into the emitted {@code <Type>.$fields(...)} call. A refactor that reverts
 * to passing the unfiltered parent selection set re-introduces the over-selection
 * the wrapper closes.
 *
 * <p>The classifier does not reject this shape (no classifier guarantee applies),
 * and the rewrite bans code-string assertions on <em>emitted</em> method bodies,
 * so the pin follows the precedent set by {@link UnifiedEmissionPinsTest}: a
 * regex scan over <em>generator source files</em> that counts occurrences and
 * asserts the expected enumeration.
 *
 * <p>Two assertions:
 * <ul>
 *   <li>Folder-wide scan via {@link #countAcrossGenerators}: occurrences of the
 *       JavaPoet emit-string shape {@code $T.restrictTo(env.getSelectionSet()}
 *       across the generators package. The {@code $T} placeholder uniquely
 *       identifies addStatement/addCode emit-site strings (prose mentions of
 *       {@code PolymorphicSelectionSet.restrictTo} in javadoc / comments use
 *       the qualified-class form, not the placeholder, so they are filtered
 *       out). Expected count: 1 — the single Stage-2 site in
 *       {@code MultiTablePolymorphicEmitter.java}.</li>
 *   <li>Single-file scan over {@code MultiTablePolymorphicEmitter.java}:
 *       occurrences of {@code env.getSelectionSet()} passed <em>directly</em> as
 *       the first argument to {@code $$fields(} (the double-dollar distinguishes
 *       JavaPoet emit-site strings from javadoc text, which uses single
 *       {@code $fields}). Expected count: 0 after the fix. A regression that
 *       reverts the Stage-2 site to the unfiltered shape re-introduces a match,
 *       the count rises, the pin trips.</li>
 * </ul>
 *
 * <p>Scoping the second pin to a single file (rather than reusing
 * {@code countAcrossGenerators}) is deliberate: the same direct-arg shape
 * appears in ~12 non-polymorphic emit sites across {@code FetcherEmitter},
 * {@code SplitRowsMethodEmitter}, and several {@code TypeFetcherGenerator}
 * sites that are correct as-is per the "Filter at the call site, not inside
 * {@code $fields}" reasoning, plus the same-table interface emit site at
 * {@code TypeFetcherGenerator.buildInterfaceFieldsList} that this pin intentionally
 * leaves alone. A folder-wide count would couple the pin to those unrelated
 * correct sites; a single-file scope pins exactly the Stage-2 invariant.
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
                + "is the one Stage-2 site in MultiTablePolymorphicEmitter.buildPerTypenameSelect. "
                + "A handcrafted regression that reverts that site to the unfiltered shape removes "
                + "the call; a new Stage-2 dispatcher that bypasses buildPerTypenameSelect would "
                + "land a second call (and should — or it has the same over-selection bug R108 "
                + "closed). Either direction trips this pin.")
            .isEqualTo(1);
    }

    @Test
    void stage2EmitterPassesNoUnfilteredSelectionSetToFields() throws IOException {
        // Matches the JavaPoet emit-string shape `$$fields(env.getSelectionSet()` — the double
        // dollar distinguishes addStatement bodies from javadoc text (which uses single $fields).
        // After the fix, no occurrences remain in MultiTablePolymorphicEmitter.java: every
        // call passes through PolymorphicSelectionSet.restrictTo.
        String content = Files.readString(STAGE_2_EMITTER);
        long directArgs = Pattern.compile("\\$\\$fields\\(env\\.getSelectionSet\\(\\)")
            .matcher(content).results().count();
        assertThat(directArgs)
            .as("No emit-site in MultiTablePolymorphicEmitter.java passes env.getSelectionSet() "
                + "directly to $$fields( — every Stage-2 SELECT must thread the selection set "
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
