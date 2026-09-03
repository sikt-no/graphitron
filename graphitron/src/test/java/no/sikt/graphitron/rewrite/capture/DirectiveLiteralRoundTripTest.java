package no.sikt.graphitron.rewrite.capture;

import graphql.language.AstPrinter;
import graphql.parser.Parser;
import no.sikt.graphitron.model.test.CapturedStore;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.jooq.DSLContext;
import org.jooq.Table;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static no.sikt.graphitron.model.Tables.GRAPHQL_ARGUMENT_DIRECTIVE_ARG;
import static no.sikt.graphitron.model.Tables.GRAPHQL_ENUM_VALUE_DIRECTIVE_ARG;
import static no.sikt.graphitron.model.Tables.GRAPHQL_FIELD_DIRECTIVE_ARG;
import static no.sikt.graphitron.model.Tables.GRAPHQL_SCHEMA_DIRECTIVE_ARG;
import static no.sikt.graphitron.model.Tables.GRAPHQL_TYPE_DIRECTIVE_ARG;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the decode's input rests on: the transcription stores an authored literal as
 * {@code AstPrinter} renders it, and the decode reads it back with the parser.
 *
 * <p>The decode is a gatherer that runs after the SDL walk rather than inside it, so it no longer
 * receives the parsed value the walk was holding; it rebuilds it from the stored text. That makes
 * the print-then-parse round trip load-bearing where before it was only the shape of one column, and
 * a literal that did not survive it would reach the decode as a different value with nothing
 * reporting the difference. So the property is asserted over every literal a capture stores, not
 * over the ones a test remembered to write down: parse the stored text, print it again, and require
 * the two to be the same string.
 *
 * <p>The fixture reaches the literal shapes that could plausibly fail. Escapes and a block string,
 * because printing chooses a quoting; a negative and an exponent, because a number has more than one
 * spelling; an enum against a string, because unquoting one would silently become the other; and a
 * list of objects nested two deep, because structure is where a compact printer differs most from
 * the document. Every value here is authored on an undeclared directive on purpose: capture is total
 * and transcribes what nobody declared, which is exactly the population a round trip has no schema
 * to lean on for.
 */
@PipelineTier
class DirectiveLiteralRoundTripTest {

    private static final String FIXTURE = """
        directive @carrier(
          text: String, block: String, count: Int, ratio: Float,
          flag: Boolean, choice: Season, items: [Shape], nothing: String
        ) repeatable on SCHEMA | OBJECT | FIELD_DEFINITION | ARGUMENT_DEFINITION | ENUM_VALUE

        input Shape { name: String, inner: Shape, tags: [String] }
        enum Season { SUMMER, WINTER }

        extend schema @carrier(text: "a \\"quoted\\" one\\nwith a newline\\ttab")

        type Query {
          films(
            filter: String @carrier(choice: SUMMER, text: "SUMMER")
          ): [Film] @carrier(count: -17, ratio: 1.5e-3, flag: false, nothing: null)
        }

        type Film @carrier(
          items: [{name: "outer", inner: {name: "inner", tags: ["a", "b"]}}, {name: "second"}]
        ) {
          title: String @carrier(block: \"""
            a block string
            over two lines
            \""")
        }

        enum Rating { G @carrier(text: "general") }
        """;

    private static final List<Table<?>> LITERAL_RELATIONS = List.of(
        GRAPHQL_SCHEMA_DIRECTIVE_ARG, GRAPHQL_TYPE_DIRECTIVE_ARG, GRAPHQL_FIELD_DIRECTIVE_ARG,
        GRAPHQL_ARGUMENT_DIRECTIVE_ARG, GRAPHQL_ENUM_VALUE_DIRECTIVE_ARG);

    @Test
    @DisplayName("every stored literal parses back to the value it was printed from")
    void everyStoredLiteralParsesBack(@TempDir Path tmp) {
        try (var store = CapturedStore.of(tmp, FIXTURE)) {
            var stored = literalsOf(store.dsl());
            assertThat(stored)
                .as("the fixture has to reach every literal shape, so an empty read would pass"
                    + " vacuously; each of the five application grains carries at least one")
                .hasSizeGreaterThanOrEqualTo(9);

            var broken = new ArrayList<String>();
            for (String literal : stored) {
                String again = AstPrinter.printAstCompact(Parser.parseValue(literal));
                if (!again.equals(literal)) {
                    broken.add(literal + " parses back as " + again);
                }
            }
            assertThat(broken)
                .as("a literal the store cannot hand back to the decode unchanged; the decode reads"
                    + " these rows rather than the parse, so this is the value it would act on")
                .isEmpty();
        }
    }

    /** Every authored literal a capture stored, across the five directive-application grains. */
    private static List<String> literalsOf(DSLContext dsl) {
        var literals = new ArrayList<String>();
        for (Table<?> relation : LITERAL_RELATIONS) {
            literals.addAll(dsl.select(relation.field("VALUE_SDL", String.class))
                .from(relation)
                .fetch(0, String.class));
        }
        return literals;
    }
}
