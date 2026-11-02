package no.sikt.graphitron.rewrite;

import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLSchema;
import no.sikt.graphitron.model.schema.input.SchemaInput;
import no.sikt.graphitron.model.schema.input.SchemaSource;
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
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import no.sikt.graphitron.model.config.RunContext;
import no.sikt.graphitron.model.run.GraphitronStore;
import no.sikt.graphitron.model.schema.SchemaAssembly;

/**
 * End-to-end coverage of tagged and noted inputs: what three configured entries apply, read off the
 * post-synthesis schema a capture builds, which is the schema the generator emits. The tag and note
 * rewrites have tests of their own; this pins that what they apply reaches the emitted schema.
 */
@PipelineTier
class TaggedInputsPipelineTest {

    @Test
    void threeEntriesDistributeTagsAndNotesAcrossTheBuiltSchema(@TempDir Path tmp) throws IOException {
        Path enrolment = tmp.resolve("enrolment.graphqls");
        Files.writeString(enrolment, """
            type Query { students: [Student] }
            "An enrolled student."
            type Student {
              id: ID!
              firstName: String
            }
            """);
        Path cinema = tmp.resolve("cinema.graphqls");
        Files.writeString(cinema, """
            "Moving pictures."
            type Film {
              id: ID!
            }
            """);
        Path shared = tmp.resolve("shared.graphqls");
        Files.writeString(shared, """
            enum Status { ACTIVE INACTIVE }
            """);

        var ctx = new RunContext(
            List.of(
                new SchemaInput(SchemaSource.file(enrolment), Optional.of("enrolment"), Optional.empty()),
                new SchemaInput(SchemaSource.file(cinema), Optional.empty(), Optional.of("Part of cinema feature.")),
                new SchemaInput(SchemaSource.file(shared), Optional.of("core"), Optional.of("Shared by every feature."))
            ),
            tmp, "TaggedInputsPipelineTest",
            tmp,
            DEFAULT_OUTPUT_PACKAGE,
            DEFAULT_JOOQ_PACKAGE
        );

        GraphQLSchema assembled;
        try (var captured = GraphitronStore.captured(ctx)) {
            assembled = ((SchemaAssembly.Assembled) captured.schema().synthesised().orElseThrow())
                .schema();
        }

        // Tagged-only: @tag present on fields, no description change.
        GraphQLObjectType student = (GraphQLObjectType) assembled.getType("Student");
        assertThat(student.getDescription()).isEqualTo("An enrolled student.");
        for (var f : student.getFieldDefinitions()) {
            var directives = f.getAppliedDirectives("tag");
            assertThat(directives).hasSize(1);
            Object value = directives.getFirst().getArgument("name").getValue();
            assertThat(value).isEqualTo("enrolment");
        }

        // Noted-only: description concatenation, no @tag.
        GraphQLObjectType film = (GraphQLObjectType) assembled.getType("Film");
        assertThat(film.getDescription()).isEqualTo("Moving pictures.\n\nPart of cinema feature.");
        for (var f : film.getFieldDefinitions()) {
            assertThat(f.getAppliedDirectives("tag")).isEmpty();
        }
        // Object type declarations themselves are never tagged.
        assertThat(film.getAppliedDirectives("tag")).isEmpty();

        // Both: enum values get @tag AND note appended; enum declaration itself gets the note.
        var status = assembled.getType("Status");
        assertThat(((graphql.schema.GraphQLEnumType) status).getDescription())
            .isEqualTo("Shared by every feature.");
        for (var value : ((graphql.schema.GraphQLEnumType) status).getValues()) {
            var directives = value.getAppliedDirectives("tag");
            assertThat(directives).hasSize(1);
            Object tagName = directives.getFirst().getArgument("name").getValue();
            assertThat(tagName).isEqualTo("core");
            assertThat(value.getDescription()).isEqualTo("Shared by every feature.");
        }
    }
}
