package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.model.config.RunContext;
import no.sikt.graphitron.model.diagnostics.ValidationError;
import no.sikt.graphitron.model.diagnostics.ValidationFailedException;
import no.sikt.graphitron.model.test.CapturedGraphs;
import no.sikt.graphitron.model.schema.input.SchemaInput;
import no.sikt.graphitron.model.schema.input.SchemaSource;
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
 * That a key column projected out of a node id into a {@code @service} parameter is refused where the
 * store does not emit the field's fetcher yet, rather than handed the encoded id: only the fetcher the
 * store emits reads the projection. The refusal names why the fetcher is not emitted.
 */
@PipelineTier
class ServiceKeyProjectionRejectionPipelineTest {

    private static final String FILM = """
        interface Node { id: ID! }
        type Film implements Node @table(name: "film") @node { id: ID! title: String }
        """;

    @Test
    void aServiceTheStoreDoesNotEmitYet_isRefusedAtItsProjection(@TempDir Path tmp) throws IOException {
        assertThat(errors(tmp, FILM + """
            type Query {
                filmsPage(ids: [ID!] @nodeId(typeName: "Film")): [Film!]! @asConnection
                    @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "getFilmsByIds",
                                       argMapping: "ids: ids.film_id"})
            }
            """))
            .singleElement().asString()
            .startsWith("Field 'Query.filmsPage': ")
            .contains("The argMapping entry 'ids: ids.film_id' projects a key column of a node id into a @service parameter")
            .contains("not emitted from the store yet (CONNECTION)");
    }

    /** The negative beside the positive, so the refusal cannot pass because every projection fails. */
    @Test
    void aServiceTheStoreEmits_builds(@TempDir Path tmp) throws IOException {
        assertThat(errors(tmp, FILM + """
            type Query {
                films(ids: [ID!] @nodeId(typeName: "Film")): [Film!]!
                    @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "getFilmsByIds",
                                       argMapping: "ids: ids.film_id"})
            }
            """))
            .isEmpty();
    }

    // ===== Helpers =====

    private static List<String> errors(Path tmp, String sdl) throws IOException {
        Path schema = tmp.resolve("schema.graphqls");
        Files.writeString(schema, sdl);
        var ctx = new RunContext(
            List.of(new SchemaInput(SchemaSource.file(schema), Optional.empty(), Optional.empty())),
            tmp, "ServiceKeyProjectionRejectionPipelineTest", tmp,
            DEFAULT_OUTPUT_PACKAGE, DEFAULT_JOOQ_PACKAGE, testClassesRoot());
        try {
            CapturedGraphs.run(ctx, store -> {
                new GraphQLRewriteGenerator(ctx, store.handle(), store.schema()).validate();
            });
            return List.of();
        } catch (ValidationFailedException e) {
            return e.errors().stream().map(ValidationError::message).toList();
        }
    }

    /** The classpath root carrying the {@code @service} stubs. */
    private static List<Path> testClassesRoot() {
        try {
            return List.of(Path.of(ServiceKeyProjectionRejectionPipelineTest.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI()));
        } catch (java.net.URISyntaxException e) {
            throw new IllegalStateException("the test classes' own classpath entry", e);
        }
    }
}
