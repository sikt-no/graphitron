package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.model.config.RunContext;
import no.sikt.graphitron.model.diagnostics.ValidationError;
import no.sikt.graphitron.model.diagnostics.ValidationFailedException;
import no.sikt.graphitron.model.schema.input.SchemaInput;
import no.sikt.graphitron.model.schema.input.SchemaSource;
import no.sikt.graphitron.rewrite.classifieddsl.CorpusDocuments;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_JOOQ_PACKAGE;
import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_OUTPUT_PACKAGE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The ratchet: over every corpus document that carries a node id, no decoding instruction is left
 * without a disposition.
 *
 * <p>What this holds is the totality claim rather than a verdict about any one shape. The residual
 * over today's tree is empty, which is what makes the item a ratchet and not a repair, and an empty
 * residual is a passing ratchet rather than a disappointing one: the failure class it guards is
 * defined by nobody knowing when it acquires a member. A lowering path that lands and forgets to
 * dispose of the coordinates it now reaches fails here, in a test that names the document, rather
 * than in a consumer's build a release later.
 *
 * <p>It is stated over the corpus because the corpus is where the classification verdicts live as
 * examples, so a shape that acquires a document acquires this coverage in the same commit. The
 * assertion is deliberately narrow: a corpus document may well fail validation for its own reasons,
 * and what the ratchet asks is only that none of its errors is a coverage report.
 */
@PipelineTier
class NodeIdDecodeCoverageRatchetTest {

    /**
     * The floor on the swept set. A filter that stops matching reads as an empty corpus and passes
     * every sweep over it; this is what makes that failure loud instead.
     */
    private static final int MIN_DOCUMENTS = 8;

    /** The message fragment {@link NodeIdDecodeCoverage} alone produces. */
    private static final String COVERAGE_REPORT =
        "neither the classification walk nor the projected-key rail";

    @ParameterizedTest(name = "{0}")
    @MethodSource("nodeIdDocuments")
    void everyDecodingInstructionInTheCorpusIsDisposedOf(CorpusDocuments.Document document,
                                                         @TempDir Path tmp) throws IOException {
        assertThat(errorsOf(tmp, CorpusDocuments.prelude() + "\n" + document.sdl()))
            .as("no decoding @nodeId in '%s' is left without a disposition", document.id())
            .noneMatch(m -> m.contains(COVERAGE_REPORT));
    }

    /**
     * The corpus documents that could hold a decoding instruction: those spelling {@code @nodeId},
     * and those spelling an {@code id: ID} slot, which is the directive-less basis the census also
     * admits.
     */
    static List<CorpusDocuments.Document> nodeIdDocuments() {
        var swept = CorpusDocuments.documents().stream()
            .filter(d -> d.sdl().contains("@nodeId") || d.sdl().contains("id: ID"))
            .toList();
        assertThat(swept)
            .as("corpus documents carrying a node id (a filter that stops matching sweeps nothing)")
            .hasSizeGreaterThanOrEqualTo(MIN_DOCUMENTS);
        return swept;
    }

    /** Every validation message {@code sdl} produces, empty where it validates clean. */
    private static List<String> errorsOf(Path tmp, String sdl) throws IOException {
        Path schema = tmp.resolve("schema.graphqls");
        Files.writeString(schema, sdl);
        var generator = new GraphQLRewriteGenerator(new RunContext(
            List.of(new SchemaInput(SchemaSource.file(schema), Optional.empty(), Optional.empty())),
            tmp, "NodeIdDecodeCoverageRatchetTest",
            tmp,
            DEFAULT_OUTPUT_PACKAGE,
            DEFAULT_JOOQ_PACKAGE));
        try {
            generator.validate();
            return List.of();
        } catch (ValidationFailedException e) {
            return e.errors().stream().map(ValidationError::message).toList();
        }
    }
}
