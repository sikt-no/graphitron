package no.sikt.graphitron.model.capture.document;

import graphql.language.Directive;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The locations the five site writers name, held against the vocabulary that declares them.
 *
 * <p>{@link GraphitronEntries} judges each site's applications by handing {@link DirectiveLegality}
 * a location string, and a site that spells its location wrong fails open: no declared location
 * matches, every application is refused, and the relation simply empties. A corpus case at that
 * site would catch it, but only for a directive that site decodes, and the schema site decodes none
 * of the vocabulary's own. So the spellings are checked here, once, against the definitions rather
 * than against a capture.
 *
 * <p>This is also the whole of what wiring the schema site buys today. No bundled directive is
 * declared at {@code SCHEMA}, so every application reaching it belongs to the author or to
 * federation, which the vocabulary does not declare and the judge admits without looking. The
 * wiring is a statement about the day graphitron declares one, and this is where that statement is
 * falsifiable.
 */
class DirectiveLegalitySiteTest {

    /**
     * Each site's own location admits a directive the vocabulary declares there. A misspelling
     * fails here rather than in production, where it would read as a relation nobody writes to.
     *
     * <p>Every directive named here declares no required argument, so a bare application of it is
     * one its definition admits and the only thing the case can fail on is the location. One that
     * required an argument would fail for writing none, which is a different judgement and belongs
     * to the corpus cases.
     */
    @ParameterizedTest(name = "@{0} is admitted at {1}")
    @CsvSource({
        "table,           OBJECT",
        "table,           INPUT_OBJECT",
        "notGenerated,    FIELD_DEFINITION",
        "lookupKey,       ARGUMENT_DEFINITION",
        "asFacet,         INPUT_FIELD_DEFINITION",
        "index,           ENUM_VALUE",
    })
    void eachSiteNamesALocationTheVocabularyDeclares(String directive, String location) {
        assertThat(DirectiveLegality.admits(new Directive(directive), location))
            .as("the vocabulary declares @%s at %s, so the site that passes that spelling admits "
                + "an application written there", directive, location)
            .isTrue();
    }

    /**
     * A location no bundled directive is declared at still judges, which is what the schema site
     * will do the day one is. {@code @index} is declared at {@code ENUM_VALUE} and nowhere else, so
     * it is refused everywhere the vocabulary does not put it.
     */
    @Test
    @DisplayName("a bundled directive written at a location its definition omits is refused")
    void aLocationTheDefinitionOmitsIsRefused() {
        assertThat(DirectiveLegality.admits(new Directive("index"), "SCHEMA"))
            .as("no bundled directive is declared at SCHEMA, so the site admits whatever reaches "
                + "it today; what it will refuse is a bundled directive written in the wrong place")
            .isFalse();
    }

    /**
     * A directive the vocabulary does not declare is admitted wherever it is written. Federation's
     * @link is the live instance: it reaches the schema site, the vocabulary has no definition for
     * it, and judging it would be judging input against a definition we do not hold.
     */
    @Test
    @DisplayName("a directive the vocabulary does not declare is admitted at every site")
    void anUndeclaredDirectiveIsNotJudged() {
        assertThat(DirectiveLegality.admits(new Directive("link"), "SCHEMA"))
            .as("federation's @link is the author's vocabulary, not ours, and the schema site "
                + "transcribes it")
            .isTrue();
    }
}
