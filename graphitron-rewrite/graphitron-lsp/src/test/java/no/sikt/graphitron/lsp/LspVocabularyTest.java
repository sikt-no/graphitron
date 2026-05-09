package no.sikt.graphitron.lsp;

import no.sikt.graphitron.lsp.parsing.Behavior;
import no.sikt.graphitron.lsp.parsing.LspVocabulary;
import no.sikt.graphitron.lsp.parsing.LspVocabulary.DeprecationInfo;
import no.sikt.graphitron.lsp.parsing.LspVocabulary.LspStartupException;
import no.sikt.graphitron.lsp.parsing.SchemaCoordinate;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins {@link LspVocabulary}'s startup invariants and the deprecation-info
 * surface. The structural guarantee asserted by
 * {@link #structuralInvariant_failsStartupWhenOverlayCoordinateDoesNotResolve}
 * is the load-bearing piece of R119: the LSP cannot start with an overlay that
 * names a coordinate the parsed registry does not declare. R110-style drift
 * surfaces here as a startup failure rather than a silent unknown-directive
 * at request time.
 */
class LspVocabularyTest {

    private static final String FIXTURE_SDL = """
        directive @demo(name: String!) on OBJECT

        "Marker for the soon-to-be-removed @retired form. @deprecated use @demo(name:) instead"
        directive @retired on OBJECT

        input DemoRef {
            "stays"
            className: String
            name: String @deprecated(reason: "replaced by className")
        }
        """;

    @Test
    void load_acceptsOverlayPointingAtRegisteredCoordinates() {
        var overlay = Map.<SchemaCoordinate, Behavior>of(
            new SchemaCoordinate.DirectiveArg("demo", "name"),
                new Behavior.CatalogTableBinding(),
            new SchemaCoordinate.InputField("DemoRef", "className"),
                new Behavior.ClassNameBinding()
        );

        var vocab = LspVocabulary.load(overlay, FIXTURE_SDL);

        assertThat(vocab.behaviorAt(new SchemaCoordinate.DirectiveArg("demo", "name")))
            .containsInstanceOf(Behavior.CatalogTableBinding.class);
        assertThat(vocab.behaviorAt(new SchemaCoordinate.InputField("DemoRef", "className")))
            .containsInstanceOf(Behavior.ClassNameBinding.class);
    }

    @Test
    void structuralInvariant_failsStartupWhenOverlayCoordinateDoesNotResolve() {
        var fictional = new SchemaCoordinate.DirectiveArg("notADirective", "nope");
        var overlay = Map.<SchemaCoordinate, Behavior>of(fictional, new Behavior.CatalogTableBinding());

        assertThatThrownBy(() -> LspVocabulary.load(overlay, FIXTURE_SDL))
            .isInstanceOf(LspStartupException.class)
            .hasMessageContaining("@notADirective(nope:)")
            .hasMessageContaining("does not resolve");
    }

    @Test
    void structuralInvariant_failsForUnknownDirective() {
        var overlay = Map.<SchemaCoordinate, Behavior>of(
            new SchemaCoordinate.Directive("nope"), new Behavior.CatalogTableBinding()
        );

        assertThatThrownBy(() -> LspVocabulary.load(overlay, FIXTURE_SDL))
            .isInstanceOf(LspStartupException.class)
            .hasMessageContaining("@nope");
    }

    @Test
    void structuralInvariant_failsForUnknownInputType() {
        var overlay = Map.<SchemaCoordinate, Behavior>of(
            new SchemaCoordinate.InputType("Ghost"), new Behavior.CatalogTableBinding()
        );

        assertThatThrownBy(() -> LspVocabulary.load(overlay, FIXTURE_SDL))
            .isInstanceOf(LspStartupException.class)
            .hasMessageContaining("Ghost");
    }

    @Test
    void structuralInvariant_failsForUnknownInputField() {
        var overlay = Map.<SchemaCoordinate, Behavior>of(
            new SchemaCoordinate.InputField("DemoRef", "ghostField"), new Behavior.ClassNameBinding()
        );

        assertThatThrownBy(() -> LspVocabulary.load(overlay, FIXTURE_SDL))
            .isInstanceOf(LspStartupException.class)
            .hasMessageContaining("DemoRef.ghostField");
    }

    @Test
    void deprecationOf_nativeShape_returnsReasonStringForInputField() {
        var vocab = LspVocabulary.load(Map.of(), FIXTURE_SDL);

        var info = vocab.deprecationOf(new SchemaCoordinate.InputField("DemoRef", "name"))
            .orElseThrow();

        assertThat(info.shape()).isEqualTo(DeprecationInfo.Shape.NATIVE);
        assertThat(info.reason()).isEqualTo("replaced by className");
    }

    @Test
    void deprecationOf_docstringShape_returnsDescriptionForWholeDirective() {
        var vocab = LspVocabulary.load(Map.of(), FIXTURE_SDL);

        var info = vocab.deprecationOf(new SchemaCoordinate.Directive("retired"))
            .orElseThrow();

        assertThat(info.shape()).isEqualTo(DeprecationInfo.Shape.DOCSTRING);
        assertThat(info.reason()).contains("@deprecated");
    }

    @Test
    void descriptionOf_returnsSdlDocstringForKnownCoordinates() {
        var vocab = LspVocabulary.load(Map.of(), FIXTURE_SDL);

        assertThat(vocab.descriptionOf(new SchemaCoordinate.Directive("retired")))
            .map(String::trim)
            .hasValueSatisfying(d -> assertThat(d).contains("@deprecated"));

        assertThat(vocab.descriptionOf(new SchemaCoordinate.InputField("DemoRef", "className")))
            .map(String::trim)
            .hasValue("stays");
    }

    @Test
    void descriptionOf_returnsEmptyForCoordinateWithoutDescription() {
        var vocab = LspVocabulary.load(Map.of(), FIXTURE_SDL);

        // @demo has no docstring on its declaration in the fixture.
        assertThat(vocab.descriptionOf(new SchemaCoordinate.Directive("demo"))).isEmpty();
    }

    @Test
    void deprecationOf_returnsEmptyForUndeprecatedCoordinate() {
        var vocab = LspVocabulary.load(Map.of(), FIXTURE_SDL);

        assertThat(vocab.deprecationOf(new SchemaCoordinate.InputField("DemoRef", "className")))
            .isEmpty();
        assertThat(vocab.deprecationOf(new SchemaCoordinate.Directive("demo")))
            .isEmpty();
    }
}
