package no.sikt.graphitron.model;

import no.sikt.graphitron.model.capture.document.SdlCapture;
import no.sikt.graphitron.model.run.GraphIdentity;
import no.sikt.graphitron.model.run.SubjectConfig;
import no.sikt.graphitron.model.schema.input.SchemaRecipe;
import org.jooq.DSLContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_INPUT_VALUE_CONDITION_CONTEXT_ARG_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_INPUT_VALUE_CONDITION_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_INPUT_VALUE_BINDING_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_INPUT_VALUE_REFERENCE_CONDITION_STEP_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_INPUT_VALUE_REFERENCE_FOR_TABLE_STEP_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_INPUT_VALUE_REFERENCE_KEY_STEP_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_INPUT_VALUE_REFERENCE_TABLE_STEP_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_INPUT_VALUE_NODE_ID_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_INPUT_VALUE_REFERENCE_FOR_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_AST_FIELD_ARGUMENT_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_AST_INPUT_FIELD_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_AST_INPUT_VALUE_DIRECTIVE_ENTRY;
import static no.sikt.graphitron.model.test.SeededStore.withSeededStore;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * The input-value site's decode: one writer over the three parents the parser treats alike.
 *
 * <p>The claims worth standing up here are the ones the site's shape decides rather than the ones
 * any decode would satisfy. That an input object's field and a field's argument land in one
 * relation is the whole reason this writer exists, the incumbent having routed the first down its
 * field path. That an argument-less directive writes nothing is what keeps a relation from existing
 * only to be a coordinate. And that a repeatable directive needs no ordinal is what the position
 * keying buys.
 */
class GraphitronInputValueEntriesTest {

    private static final String GRAPH = "input-value";

    /**
     * The two parents a consumer schema writes, decoded into one relation. Read back through each
     * parent's own coordinate, which is the join the design leaves as a detection rather than a
     * key: an application names its parent by position, and a position is unique in a file whichever
     * of the three relations holds the parent.
     *
     * <p>The third parent is a directive definition's own argument, and no decode row can be shown
     * at it because no graphitron directive is written there in any corpus we read. One directive is:
     * the bundled vocabulary deprecates {@code @asConnection(connectionName:)}, so that arm of the
     * union carries a row on every reading and the two cases below have to say which file they mean.
     */
    @Test
    @DisplayName("an input object's field and a field's argument decode into one relation")
    void bothParentsLandInOneRelation(@TempDir Path tmp) {
        write(tmp, "film.graphqls", """
            type Query { films(titleFilter: String @field(name: "title")): [Film!] }
            type Film { title: String }
            input FilmFilter { category: String @field(name: "category") }
            """);

        withSeededStore(GRAPH, dsl -> {
            read(dsl, tmp);
            var decode = GRAPHITRON_AST_INPUT_VALUE_BINDING_ENTRY;
            var applied = GRAPHQL_AST_INPUT_VALUE_DIRECTIVE_ENTRY;
            var argument = GRAPHQL_AST_FIELD_ARGUMENT_ENTRY;
            var inputField = GRAPHQL_AST_INPUT_FIELD_ENTRY;

            assertThat(dsl.select(argument.COORDINATE, decode.NAME_REF)
                    .from(decode)
                    .join(applied).on(applied.GRAPH_NAME.eq(decode.GRAPH_NAME))
                        .and(applied.SOURCE_NAME.eq(decode.SOURCE_NAME))
                        .and(applied.SOURCE_LINE.eq(decode.SOURCE_LINE))
                        .and(applied.SOURCE_COLUMN.eq(decode.SOURCE_COLUMN))
                    .join(argument).on(argument.GRAPH_NAME.eq(applied.GRAPH_NAME))
                        .and(argument.SOURCE_NAME.eq(applied.SOURCE_NAME))
                        .and(argument.SOURCE_LINE.eq(applied.PARENT_LINE))
                        .and(argument.SOURCE_COLUMN.eq(applied.PARENT_COLUMN))
                    .fetch())
                .as("the decode of an application written on a field's argument")
                .extracting(row -> row.value1(), row -> row.value2())
                .containsExactly(tuple("Query.films(titleFilter:)", "title"));

            assertThat(dsl.select(inputField.COORDINATE, decode.NAME_REF)
                    .from(decode)
                    .join(applied).on(applied.GRAPH_NAME.eq(decode.GRAPH_NAME))
                        .and(applied.SOURCE_NAME.eq(decode.SOURCE_NAME))
                        .and(applied.SOURCE_LINE.eq(decode.SOURCE_LINE))
                        .and(applied.SOURCE_COLUMN.eq(decode.SOURCE_COLUMN))
                    .join(inputField).on(inputField.GRAPH_NAME.eq(applied.GRAPH_NAME))
                        .and(inputField.SOURCE_NAME.eq(applied.SOURCE_NAME))
                        .and(inputField.SOURCE_LINE.eq(applied.PARENT_LINE))
                        .and(inputField.SOURCE_COLUMN.eq(applied.PARENT_COLUMN))
                    .fetch())
                .as("and of one written on an input object's field, out of the same relation")
                .extracting(row -> row.value1(), row -> row.value2())
                .containsExactly(tuple("FilmFilter.category", "category"));

            assertThat(dsl.fetchCount(decode))
                .as("two rows and one relation, which is what the incumbent's two paths are not")
                .isEqualTo(2);
        });
    }

