package no.sikt.graphitron.model.intent;

import org.jooq.DSLContext;
import org.jooq.Record;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Consumer;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_MUTATION;
import static no.sikt.graphitron.model.Tables.GRAPHQL_ARGUMENT;
import static no.sikt.graphitron.model.Tables.INTENT_MUTATION_WRITE_PAYLOAD;
import static no.sikt.graphitron.model.test.SeededStore.derive;
import static no.sikt.graphitron.model.test.SeededStore.seedArgument;
import static no.sikt.graphitron.model.test.SeededStore.seedArgumentCondition;
import static no.sikt.graphitron.model.test.SeededStore.seedConstraint;
import static no.sikt.graphitron.model.test.SeededStore.seedField;
import static no.sikt.graphitron.model.test.SeededStore.seedGraphSource;
import static no.sikt.graphitron.model.test.SeededStore.seedMutation;
import static no.sikt.graphitron.model.test.SeededStore.seedRootOperation;
import static no.sikt.graphitron.model.test.SeededStore.seedSource;
import static no.sikt.graphitron.model.test.SeededStore.seedTable;
import static no.sikt.graphitron.model.test.SeededStore.seedTableBinding;
import static no.sikt.graphitron.model.test.SeededStore.seedType;
import static no.sikt.graphitron.model.test.SeededStore.withSeededStore;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * What {@code intent_mutation_write_payload} states: which coordinate writes, with which verb, over
 * which table, through which argument. The first relation in the input-field family keyed by the
 * mutation rather than by a field, and the cases divide the same way the relation's own population
 * rule does.
 *
 * <p>The write-target section asserts which rung of {@code intent_field_scope_table} answered, by
 * asserting the table a coordinate whose rungs disagree resolves; the rung itself is not a column
 * here, so the claim is made through a fixture where only one rung could have produced the answer.
 * The DELETE cases carry the load there: a DELETE has no return-derived rung at all, so a DELETE
 * returning a bound type and naming no table is the one shape where reading the scope relation
 * naively would produce a write surface the classifier refuses.
 *
 * <p>The argument section is the three facts this relation folds into an absence, and each case
 * exists because folding is a choice: a payload whose argument shape is refused is not a payload
 * with something wrong in it, which is what makes the absence honest where a per-field refusal's
 * would not be.
 */
class MutationWritePayloadTest {

    private static final String GRAPH = "g";
    private static final String PKG = "cat";
    private static final String PUBLIC = "public";

    // ===== The write target, read through the verb =====

    /** The ordinary UPDATE: the return names the table, and the argument carries the payload. */
    @Test
    void anUpdateReturningItsTableWritesThatTable() {
        withCatalog(dsl -> {
            seedTableBinding(dsl, GRAPH, "Film", "film");
            seedField(dsl, GRAPH, "Mutation", "updateFilm", "Film", false);
            seedMutation(dsl, GRAPH, "Mutation", "updateFilm", "UPDATE");
            seedInputArgument(dsl, "updateFilm", "in", "FilmUpdateInput");

            assertThat(rows(dsl)).containsExactly(
                "Mutation.updateFilm UPDATE film in:FilmUpdateInput single row");
        });
    }

    /**
     * An UPDATE returning a carrier payload writes the table its data channel binds. The shape the
     * store could not see at all before {@code intent_field_scope_table} gained the payload rung,
     * and the reason this relation is worth pinning against that one rather than only against the
     * classifier.
     */
    @Test
    void anUpdateReturningACarrierPayloadWritesTheChannelsTable() {
        withCatalog(dsl -> {
            seedRootOperation(dsl, GRAPH, "MUTATION", "Mutation");
            seedTableBinding(dsl, GRAPH, "Film", "film");
            seedType(dsl, GRAPH, "FilmPayload", "OBJECT");
            seedField(dsl, GRAPH, "FilmPayload", "film", "Film", false);
            seedField(dsl, GRAPH, "Mutation", "updateFilmPayload", "FilmPayload", false);
            seedMutation(dsl, GRAPH, "Mutation", "updateFilmPayload", "UPDATE");
            seedInputArgument(dsl, "updateFilmPayload", "in", "FilmUpdateInput");

            assertThat(rows(dsl)).containsExactly(
                "Mutation.updateFilmPayload UPDATE film in:FilmUpdateInput single row");
        });
    }

