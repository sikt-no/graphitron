package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.model.config.RunContext;
import no.sikt.graphitron.model.diagnostics.ValidationError;
import no.sikt.graphitron.model.diagnostics.ValidationFailedException;
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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * That a decoding {@code @nodeId} the generator does not carry out fails the build, and that every
 * shape it does carry out still builds clean.
 *
 * <p>The rule subtracts two operands that meet only at {@link GraphQLRewriteGenerator}'s capture
 * window, so this tier is the only one that can state it: the census of authored instructions is
 * written by the capture, and the disposition ledger is produced by the classification pass. Both
 * halves are asserted throughout, because a gate whose clean half is unstated passes just as well
 * when everything fails.
 */
@PipelineTier
class NodeIdDecodeCoveragePipelineTest {

    /**
     * The node type every fixture below decodes against, and the two ordinary tables they filter.
     * {@code language} carries a one-column primary key and {@code film} a foreign key to it, which
     * is the pairing the walk rail installs a filter decode over.
     */
    private static final String NODE_AND_TABLES = """
        interface Node { id: ID! }
        type Language implements Node @table(name: "language") @node {
            id: ID! @nodeId
            name: String
        }
        type Film @table(name: "film") { title: String }
        """;

    /** The authored condition method the rail-two fixtures bind: {@code (Table<?>, Integer)}. */
    private static final String DECODED_KEY_CONDITION = """
        @condition(condition: {
            className: "no.sikt.graphitron.rewrite.TestConditionStub",
            method: "languageIdDecodedKeyCondition",
            argMapping: "%s"
        })
        """;

    /**
     * The silence this rule closes. The author annotated an argument {@code @nodeId}, and the
     * coordinate is one no lowering path reaches: the owning field is a plain column read, so its
     * arguments are never classified into a filter, and no {@code argMapping} binds the id either.
     * Before this rule the build was green and the base64 string was simply dropped; now the
     * coordinate is named.
     */
    @Test
    void anArgumentNoRailDecodesFailsTheBuild(@TempDir Path tmp) throws IOException {
        assertThatThrownBy(() -> validate(tmp, NODE_AND_TABLES + """
            type Actor @table(name: "actor") {
                firstName(languageId: ID! @nodeId(typeName: "Language")): String
                    @field(name: "first_name")
            }
            type Query { actors: [Actor!]!, language: Language }
            """))
            .isInstanceOf(ValidationFailedException.class)
            .satisfies(e -> assertThat(((ValidationFailedException) e).errors())
                .extracting(ValidationError::message)
                .as("the coordinate, the node type and the fact that no rail decodes it")
                .anyMatch(m -> m.contains("argument 'languageId' on field 'Actor.firstName'")
                    && m.contains("@nodeId for node type 'Language'")
                    && m.contains("neither the classification walk nor the projected-key rail")));
    }

    /**
     * The same instruction at a coordinate the walk does install at builds clean. Without this the
     * gate above would be satisfied by a rule that reports every {@code @nodeId} in the schema.
     */
    @Test
    void theSameInstructionOnAFilterArgumentBuildsClean(@TempDir Path tmp) throws IOException {
        assertThatCode(() -> validate(tmp, NODE_AND_TABLES + """
            type Query {
                language: Language
                films(languageId: ID @nodeId(typeName: "Language") @reference(path: [{key: "film_language_id_fkey"}])): [Film!]!
            }
            """))
            .as("a filter argument reaching the node's table through a foreign key is the walk"
                + " rail's own shape")
            .doesNotThrowAnyException();
    }

    /**
     * The rail-two regression. A field-level {@code @condition} whose {@code argMapping} descends
     * to a {@code @nodeId} input field is installed by the projected-key rail and by nothing the
     * classification walk holds, so this is the fixture that fails the moment the ledger is built
     * over walk carriers alone.
     *
     * <p>Both spellings, since they differ in what the author wrote past the node id: one names the
     * key column and one lets the key's arity name it. They resolve through different arms of the
     * projection relation and have to be covered by the same disposition.
     */
    @Test
    void aDottedArgmappingDescentToANodeIdInputFieldBuildsClean(@TempDir Path tmp)
            throws IOException {
        assertThatCode(() -> validate(tmp, railTwoSchema("languageId: filter.languageId")))
            .as("the inferred spelling: a one-column key names its own column")
            .doesNotThrowAnyException();
        assertThatCode(() -> validate(tmp,
                railTwoSchema("languageId: filter.languageId.language_id")))
            .as("the authored spelling: the author named the key column past the node id")
            .doesNotThrowAnyException();
    }

