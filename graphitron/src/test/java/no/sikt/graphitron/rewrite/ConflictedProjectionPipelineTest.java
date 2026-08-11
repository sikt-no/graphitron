package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.catalog.FieldClassification;
import no.sikt.graphitron.rewrite.schema.input.SchemaInput;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_JOOQ_PACKAGE;
import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_OUTPUT_PACKAGE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The output-side projection preserves the claims, driven through the production producer:
 * {@link GraphQLRewriteGenerator#buildOutput()} runs capture-and-detect ahead of the snapshot
 * and overlays {@link FieldClassification.Conflicted} at conflicted coordinates, so a broken
 * DELETE mutation still reads as a DELETE with its intended table on the LSP and MCP surfaces,
 * sourced from the claim relations. Driving {@code buildOutput()} rather than hand-assembling
 * capture, detect and {@code buildSnapshot} is deliberate: the reorder (detection before
 * snapshot) is itself the production change under test, and a hand-assembled harness would
 * stay green with the wiring broken.
 */
@PipelineTier
class ConflictedProjectionPipelineTest {

    private static final String SERVICE =
        "no.sikt.graphitron.codereferences.dummyreferences.DummyService";

    @Test
    void conflictedDeleteMutationStillReportsItsVerbAndTable(@TempDir Path tmp) throws IOException {
        Path schema = tmp.resolve("schema.graphqls");
        Files.writeString(schema, """
            type Film @table(name: "film") { title: String }
            type Query { film: Film }
            type Mutation {
              deleteFilm(filmId: Int): ID
                @service(service: {className: "%s", method: "makeDummyRecord"})
                @mutation(typeName: DELETE, table: "film")
            }
            """.formatted(SERVICE));
        var output = buildOutput(tmp, schema);

        var classification = output.artifacts().snapshot()
            .fieldClassificationsByCoord().get("Mutation.deleteFilm");
        assertThat(classification).isInstanceOf(FieldClassification.Conflicted.class);
        var conflicted = (FieldClassification.Conflicted) classification;
        assertThat(conflicted.violation()).isEqualTo("@service, @mutation are mutually exclusive");
        assertThat(conflicted.claims()).hasSize(2);

        var service = (FieldClassification.Claim.Service) conflicted.claims().get(0);
        assertThat(service.methodClassName()).isEqualTo(SERVICE);
        assertThat(service.methodName()).isEqualTo("makeDummyRecord");
        assertThat(service.trigger()).isEqualTo("service");
        assertThat(service.decoded()).isTrue();

        var mutation = (FieldClassification.Claim.Mutation) conflicted.claims().get(1);
        assertThat(mutation.dmlKind()).isEqualTo("DELETE");
        assertThat(mutation.tableName()).isEqualTo("film");
        assertThat(mutation.trigger()).isEqualTo("mutation");
        assertThat(mutation.decoded()).isTrue();
        assertThat(mutation.location())
            .as("the claim carries its application's own position, decoded to the catalog's location shape")
            .isNotNull();
        assertThat(mutation.location().uri()).endsWith("schema.graphqls");

        // The conflict still reaches the error stream: the projection preserves the claims,
        // it never absorbs the violation.
        assertThat(output.report().errors())
            .extracting(ValidationError::message)
            .contains("Field 'Mutation.deleteFilm': @service, @mutation are mutually exclusive");
    }

    @Test
    void deferredRoutineLookupPairDoesNotOverlay(@TempDir Path tmp) throws IOException {
        // The recognised routine-plus-lookup pair is a capability-gap deferral, not a conflict;
        // its coordinate keeps the walk's own projection and never renders Conflicted.
        Path schema = tmp.resolve("schema.graphqls");
        Files.writeString(schema, """
            type Film @table(name: "film") { title: String }
            type Query {
              film(id: ID @lookupKey): Film @routine(name: "film_fn")
            }
            """);
        var output = buildOutput(tmp, schema);

        var classification = output.artifacts().snapshot()
            .fieldClassificationsByCoord().get("Query.film");
        assertThat(classification).isNotInstanceOf(FieldClassification.Conflicted.class);
        assertThat(output.report().errors())
            .extracting(ValidationError::message)
            .contains("Field 'Query.film': @routine with @lookupKey on a root field classifies but does not emit yet");
    }

    private static GraphQLRewriteGenerator.BuildOutput buildOutput(Path tmp, Path schema) {
        var ctx = new RewriteContext(
            List.of(new SchemaInput(schema.toString(), Optional.empty(), Optional.empty())),
            tmp, "ConflictedProjectionPipelineTest",
            tmp,
            DEFAULT_OUTPUT_PACKAGE,
            DEFAULT_JOOQ_PACKAGE
        );
        return new GraphQLRewriteGenerator(ctx).buildOutput();
    }
}