    /**
     * The three names that declare no arguments. Their applications are recorded, which is the
     * fact; a decode row would carry its key and repeat it.
     */
    @Test
    @DisplayName("an argument-less directive is applied and decodes to nothing")
    void anArgumentLessDirectiveWritesNoDecode(@TempDir Path tmp) {
        write(tmp, "film.graphqls", """
            type Query { films(orderBy: FilmOrder @orderBy, id: ID @lookupKey): [Film!] }
            type Film { title: String }
            enum FilmOrder { TITLE }
            input FilmFilter { title: String @asFacet }
            """);

        withSeededStore(GRAPH, dsl -> {
            read(dsl, tmp);

            assertThat(dsl.select(GRAPHQL_AST_INPUT_VALUE_DIRECTIVE_ENTRY.NAME)
                    .from(GRAPHQL_AST_INPUT_VALUE_DIRECTIVE_ENTRY)
                    .where(GRAPHQL_AST_INPUT_VALUE_DIRECTIVE_ENTRY.SOURCE_NAME.eq(
                        source(tmp, "film.graphqls")))
                    .orderBy(GRAPHQL_AST_INPUT_VALUE_DIRECTIVE_ENTRY.NAME)
                    .fetch(GRAPHQL_AST_INPUT_VALUE_DIRECTIVE_ENTRY.NAME))
                .as("the applications are what says they were applied")
                .containsExactly("asFacet", "lookupKey", "orderBy");
            assertThat(dsl.fetchCount(GRAPHITRON_AST_INPUT_VALUE_BINDING_ENTRY)
                    + dsl.fetchCount(GRAPHITRON_AST_INPUT_VALUE_CONDITION_ENTRY)
                    + dsl.fetchCount(GRAPHITRON_AST_INPUT_VALUE_REFERENCE_FOR_ENTRY)
                    + dsl.fetchCount(GRAPHITRON_AST_INPUT_VALUE_REFERENCE_TABLE_STEP_ENTRY)
                    + dsl.fetchCount(GRAPHITRON_AST_INPUT_VALUE_NODE_ID_ENTRY))
                .as("and no decode relation holds a row for any of them")
                .isZero();
        });
    }