    /** A DELETE names its table on the field, its return being a scalar nothing binds. */
    @Test
    void aDeleteWritesTheTableTheFieldNames() {
        withCatalog(dsl -> {
            seedField(dsl, GRAPH, "Mutation", "deleteFilm", "ID", false);
            seedMutation(dsl, GRAPH, "Mutation", "deleteFilm", "DELETE", "film");
            seedInputArgument(dsl, "deleteFilm", "in", "FilmDeleteInput");

            assertThat(rows(dsl)).containsExactly(
                "Mutation.deleteFilm DELETE film in:FilmDeleteInput single row");
        });
    }

    /**
     * A DELETE whose return binds a table and which names none writes nothing. The scope relation
     * answers this coordinate on its top rung and the answer is not the classifier's: a DELETE has
     * no return-derived rung, the row being gone once the statement runs, so reading the scope
     * without the verb would hand a consumer a write target the resolver refuses to accept.
     */
    @Test
    void aDeleteWhoseReturnBindsATableAndWhichNamesNoneIsNoWriteSurface() {
        withCatalog(dsl -> {
            seedTableBinding(dsl, GRAPH, "Film", "film");
            seedField(dsl, GRAPH, "Mutation", "deleteFilm", "Film", false);
            seedMutation(dsl, GRAPH, "Mutation", "deleteFilm", "DELETE");
            seedInputArgument(dsl, "deleteFilm", "in", "FilmDeleteInput");

            assertThat(rows(dsl)).isEmpty();
        });
    }

    /**
     * INSERT and UPSERT are outside the population, and the boundary is the admission gate rather
     * than the statement: INSERT resolves its input through a gate that admits a carrier the two
     * walkers refuse, and UPSERT is refused at the verb dispatch before any input is read.
     */
    @Test
    void anInsertIsNoWalkerDrivenWriteSurface() {
        withCatalog(dsl -> {
            seedTableBinding(dsl, GRAPH, "Film", "film");
            seedField(dsl, GRAPH, "Mutation", "createFilm", "Film", false);
            seedMutation(dsl, GRAPH, "Mutation", "createFilm", "INSERT");
            seedInputArgument(dsl, "createFilm", "in", "FilmCreateInput");

            assertThat(rows(dsl)).isEmpty();
        });
    }

    // ===== The cardinality columns =====

    /**
     * {@code multiRow: true} on a DELETE is carried and not filtered on: it is the arm that opts
     * out of needing a key, so the same two columns describe a statement deleting one row and one
     * deleting many.
     */
    @Test
    void aMultiRowDeleteCarriesTheFlag() {
        withCatalog(dsl -> {
            seedField(dsl, GRAPH, "Mutation", "deleteFilms", "ID", true);
            seedMutation(dsl, GRAPH, "Mutation", "deleteFilms", "DELETE", "film");
            seedMultiRow(dsl, "deleteFilms");
            seedInputArgument(dsl, "deleteFilms", "in", "FilmDeleteInput");

            assertThat(rows(dsl)).containsExactly(
                "Mutation.deleteFilms DELETE film in:FilmDeleteInput single rows");
        });
    }

    /**
     * The same spelling on an UPDATE is a refusal rather than a flag. Broadcast semantics has no
     * UPDATE reading, so the coordinate has no write surface at all and the column is false on
     * every row here by construction.
     */
    @Test
    void aMultiRowUpdateIsNoWriteSurface() {
        withCatalog(dsl -> {
            seedTableBinding(dsl, GRAPH, "Film", "film");
            seedField(dsl, GRAPH, "Mutation", "updateFilm", "Film", false);
            seedMutation(dsl, GRAPH, "Mutation", "updateFilm", "UPDATE");
            seedMultiRow(dsl, "updateFilm");
            seedInputArgument(dsl, "updateFilm", "in", "FilmUpdateInput");

            assertThat(rows(dsl)).isEmpty();
        });
    }

