package no.sikt.graphitron.rewrite.schema.input;

import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLSchema;
import no.sikt.graphitron.rewrite.GraphitronSchemaBuilder;
import no.sikt.graphitron.rewrite.schema.RewriteSchemaLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end coverage of the tagged-inputs pipeline: attribution → loader →
 * tag applier → description-note applier → classification. Reads assertions
 * off the built {@link GraphQLSchema} (not the pre-build registry) so any
 * future caching the classifier might introduce between registry mutation
 * and schema build is covered by this test.
 */
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

        var inputs = List.of(
            new SchemaInput(enrolment.toString(), Optional.of("enrolment"), Optional.empty()),
            new SchemaInput(cinema.toString(), Optional.empty(), Optional.of("Part of cinema feature.")),
            new SchemaInput(shared.toString(), Optional.of("core"), Optional.of("Shared by every feature."))
        );

        var bySource = SchemaInputAttribution.build(inputs);
        var registry = RewriteSchemaLoader.load(bySource.keySet());
        TagApplier.apply(registry, bySource);
        DescriptionNoteApplier.apply(registry, bySource);

        GraphQLSchema assembled = GraphitronSchemaBuilder.buildBundle(registry).assembled();

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
