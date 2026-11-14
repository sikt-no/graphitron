package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.model.config.OutboxConfig;
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
import java.util.Map;
import java.util.Optional;

import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_JOOQ_PACKAGE;
import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_OUTPUT_PACKAGE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * That an {@code @outbox} on a {@code @service} mutation is refused where the store does not emit the
 * field's fetcher yet, rather than accepted without its messages: the messages are written by the
 * store-emitted fetcher alone, so a field the classified emitters still build has nowhere to write
 * them. The refusal names why the fetcher is not emitted, and goes once the last service is.
 */
@PipelineTier
class ServiceOutboxRejectionPipelineTest {

    private static final String FILM = """
        interface Node { id: ID! }
        type Film implements Node @table(name: "film") @node { id: ID! title: String }
        input ModifyFilmInput {
            filmId: ID! @nodeId(typeName: "Film")
            title: String @field(name: "title")
            relatedIds: [ID!] @nodeId(typeName: "Film")
        }
        type Query { films: [Film!]! }
        """;

    @Test
    void aServiceTheStoreDoesNotEmitYet_isRefusedAtItsOutbox(@TempDir Path tmp) throws IOException {
        assertThat(errors(tmp, FILM + """
            type Mutation {
                modifyFilm(in: ModifyFilmInput!): String
                    @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "modifyFilmRecord"})
                    @outbox(event: "FilmModified", argMapping: "film: in.filmId")
            }
            """))
            .singleElement().asString()
            .startsWith("Field 'Mutation.modifyFilm': ")
            .contains("An @outbox on a @service is written by the fetcher the store emits")
            .contains("not emitted from the store yet (");
    }

    /** The negative beside the positive, so the refusal cannot pass because every service outbox fails. */
    @Test
    void aServiceTheStoreEmits_builds(@TempDir Path tmp) throws IOException {
        assertThat(errors(tmp, FILM + """
            type Mutation {
                echo(id: String!): String
                    @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "wireIdAsString"})
                    @outbox(event: "Echoed", argMapping: "value: $result, id: id")
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
            tmp, "ServiceOutboxRejectionPipelineTest", tmp,
            DEFAULT_OUTPUT_PACKAGE, DEFAULT_JOOQ_PACKAGE, testClassesRoot()
        ).withOutboxConfig(OutboxConfig.from("outbox", null, Map.of(), Map.of(), null));
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
            return List.of(Path.of(ServiceOutboxRejectionPipelineTest.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI()));
        } catch (java.net.URISyntaxException e) {
            throw new IllegalStateException("the test classes' own classpath entry", e);
        }
    }
}
