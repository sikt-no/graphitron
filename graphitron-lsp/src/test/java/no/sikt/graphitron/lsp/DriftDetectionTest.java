package no.sikt.graphitron.lsp;

import no.sikt.graphitron.lsp.facts.DirectiveSurface;
import no.sikt.graphitron.lsp.parsing.Behavior;
import no.sikt.graphitron.lsp.parsing.LspVocabulary;
import no.sikt.graphitron.lsp.parsing.SchemaCoordinate;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The drift-detection guard: every coordinate the canonical overlay names must be one graphitron's
 * shipped {@code directives.graphqls} actually declares.
 *
 * <p>This is the test that fails when the SDL drifts away from the overlay. An overlay entry for a
 * directive argument that has since been renamed or removed binds a behaviour to a coordinate no
 * cursor can ever reach, so the binding goes silently dead; nothing else notices, because every
 * surface keyed on that coordinate simply stops being asked.
 *
 * <p>A test rather than a constructor check. The vocabulary used to parse the shipped SDL itself and
 * could therefore refuse to start when a coordinate failed to resolve; it reads a captured graph now,
 * and whether a session's graph has been captured yet is a different question from whether the SDL
 * graphitron ships agrees with the overlay. Only the second is drift, and only the second is worth
 * failing a build over. What the surface here is built from is a capture of the shipped file, so this
 * asserts exactly the old startup invariant with none of its collateral.
 */
class DriftDetectionTest {

    @Test
    void everyCanonicalOverlayCoordinateIsDeclaredByTheShippedDirectives() {
        var vocabulary = BundledVocabulary.get();
        var unresolved = vocabulary.overlay().keySet().stream()
            .filter(coord -> !resolves(coord, vocabulary.surface()))
            .toList();
        assertThat(unresolved)
            .as("overlay coordinates the shipped directives.graphqls no longer declares")
            .isEmpty();
    }

    @Test
    void productionOverlayContainsExpectedCanonicalEntries() {
        var vocab = BundledVocabulary.get();

        // Spot-check a representative subset of the canonical overlay
        // (the full table lives in the spec; this guards against silent
        // shrinkage of the binding set).
        assertThat(vocab.overlay()).containsKeys(
            new SchemaCoordinate.InputField("ExternalCodeReference", "className"),
            new SchemaCoordinate.InputField("ExternalCodeReference", "method"),
            new SchemaCoordinate.DirectiveArg("sourceRow", "className"),
            new SchemaCoordinate.DirectiveArg("table", "name"),
            new SchemaCoordinate.DirectiveArg("field", "name")
        );
    }

    @Test
    void fieldSortNameResolvesAndBindsToColumnBehavior() {
        // The @defaultOrder(fields: [{name: ...}]) coordinate. That it resolves against the shipped
        // directives.graphqls (input FieldSort { name: String! ... }) is the case above; this pins
        // the binding.
        var vocab = BundledVocabulary.get();
        var coord = new SchemaCoordinate.InputField("FieldSort", "name");
        assertThat(vocab.overlay()).containsKey(coord);
        assertThat(vocab.behaviorAt(coord))
            .containsInstanceOf(Behavior.CatalogColumnBinding.class);
    }

    /**
     * Whether a coordinate names something the surface declares, exhaustively over the coordinate
     * family so a fifth arm has to say here what resolving it means.
     */
    private static boolean resolves(SchemaCoordinate coord, DirectiveSurface surface) {
        return switch (coord) {
            case SchemaCoordinate.Directive d -> surface.declaresDirective(d.name());
            case SchemaCoordinate.DirectiveArg da -> surface.declaresArgument(da.directive(), da.arg());
            case SchemaCoordinate.InputType t -> surface.declaresInputObject(t.name());
            case SchemaCoordinate.InputField f -> surface.declaresInputField(f.type(), f.field());
        };
    }
}