    /**
     * Two applications of a repeatable directive on one input value. The incumbent needed an
     * ordinal to tell them apart because a coordinate could not; each stands at its own at sign
     * here, so the key does it.
     */
    @Test
    @DisplayName("two applications of a repeatable directive are two rows and carry no ordinal")
    void aRepeatableDirectiveNeedsNoOrdinal(@TempDir Path tmp) {
        write(tmp, "film.graphqls", """
            type Query {
              media(
                target: ID
                  @referenceFor(type: "Book", path: [{table: "book"}])
                  @referenceFor(type: "Film", path: [{table: "film"}])
              ): [Film!]
            }
            type Film { title: String }
            """);

        withSeededStore(GRAPH, dsl -> {
            read(dsl, tmp);
            var t = GRAPHITRON_AST_INPUT_VALUE_REFERENCE_FOR_ENTRY;

            assertThat(dsl.select(t.PARTICIPANT_TYPE_REF, t.SOURCE_LINE)
                    .from(t).orderBy(t.PARTICIPANT_TYPE_REF).fetch())
                .as("two decodes at two positions, told apart by the key and nothing else")
                .extracting(row -> row.value1())
                .containsExactly("Book", "Film");
            assertThat(dsl.select(GRAPHITRON_AST_INPUT_VALUE_REFERENCE_FOR_TABLE_STEP_ENTRY.TABLE_REF)
                    .from(GRAPHITRON_AST_INPUT_VALUE_REFERENCE_FOR_TABLE_STEP_ENTRY)
                    .orderBy(GRAPHITRON_AST_INPUT_VALUE_REFERENCE_FOR_TABLE_STEP_ENTRY.TABLE_REF)
                    .fetch(GRAPHITRON_AST_INPUT_VALUE_REFERENCE_FOR_TABLE_STEP_ENTRY.TABLE_REF))
                .as("each application's path hangs off its own decode")
                .containsExactly("book", "film");
        });
    }

    /**
     * One step, as many rows as the author made claims. The element naming a table and a key writes
     * to both relations at one position, which is what says they are one step, and every relation
     * stays total: the resolution picks its arm by which relation the row is in rather than by a
     * predicate over which columns are null.
     */
    @Test
    @DisplayName("a path element writes one row per claim it makes, at one position")
    void aStepWritesToEveryRelationItsClaimsWarrant(@TempDir Path tmp) {
        write(tmp, "film.graphqls", """
            type Query {
              actors(
                agencyId: ID @reference(path: [
                  {table: "sakila.agency"},
                  {table: "actor", key: "sakila.actor_agency_fk",
                   condition: {className: "no.example.Conditions", method: "live", argMapping: "a: b"}}
                ])
              ): [Film!]
            }
            type Film { title: String }
            """);

        withSeededStore(GRAPH, dsl -> {
            read(dsl, tmp);
            var tables = GRAPHITRON_AST_INPUT_VALUE_REFERENCE_TABLE_STEP_ENTRY;
            var keys = GRAPHITRON_AST_INPUT_VALUE_REFERENCE_KEY_STEP_ENTRY;
            var conditions = GRAPHITRON_AST_INPUT_VALUE_REFERENCE_CONDITION_STEP_ENTRY;

            assertThat(dsl.select(tables.POSITION, tables.TABLE_REF_NAMESPACE_PART,
                        tables.TABLE_REF_NAME_PART)
                    .from(tables).orderBy(tables.POSITION).fetch())
                .extracting(row -> row.value1(), row -> row.value2(), row -> row.value3())
                .as("both elements name a table, each cut where the grammar cuts it")
                .containsExactly(tuple(0, "sakila", "agency"), tuple(1, null, "actor"));

            assertThat(dsl.select(keys.POSITION, keys.KEY_REF_NAMESPACE_PART, keys.KEY_REF_NAME_PART)
                    .from(keys).fetch())
                .extracting(row -> row.value1(), row -> row.value2(), row -> row.value3())
                .as("only the second names a key, and it lands at that element's own position")
                .containsExactly(tuple(1, "sakila", "actor_agency_fk"));

            assertThat(dsl.select(conditions.POSITION, conditions.CLASS_NAME, conditions.METHOD,
                        conditions.ARGMAPPING)
                    .from(conditions).fetch())
                .extracting(row -> row.value1(), row -> row.value2(), row -> row.value3(),
                    row -> row.value4())
                .as("and only the second carries a condition, the one arm that reaches the classpath")
                .containsExactly(tuple(1, "no.example.Conditions", "live", "a: b"));
        });
    }