    /**
     * A list-shaped argument is the bulk form: one statement over several payload rows. Carried as
     * a column because the cardinality is the emitter's fork, not an admissibility question.
     */
    @Test
    void aListArgumentIsTheBulkForm() {
        withCatalog(dsl -> {
            seedTableBinding(dsl, GRAPH, "Film", "film");
            seedField(dsl, GRAPH, "Mutation", "updateFilms", "Film", true);
            seedMutation(dsl, GRAPH, "Mutation", "updateFilms", "UPDATE");
            seedInputArgument(dsl, "updateFilms", "in", "FilmUpdateInput");
            seedListArgument(dsl, "updateFilms", "in");

            assertThat(rows(dsl)).containsExactly(
                "Mutation.updateFilms UPDATE film in:FilmUpdateInput bulk row");
        });
    }

    // ===== The argument shape, which is what this relation folds into an absence =====

    /** Two arguments is not a payload with something wrong in it; it is no payload. */
    @Test
    void aSecondArgumentIsNoWriteSurface() {
        withCatalog(dsl -> {
            seedTableBinding(dsl, GRAPH, "Film", "film");
            seedField(dsl, GRAPH, "Mutation", "updateFilm", "Film", false);
            seedMutation(dsl, GRAPH, "Mutation", "updateFilm", "UPDATE");
            seedInputArgument(dsl, "updateFilm", "in", "FilmUpdateInput");
            seedArgument(dsl, GRAPH, "Mutation", "updateFilm", "dryRun", "Boolean");

            assertThat(rows(dsl)).isEmpty();
        });
    }

    /** A scalar argument is the same refusal reached the other way: the sole argument is no input. */
    @Test
    void aScalarArgumentIsNoWriteSurface() {
        withCatalog(dsl -> {
            seedTableBinding(dsl, GRAPH, "Film", "film");
            seedField(dsl, GRAPH, "Mutation", "updateFilm", "Film", false);
            seedMutation(dsl, GRAPH, "Mutation", "updateFilm", "UPDATE");
            seedType(dsl, GRAPH, "ID", "SCALAR");
            seedArgument(dsl, GRAPH, "Mutation", "updateFilm", "id", "ID");

            assertThat(rows(dsl)).isEmpty();
        });
    }

    /**
     * A {@code @condition} on the payload argument is refused outright, which is the argument's own
     * property rather than any field's and is why it is folded in here.
     */
    @Test
    void aConditionOnThePayloadArgumentIsNoWriteSurface() {
        withCatalog(dsl -> {
            seedTableBinding(dsl, GRAPH, "Film", "film");
            seedField(dsl, GRAPH, "Mutation", "updateFilm", "Film", false);
            seedMutation(dsl, GRAPH, "Mutation", "updateFilm", "UPDATE");
            seedInputArgument(dsl, "updateFilm", "in", "FilmUpdateInput");
            seedArgumentCondition(dsl, GRAPH, "Mutation", "updateFilm", "in", false);

            assertThat(rows(dsl)).isEmpty();
        });
    }

    /**
     * A field carrying no {@code @mutation} has no write surface however its return binds, the
     * population being the directive's and not the return type's.
     */
    @Test
    void aFieldWithNoMutationDirectiveIsNoWriteSurface() {
        withCatalog(dsl -> {
            seedTableBinding(dsl, GRAPH, "Film", "film");
            seedField(dsl, GRAPH, "Mutation", "updateFilm", "Film", false);
            seedInputArgument(dsl, "updateFilm", "in", "FilmUpdateInput");

            assertThat(rows(dsl)).isEmpty();
        });
    }

    /** The graph partition holds: a sibling graph reads none of this one's write surfaces. */
    @Test
    void aSiblingGraphReadsNoneOfTheWriteSurfaces() {
        withCatalog(dsl -> {
            seedTableBinding(dsl, GRAPH, "Film", "film");
            seedField(dsl, GRAPH, "Mutation", "updateFilm", "Film", false);
            seedMutation(dsl, GRAPH, "Mutation", "updateFilm", "UPDATE");
            seedInputArgument(dsl, "updateFilm", "in", "FilmUpdateInput");

            assertThat(rowsIn(dsl, "other")).isEmpty();
        });
    }

