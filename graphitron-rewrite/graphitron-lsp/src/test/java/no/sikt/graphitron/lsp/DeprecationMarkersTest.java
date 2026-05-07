package no.sikt.graphitron.lsp;

import no.sikt.graphitron.lsp.code_action.SdlAction.DeprecationTarget;
import no.sikt.graphitron.lsp.parsing.DeprecationMarkers;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Parser unit test against synthetic SDL fixtures plus the bundled
 * {@code directives.graphqls}. Covers both marker shapes that
 * {@link DeprecationMarkers} pulls out: SDL {@code @deprecated()} on
 * arguments and input-type fields (member-level), and the javadoc-style
 * {@code @deprecated} token in directive description strings
 * (whole-directive).
 */
class DeprecationMarkersTest {

    @Test
    void parsesSdlDeprecatedOnInputField() {
        var sdl = """
            input Foo {
              old: String @deprecated(reason: "Use new")
              new: String
            }
            """;

        var targets = DeprecationMarkers.parse(sdl);

        assertThat(targets).containsExactly(new DeprecationTarget.Member("Foo", "old"));
    }

    @Test
    void parsesSdlDeprecatedOnDirectiveArg() {
        var sdl = """
            directive @asConnection(
              defaultFirstValue: Int = 100
              connectionName: String @deprecated(reason: "transitional")
            ) on FIELD_DEFINITION
            """;

        var targets = DeprecationMarkers.parse(sdl);

        assertThat(targets).containsExactly(
            new DeprecationTarget.Member("@asConnection", "connectionName"));
    }

    @Test
    void parsesWholeDirectiveDeprecationFromTripleQuotedDescription() {
        var sdl = """
            \"""@deprecated use @order(index:) instead\"""
            directive @index(name: String) on ENUM_VALUE
            """;

        var targets = DeprecationMarkers.parse(sdl);

        assertThat(targets).containsExactly(new DeprecationTarget.WholeDirective("index"));
    }

    @Test
    void parsesWholeDirectiveDeprecationFromSingleQuotedDescription() {
        var sdl = """
            "Connect this to an index. @deprecated use @order(index:) instead"
            directive @index(name: String) on ENUM_VALUE
            """;

        var targets = DeprecationMarkers.parse(sdl);

        assertThat(targets).containsExactly(new DeprecationTarget.WholeDirective("index"));
    }

    @Test
    void parsesWholeDirectiveDeprecationFromMultiLineDescription() {
        var sdl = """
            \"""
            Connect this enum value to an index.
            @deprecated use @order(index:) instead
            \"""
            directive @index(name: String) on ENUM_VALUE
            """;

        var targets = DeprecationMarkers.parse(sdl);

        assertThat(targets).containsExactly(new DeprecationTarget.WholeDirective("index"));
    }

    @Test
    void doesNotMatchProseDeprecatedWord() {
        // Prose "Deprecated:" (capitalised, with a colon) is not the
        // structured token; must not fire.
        var sdl = """
            "Connect this to something. Deprecated: use other directive."
            directive @foo(name: String) on ENUM_VALUE
            """;

        var targets = DeprecationMarkers.parse(sdl);

        assertThat(targets).isEmpty();
    }

    @Test
    void doesNotMatchAtDeprecatedFollowedByLetter() {
        // The token must end at a word boundary; "@deprecatedfoo" isn't
        // the marker.
        var sdl = """
            "@deprecatedSoon use other directive"
            directive @foo(name: String) on ENUM_VALUE
            """;

        var targets = DeprecationMarkers.parse(sdl);

        assertThat(targets).isEmpty();
    }

    @Test
    void noDescriptionMeansNoWholeDirectiveMarker() {
        var sdl = """
            directive @foo(name: String) on ENUM_VALUE
            """;

        var targets = DeprecationMarkers.parse(sdl);

        assertThat(targets).isEmpty();
    }

    @Test
    void parsesBothMarkerKindsTogether() {
        var sdl = """
            \"""@deprecated use newer thing\"""
            directive @oldDir(arg: String) on ENUM_VALUE

            input Box {
              legacy: String @deprecated(reason: "x")
              modern: String
            }
            """;

        var targets = DeprecationMarkers.parse(sdl);

        assertThat(targets).containsExactlyInAnyOrder(
            new DeprecationTarget.WholeDirective("oldDir"),
            new DeprecationTarget.Member("Box", "legacy")
        );
    }

    @Test
    void bundledDirectivesGraphqlsContainsAllThreeKnownTargets() {
        var targets = DeprecationMarkers.parseFromClasspath();

        assertThat(targets).containsExactlyInAnyOrder(
            new DeprecationTarget.Member("ExternalCodeReference", "name"),
            new DeprecationTarget.Member("@asConnection", "connectionName"),
            new DeprecationTarget.WholeDirective("index")
        );
    }
}
