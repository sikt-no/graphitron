package no.sikt.graphitron.rewrite.classifieddsl;

import no.sikt.graphitron.rewrite.classifieddsl.CorpusExpectations.Block;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The planted regressions under {@link CorpusExpectations}'s floors. {@code CorpusExpectationTest}
 * sweeps the real corpus, where a green run says only that no document is currently wrong; these
 * cases hand each floor the defect it exists to catch, so a floor that stops firing fails here.
 *
 * <p>Decoding needs no store: a block is CSV, and jOOQ parses CSV without a connection, which is the
 * whole point of not owning a parser.
 */
@UnitTier
class CorpusExpectationsTest {

    private static final DSLContext PARSER = DSL.using(SQLDialect.DEFAULT);

    private static Block block(String csv) {
        return CorpusExpectations.decode(PARSER, "doc", "intent_resolved_field_claim", csv);
    }

    @Test
    void aWellFormedBlockHasNoDefects() {
        assertThat(CorpusExpectations.defects(block("""
            type_name, field_name
            Film,      title
            """), false))
            .as("the shape every corpus document writes passes every well-formedness check")
            .isEmpty();
    }

    @Test
    void cellsAndHeadersAreTrimmedSoADocumentMayPadForLegibility() {
        Block padded = block("""
            type_name,    field_name,   tier
            Film,         title,        INFERRED
            """);

        assertThat(padded.columns()).containsExactly("type_name", "field_name", "tier");
        assertThat(padded.rows()).containsExactly(List.of("Film", "title", "INFERRED"));
    }

    @Test
    void anEmptyCellIsHowADocumentSpellsNull() {
        Block block = block("""
            type_name, field_name, tier
            Film,      ,           INFERRED
            """);

        assertThat(block.rows().getFirst())
            .as("a cell with nothing in it is NULL, which the row comparison matches with "
                + "IS NOT DISTINCT FROM rather than with equality")
            .containsExactly("Film", null, "INFERRED");
    }

    @Test
    void aRaggedRowIsADefect() {
        assertThat(CorpusExpectations.defects(block("""
            type_name, field_name, tier
            Film,      title
            """), false))
            .as("a row with fewer cells than the header would otherwise compare a padded NULL "
                + "against a real value and read as a behaviour disagreement")
            .hasSize(1)
            .allSatisfy(defect -> assertThat(defect).contains("does not have 3 cells"));
    }

    @Test
    void aRepeatedHeaderCellIsADefect() {
        assertThat(CorpusExpectations.defects(block("""
            type_name, type_name
            Film,      Film
            """), false))
            .hasSize(1)
            .allSatisfy(defect -> assertThat(defect).contains("repeats a column"));
    }

    @Test
    void spellingTheGraphColumnIsADefect() {
        assertThat(CorpusExpectations.defects(block("""
            graph_name, type_name
            doc,        Film
            """), false))
            .as("the graph is the document's own identity, supplied by the harness; a document that "
                + "spells it could name another document's graph")
            .hasSize(1)
            .allSatisfy(defect -> assertThat(defect).contains("graph_name"));
    }

    @Test
    void anEmptyBlockNeedsTheRelationsPermission() {
        Block empty = block("type_name, field_name\n");

        assertThat(CorpusExpectations.defects(empty, false))
            .as("asserted absence is a claim about what a relation's silence means, so a relation "
                + "that does not say cannot be asserted empty")
            .hasSize(1)
            .allSatisfy(defect -> assertThat(defect).contains("does not say what its silence means"));
        assertThat(CorpusExpectations.defects(empty, true))
            .as("a relation whose comment states its silence may be asserted empty")
            .isEmpty();
    }

    @Test
    void aRelationOwnsItsSilenceWhenItsCommentSaysSo() {
        assertThat(CorpusExpectationTest.ownsItsSilence(
            "One row per claimed coordinate; no row where the walk reached nothing."))
            .isTrue();
        assertThat(CorpusExpectationTest.ownsItsSilence("One row per claimed coordinate."))
            .isFalse();
    }

    @Test
    void anUnknownRelationOrColumnDoesNotResolve() {
        Map<String, Set<String>> catalog = Map.of("intent_resolved_field_claim",
            Set.of("graph_name", "type_name", "field_name"));

        assertThat(CorpusExpectations.unresolvedNames(block("type_name\nFilm\n"), catalog)).isEmpty();
        assertThat(CorpusExpectations.unresolvedNames(block("type_nmae\nFilm\n"), catalog))
            .hasSize(1)
            .allSatisfy(problem -> assertThat(problem).contains("has no column 'type_nmae'"));
        assertThat(CorpusExpectations.unresolvedNames(
            CorpusExpectations.decode(PARSER, "doc", "intent_no_such_relation", "type_name\nFilm\n"),
            catalog))
            .hasSize(1)
            .allSatisfy(problem -> assertThat(problem).contains("no relation named"));
    }

    @Test
    void aValueOutsideACheckedVocabularyIsAMembershipError() {
        Map<String, Set<String>> vocabularies = Map.of("tier", Set.of("AUTHORED", "INFERRED"));

        assertThat(CorpusExpectations.membershipViolations(block("""
            type_name, tier
            Film,      INFERRED
            """), vocabularies))
            .isEmpty();
        assertThat(CorpusExpectations.membershipViolations(block("""
            type_name, tier
            Film,      INFERED
            """), vocabularies))
            .as("where the store closes the set in DDL, a typo fails as a membership error rather "
                + "than as a row mismatch")
            .hasSize(1)
            .allSatisfy(violation -> assertThat(violation).contains("outside the column's CHECK"));
    }

    @Test
    void aCheckedVocabularyIsReadOffTheClauseTheStoreDeclares() {
        assertThat(CorpusExpectationTest.CHECKED_MEMBERSHIP
            .matcher("(\"TIER\" IN ('AUTHORED', 'INFERRED'))").find())
            .as("the clause shape the store spells its closed sets in")
            .isTrue();
    }
}