    /**
     * A required argument the author omitted is a malformed application, not an intent. No decode
     * row is written for it: the applied-directive row says the directive was applied, and what
     * they wrote instead stands in the argument's own value.
     */
    @Test
    @DisplayName("a malformed application is applied and decodes to nothing")
    void aMalformedApplicationWritesNoDecode(@TempDir Path tmp) {
        write(tmp, "film.graphqls", """
            type Query { films(titleFilter: String @field, actorId: ID @nodeId): [Film!] }
            type Film { title: String }
            """);

        withSeededStore(GRAPH, dsl -> {
            read(dsl, tmp);

            assertThat(dsl.select(GRAPHQL_AST_INPUT_VALUE_DIRECTIVE_ENTRY.NAME)
                    .from(GRAPHQL_AST_INPUT_VALUE_DIRECTIVE_ENTRY)
                    .where(GRAPHQL_AST_INPUT_VALUE_DIRECTIVE_ENTRY.SOURCE_NAME.eq(
                        source(tmp, "film.graphqls")))
                    .orderBy(GRAPHQL_AST_INPUT_VALUE_DIRECTIVE_ENTRY.NAME)
                    .fetch(GRAPHQL_AST_INPUT_VALUE_DIRECTIVE_ENTRY.NAME))
                .as("both were applied, which is what the applied-directive row is for")
                .containsExactly("field", "nodeId");
            assertThat(dsl.fetchCount(GRAPHITRON_AST_INPUT_VALUE_BINDING_ENTRY))
                .as("@field without its required name decodes to nothing rather than to a null")
                .isZero();
            assertThat(dsl.fetchCount(GRAPHITRON_AST_INPUT_VALUE_NODE_ID_ENTRY))
                .as("and a bare @nodeId asks for the deduction, which is a row's absence")
                .isZero();
        });
    }

    /**
     * A definition default is not a written value. Filling it in would lose the difference, and the
     * default itself already stands in {@code graphql_directive_argument.default_value_sdl}.
     */
    @Test
    @DisplayName("an override the author did not write is null, not the definition's false")
    void anUnwrittenDefaultStaysNull(@TempDir Path tmp) {
        write(tmp, "film.graphqls", """
            type Query {
              films(
                scope: ID @condition(
                  condition: {className: "no.example.Conditions", method: "inScope"}
                  contextArguments: ["tenantId", "userId"]
                )
                other: ID @condition(condition: {className: "no.example.Conditions"}, override: true)
              ): [Film!]
            }
            type Film { title: String }
            """);

        withSeededStore(GRAPH, dsl -> {
            read(dsl, tmp);
            var t = GRAPHITRON_AST_INPUT_VALUE_CONDITION_ENTRY;

            assertThat(dsl.select(t.METHOD, t.OVERRIDE).from(t).orderBy(t.SOURCE_LINE).fetch())
                .extracting(row -> row.value1(), row -> row.value2())
                .as("null where the author wrote nothing, and what they wrote where they did")
                .containsExactly(tuple("inScope", null), tuple(null, true));
            assertThat(dsl.select(GRAPHITRON_AST_INPUT_VALUE_CONDITION_CONTEXT_ARG_ENTRY.POSITION,
                        GRAPHITRON_AST_INPUT_VALUE_CONDITION_CONTEXT_ARG_ENTRY.NAME)
                    .from(GRAPHITRON_AST_INPUT_VALUE_CONDITION_CONTEXT_ARG_ENTRY)
                    .orderBy(GRAPHITRON_AST_INPUT_VALUE_CONDITION_CONTEXT_ARG_ENTRY.POSITION)
                    .fetch())
                .extracting(row -> row.value1(), row -> row.value2())
                .as("the list argument keeps the order the author wrote")
                .containsExactly(tuple(0, "tenantId"), tuple(1, "userId"));
        });
    }