    // ===== Fixture =====

    private static void withCatalog(Consumer<DSLContext> body) {
        withSeededStore(GRAPH, dsl -> {
            seedSource(dsl, PKG, "JOOQ_SCHEMA");
            seedGraphSource(dsl, GRAPH, PKG);
            for (String table : List.of("film", "actor")) {
                seedTable(dsl, PKG, PUBLIC, table);
                seedConstraint(dsl, PKG, PUBLIC, table, table + "_pkey", "PRIMARY KEY", null);
            }
            seedType(dsl, GRAPH, "ID", "SCALAR");
            seedType(dsl, GRAPH, "Boolean", "SCALAR");
            body.accept(dsl);
        });
    }

    /** The sole input-object argument a payload arrives through, with the input type it names. */
    private static void seedInputArgument(DSLContext dsl, String fieldName,
                                          String argumentName, String inputTypeName) {
        seedType(dsl, GRAPH, inputTypeName, "INPUT_OBJECT");
        seedArgument(dsl, GRAPH, "Mutation", fieldName, argumentName, inputTypeName);
    }

    /** {@code @mutation(multiRow: true)}, which the seeding helper leaves unstated. */
    private static void seedMultiRow(DSLContext dsl, String fieldName) {
        dsl.update(GRAPHITRON_MUTATION)
            .set(GRAPHITRON_MUTATION.MULTI_ROW, true)
            .where(GRAPHITRON_MUTATION.GRAPH_NAME.eq(GRAPH))
            .and(GRAPHITRON_MUTATION.FIELD_NAME.eq(fieldName))
            .execute();
    }

    /** The list wrapper on an argument, which the seeding helper writes single-valued. */
    private static void seedListArgument(DSLContext dsl, String fieldName, String argumentName) {
        dsl.update(GRAPHQL_ARGUMENT)
            .set(GRAPHQL_ARGUMENT.IS_LIST, true)
            .where(GRAPHQL_ARGUMENT.GRAPH_NAME.eq(GRAPH))
            .and(GRAPHQL_ARGUMENT.FIELD_NAME.eq(fieldName))
            .and(GRAPHQL_ARGUMENT.ARGUMENT_NAME.eq(argumentName))
            .execute();
    }

    private static List<String> rows(DSLContext dsl) {
        return rowsIn(dsl, GRAPH);
    }

    private static List<String> rowsIn(DSLContext dsl, String graphName) {
        derive(dsl);
        return dsl.select(INTENT_MUTATION_WRITE_PAYLOAD.fields())
            .from(INTENT_MUTATION_WRITE_PAYLOAD)
            .where(INTENT_MUTATION_WRITE_PAYLOAD.GRAPH_NAME.eq(graphName))
            .orderBy(INTENT_MUTATION_WRITE_PAYLOAD.TYPE_NAME,
                INTENT_MUTATION_WRITE_PAYLOAD.FIELD_NAME)
            .fetch()
            .map(MutationWritePayloadTest::render);
    }

    /**
     * The coordinate, the verb, the table written, the argument the payload arrives through, and the
     * two cardinalities: the whole row, because every column of it is a claim some case here makes.
     */
    private static String render(Record row) {
        return row.get(INTENT_MUTATION_WRITE_PAYLOAD.TYPE_NAME) + "."
            + row.get(INTENT_MUTATION_WRITE_PAYLOAD.FIELD_NAME) + " "
            + row.get(INTENT_MUTATION_WRITE_PAYLOAD.OPERATION) + " "
            + row.get(INTENT_MUTATION_WRITE_PAYLOAD.WRITE_TABLE) + " "
            + row.get(INTENT_MUTATION_WRITE_PAYLOAD.ARGUMENT_NAME) + ":"
            + row.get(INTENT_MUTATION_WRITE_PAYLOAD.ARGUMENT_TYPE_NAME) + " "
            + (row.get(INTENT_MUTATION_WRITE_PAYLOAD.ARGUMENT_LIST) ? "bulk" : "single") + " "
            + (row.get(INTENT_MUTATION_WRITE_PAYLOAD.MULTI_ROW) ? "rows" : "row");
    }
}