    /** {@link #aDottedArgmappingDescentToANodeIdInputFieldBuildsClean}'s two schemas. */
    private static String railTwoSchema(String argMapping) {
        return NODE_AND_TABLES + """
            input FilmFilter {
                languageId: ID @nodeId(typeName: "Language")
                    @reference(path: [{key: "film_language_id_fkey"}])
            }
            type Query {
                language: Language
                films(filter: FilmFilter): [Film!]!
            """ + DECODED_KEY_CONDITION.formatted(argMapping) + "}";
    }

    /**
     * The use-site enforcer. One input field carrying one instruction, consumed at two coordinates:
     * a filter argument where the walk installs, and a plain column field's argument where nothing
     * does. A ledger keyed at the definition would cover both from the one install and report
     * nothing, so this is the fixture that fails if the keying axis slips back to the definition.
     * It is the silent-miss direction, which no other case here covers.
     */
    @Test
    void anInstallAtOneUseSiteDoesNotCoverAnother(@TempDir Path tmp) throws IOException {
        assertThatThrownBy(() -> validate(tmp, NODE_AND_TABLES + """
            input FilmFilter {
                languageId: ID @nodeId(typeName: "Language")
                    @reference(path: [{key: "film_language_id_fkey"}])
            }
            type Actor @table(name: "actor") {
                firstName(pick: FilmFilter): String @field(name: "first_name")
            }
            type Query {
                language: Language
                actors: [Actor!]!
                films(filter: FilmFilter): [Film!]!
            }
            """))
            .isInstanceOf(ValidationFailedException.class)
            .satisfies(e -> assertThat(((ValidationFailedException) e).errors())
                .extracting(ValidationError::message)
                .as("the uninstalled use site is named, and the installed one is not")
                .anyMatch(m -> m.contains("input field 'FilmFilter.languageId'")
                    && m.contains("Actor.firstName(pick)"))
                .noneMatch(m -> m.contains("Query.films(filter)")));
    }

    /**
     * The granularity enforcer. Two decoding instructions under one owning field, both dropped. At
     * {@code Type.field} grain one message would stand for both and a second drop could hide behind
     * the first; the check reports each coordinate it finds, so both are named.
     *
     * <p>The plan's own wording for this case pairs a drop with a neighbour <em>refused</em> under
     * the same field, and that pairing turns out not to be constructible: a field whose arguments
     * the walk classifies disposes of every instruction under it, and one whose arguments it does
     * not classify refuses none of them. What the grain has to survive is a neighbour's message
     * standing at the same coordinate the error attaches to, and two drops state that as directly.
     */
    @Test
    void twoDropsUnderOneFieldAreBothReported(@TempDir Path tmp) throws IOException {
        assertThatThrownBy(() -> validate(tmp, NODE_AND_TABLES + """
            type Actor @table(name: "actor") {
                firstName(
                    one: ID @nodeId(typeName: "Language")
                    two: ID @nodeId(typeName: "Language")
                ): String @field(name: "first_name")
            }
            type Query { actors: [Actor!]!, language: Language }
            """))
            .isInstanceOf(ValidationFailedException.class)
            .satisfies(e -> assertThat(((ValidationFailedException) e).errors())
                .extracting(ValidationError::message)
                .as("one message per dropped coordinate, not one per owning field")
                .anyMatch(m -> m.contains("argument 'one' on field 'Actor.firstName'"))
                .anyMatch(m -> m.contains("argument 'two' on field 'Actor.firstName'")));
    }

    /**
     * The {@code NotReached} case. The owning field's classification aborts above the argument
     * surface, so its own rejection is the only thing the author should see; a second message about
     * a decode nothing could have installed under an unclassifiable field would state the wrong
     * cause beside the right one on every already-failing build.
     */
    @Test
    void aFieldThatDoesNotClassifyReportsOnlyItsOwnRejection(@TempDir Path tmp) throws IOException {
        assertThatThrownBy(() -> validate(tmp, NODE_AND_TABLES + """
            type Actor @table(name: "actor") {
                missing(languageId: ID @nodeId(typeName: "Language")): String
                    @field(name: "no_such_column")
            }
            type Query { actors: [Actor!]!, language: Language }
            """))
            .isInstanceOf(ValidationFailedException.class)
            .satisfies(e -> assertThat(((ValidationFailedException) e).errors())
                .extracting(ValidationError::message)
                .as("the field's own rejection stands alone")
                .noneMatch(m ->
                    m.contains("neither the classification walk nor the projected-key rail")));
    }

    private static void validate(Path tmp, String sdl) throws IOException {
        Path schema = tmp.resolve("schema.graphqls");
        Files.writeString(schema, sdl);
        new GraphQLRewriteGenerator(new RunContext(
            List.of(new SchemaInput(SchemaSource.file(schema), Optional.empty(), Optional.empty())),
            tmp, "NodeIdDecodeCoveragePipelineTest",
            tmp,
            DEFAULT_OUTPUT_PACKAGE,
            DEFAULT_JOOQ_PACKAGE
        )).validate();
    }
}
