package no.sikt.graphitron.rewrite.capture;

import no.sikt.graphitron.common.configuration.TestConfiguration;
import no.sikt.graphitron.model.test.CapturedStore;
import no.sikt.graphitron.model.jooq.JooqCatalog;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.jooq.DSLContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.function.Consumer;

import static no.sikt.graphitron.model.Tables.SQL_COLUMN;
import static no.sikt.graphitron.model.Tables.SQL_ENUM_BINDING;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the catalog walk writes for the enum classes a generated model binds columns to. The
 * relation exists to answer one predicate the rest of the store cannot: whether a named class is an
 * enum, for the generated classes the classpath census deliberately excludes. So the cases here pin
 * the two properties that predicate rests on, that a generated enum is reachable at all and that
 * the answer is keyed on the class rather than on the column that led to it.
 *
 * <p>Against the sakila catalog, which declares three database enum types and binds one of them
 * from three separate tables. There is no converter-bound Java enum in the fixture, which is why
 * the nullable half of the row is stated in the relation's comment rather than pinned here: a case
 * asserting a shape no fixture produces would be asserting the assertion.
 */
@PipelineTier
class EnumBindingCaptureTest {

    private static final String ENUM_PACKAGE = "no.sikt.graphitron.rewrite.test.jooq.enums.";

    private static final String SDL = """
        type Query {
          films: [Film!]!
        }

        type Film {
          id: ID!
        }
        """;

    /**
     * The database enum, read off the generated class rather than off the column: the class states
     * its own schema and type name through jOOQ's enum interface, which is what lets the row name
     * the type an author would recognise in their database instead of only the class.
     */
    @Test
    @DisplayName("a generated enum lands with its class, its schema and its database type name")
    void aGeneratedEnumLandsWithItsDatabaseCoordinate(@TempDir Path tmp) {
        withCatalog(tmp, dsl -> {
            var row = dsl.selectFrom(SQL_ENUM_BINDING)
                .where(SQL_ENUM_BINDING.CLASS_FQN.eq(ENUM_PACKAGE + "MpaaRating"))
                .fetchOne();
            assertThat(row).isNotNull();
            assertThat(row.getTableSchema()).isEqualTo("public");
            assertThat(row.getTypeName()).isEqualTo("mpaa_rating");
        });
    }

    /**
     * The grain, and the one property a column-keyed relation would not have. The fixture binds one
     * enum type from three tables, so a relation that had followed the column would answer this
     * predicate three times and leave a reader to decide whether the three agreed.
     */
    @Test
    @DisplayName("an enum several columns bind is one row, not one per column")
    void anEnumBoundBySeveralColumnsIsOneRow(@TempDir Path tmp) {
        withCatalog(tmp, dsl -> {
            String subjectKind = ENUM_PACKAGE + "SubjectKind";
            assertThat(dsl.fetchCount(SQL_COLUMN, SQL_COLUMN.BINDING_TYPE.eq(subjectKind)))
                .as("the fixture binds this enum from more than one column")
                .isGreaterThan(1);
            assertThat(dsl.fetchCount(SQL_ENUM_BINDING, SQL_ENUM_BINDING.CLASS_FQN.eq(subjectKind)))
                .isEqualTo(1);
        });
    }

    /**
     * The population's edge. Every row here is an enum and nothing else is, which is what makes a
     * reader's absence-is-not-known-to-be-an-enum reading safe: a present row is never a class that
     * merely backs a column.
     */
    @Test
    @DisplayName("only enum-bound classes are rows, and every declared enum type is one")
    void onlyEnumBoundClassesAreRows(@TempDir Path tmp) {
        withCatalog(tmp, dsl -> {
            assertThat(dsl.select(SQL_ENUM_BINDING.CLASS_FQN).from(SQL_ENUM_BINDING)
                .fetch(SQL_ENUM_BINDING.CLASS_FQN))
                .containsExactlyInAnyOrder(
                    ENUM_PACKAGE + "MpaaRating",
                    ENUM_PACKAGE + "ContentKind",
                    ENUM_PACKAGE + "SubjectKind");
            assertThat(dsl.fetchExists(SQL_ENUM_BINDING,
                SQL_ENUM_BINDING.CLASS_FQN.eq(String.class.getName())))
                .as("a column bound to a plain scalar class contributes nothing")
                .isFalse();
        });
    }

    /**
     * A second walk over the same source refreshes rather than collides. The clear runs beside the
     * column round that reaches these rows, so a capture that had left this relation out of it
     * would fail here on a duplicate key rather than on an assertion.
     */
    @Test
    @DisplayName("a second capture over the same source restates the rows rather than colliding")
    void aSecondCaptureRestatesTheRows(@TempDir Path tmp) {
        var jooq = new JooqCatalog(TestConfiguration.DEFAULT_JOOQ_PACKAGE);
        try (var store = CapturedStore.ofCatalog(tmp, SDL, jooq)) {
            int before = store.dsl().fetchCount(SQL_ENUM_BINDING);
            assertThat(before).isPositive();
            store.andCatalogGraph("second", SDL, jooq);

            assertThat(store.dsl().fetchCount(SQL_ENUM_BINDING)).isEqualTo(before);
        }
    }

    private static void withCatalog(Path tmp, Consumer<DSLContext> assertions) {
        try (var store = CapturedStore.ofCatalog(tmp, SDL,
                new JooqCatalog(TestConfiguration.DEFAULT_JOOQ_PACKAGE))) {
            assertions.accept(store.dsl());
        }
    }
}
