package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.schema.input.SchemaInput;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_JOOQ_PACKAGE;
import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_OUTPUT_PACKAGE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Confirms a syntactically broken schema surfaces as the same {@link SchemaParseException}
 * out of the generator entry point as out of {@code RewriteSchemaLoader.load} directly:
 * the loader throws it, and it propagates unchanged through
 * {@code GraphQLRewriteGenerator.loadAttributedRegistry()} into {@link GraphQLRewriteGenerator#generate()}
 * with no translation step. The build-time pipeline therefore still fails on a broken schema,
 * carrying the attributed file:line:col message rather than a bare wrapper.
 *
 * <p>Unit tier, not pipeline: a parse failure short-circuits before any SDL is classified
 * into a model or rendered to a {@code TypeSpec}, so this asserts an entry-point propagation
 * invariant rather than SDL -> TypeSpec coverage.
 */
@UnitTier
class SchemaParseExceptionPropagationTest {

    @Test
    void generatePropagatesSchemaParseExceptionWithAttributedMessage(@TempDir Path tmp) throws IOException {
        Path broken = tmp.resolve("broken.graphqls");
        Files.writeString(broken, """
            type Query {
              films: [Film]
            }
            strayTokenHere
            """);

        var ctx = new RewriteContext(
            List.of(SchemaInput.plain(broken.toString())),
            tmp,
            tmp,
            DEFAULT_OUTPUT_PACKAGE,
            DEFAULT_JOOQ_PACKAGE,
            Map.of());

        Throwable thrown = catchThrowable(() -> new GraphQLRewriteGenerator(ctx).generate());

        assertThat(thrown)
            .as("a parse failure propagates unchanged out of generate(), no translation to another type")
            .isInstanceOf(SchemaParseException.class);
        assertThat(thrown.getMessage())
            .contains(broken.toString())
            .contains("line ")
            .contains("column ");
    }
}