    /**
     * Two levels of cascade, which is this site's own shape: a context argument hangs off the
     * condition decode, which hangs off the application row. Both references have to cascade, and
     * the failure if the second does not is louder than a stale row: the entry sweep's delete of the
     * application is refused outright, the context argument still standing on the decode the cascade
     * is trying to take. Verified by removing that cascade and watching the reading throw.
     */
    @Test
    @DisplayName("an application the author deleted takes its decode and the decode's children")
    void aDeletedApplicationTakesTwoLevels(@TempDir Path tmp) {
        write(tmp, "film.graphqls", """
            type Query {
              films(scope: ID @condition(
                condition: {className: "no.example.Conditions"}
                contextArguments: ["tenantId", "userId"]
              )): [Film!]
            }
            type Film { title: String }
            """);

        withSeededStore(GRAPH, dsl -> {
            read(dsl, tmp);
            assertThat(dsl.fetchCount(GRAPHITRON_AST_INPUT_VALUE_CONDITION_CONTEXT_ARG_ENTRY))
                .as("before the edit").isEqualTo(2);

            write(tmp, "film.graphqls", """
                type Query { films(scope: ID): [Film!] }
                type Film { title: String }
                """);
            read(dsl, tmp);

            assertThat(dsl.fetchCount(GRAPHITRON_AST_INPUT_VALUE_CONDITION_ENTRY))
                .as("the application is gone, so what it meant is not a fact any more").isZero();
            assertThat(dsl.fetchCount(GRAPHITRON_AST_INPUT_VALUE_CONDITION_CONTEXT_ARG_ENTRY))
                .as("and neither is what stood inside it, two references down")
                .isZero();
        });
    }

    /**
     * The case the cascade cannot reach: the position survives because another directive now
     * occupies it, so the application row is updated rather than deleted and only this writer's own
     * sweep can find what it left behind. Its children go with it, by their own cascade.
     */
    @Test
    @DisplayName("a decode whose position another directive now occupies is swept, children too")
    void aDecodeReplacedInPlaceIsSwept(@TempDir Path tmp) {
        write(tmp, "film.graphqls", """
            type Query {
              films(scope: ID @condition(
                condition: {className: "no.example.Conditions"}
                contextArguments: ["tenantId"]
              )): [Film!]
            }
            type Film { title: String }
            """);

        withSeededStore(GRAPH, dsl -> {
            read(dsl, tmp);
            assertThat(dsl.fetchCount(GRAPHITRON_AST_INPUT_VALUE_CONDITION_CONTEXT_ARG_ENTRY))
                .as("before the edit").isEqualTo(1);

            write(tmp, "film.graphqls", """
                type Query { films(scope: ID @field(name: "scope")): [Film!] }
                type Film { title: String }
                """);
            read(dsl, tmp);

            assertThat(dsl.fetchCount(GRAPHQL_AST_INPUT_VALUE_DIRECTIVE_ENTRY,
                    GRAPHQL_AST_INPUT_VALUE_DIRECTIVE_ENTRY.SOURCE_NAME.eq(
                        source(tmp, "film.graphqls"))))
                .as("one application in this file, at the position the other one had").isEqualTo(1);
            assertThat(dsl.fetchCount(GRAPHITRON_AST_INPUT_VALUE_CONDITION_ENTRY))
                .as("and the condition it replaced is not a fact any more").isZero();
            assertThat(dsl.fetchCount(GRAPHITRON_AST_INPUT_VALUE_CONDITION_CONTEXT_ARG_ENTRY))
                .as("nor its context arguments, which went with the decode they hung on").isZero();
            assertThat(dsl.fetchCount(GRAPHITRON_AST_INPUT_VALUE_BINDING_ENTRY))
                .as("what the author wrote instead is there").isEqualTo(1);
        });
    }

    /** One reading of everything the directory holds, which is what a run does. */
    private static void read(DSLContext dsl, Path baseDir) {
        SdlCapture.capture(dsl, new GraphIdentity(GRAPH, baseDir),
            SubjectConfig.of(new SchemaRecipe(baseDir.resolve("pom.xml"),
                List.of(SchemaRecipe.Binding.pattern("*.graphqls")), List.of("graphqls"))),
            LocalDateTime.now());
    }

    /** The name a row carries for a file this case wrote, which is the path the parse was handed. */
    private static String source(Path directory, String name) {
        return directory.resolve(name).toString();
    }

    private static void write(Path directory, String name, String sdl) {
        try {
            Files.writeString(directory.resolve(name), sdl, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
