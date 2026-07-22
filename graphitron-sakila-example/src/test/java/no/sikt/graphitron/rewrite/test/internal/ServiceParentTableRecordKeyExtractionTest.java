package no.sikt.graphitron.rewrite.test.internal;

import graphql.GraphQLContext;
import graphql.schema.DataFetchingEnvironment;
import graphql.schema.DataFetchingEnvironmentImpl;
import graphql.schema.DataFetchingFieldSelectionSet;
import graphql.schema.SelectedField;
import no.sikt.graphitron.generated.schema.GraphitronContext;
import no.sikt.graphitron.generated.types.Film;
import no.sikt.graphitron.rewrite.test.jooq.Tables;
import no.sikt.graphitron.rewrite.test.jooq.tables.records.FilmRecord;
import no.sikt.graphitron.rewrite.test.tier.ExecutionTier;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the discriminator basis for the {@code SourceKey.Wrap.TableRecord} key-extraction fork in
 * {@code GeneratorUtils.buildKeyExtraction}: the SQL parent path builds its SELECT from
 * {@code <Type>.$fields(...)} (an explicit field list), which jOOQ materialises as a generic
 * {@link Record}, never the typed {@code XRecord} subclass. The service parent path, by contrast,
 * hands back a real typed record ({@code selectFrom(FILM)} yields a {@link FilmRecord}). The
 * fetcher's {@code source instanceof FilmRecord} fork therefore separates the two arrival shapes
 * cleanly: the typed branch fires only for service/DML-produced parents, the else branch only for
 * SQL-projected rows carrying the reserved {@code __src_*__} aliases.
 *
 * <p>This invariant has a history of being false: the pre-fix full-row projection did
 * {@code into(Tables.FILM)} and produced typed records, which is exactly the shape the reserved-
 * alias scheme replaced. If {@code $fields} ever regressed to materialising typed records, an
 * SQL-projected parent would misroute to the typed arm (reading null base columns, since the
 * reserved-alias-populated record carries no base-named columns) and this pin would go red — as
 * would the SQL-parent execution test on its asserted values.
 */
@ExecutionTier
class ServiceParentTableRecordKeyExtractionTest {

    static PostgreSQLContainer postgres;
    static DSLContext dsl;

    @BeforeAll
    static void startDatabase() {
        var localUrl = System.getProperty("test.db.url");
        if (localUrl != null) {
            var user = System.getProperty("test.db.username", "postgres");
            var pass = System.getProperty("test.db.password", "postgres");
            dsl = DSL.using(localUrl, user, pass);
        } else {
            postgres = new PostgreSQLContainer("postgres:18-alpine").withInitScript("init.sql");
            postgres.start();
            dsl = DSL.using(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        }
    }

    @AfterAll
    static void stopDatabase() {
        if (postgres != null) postgres.stop();
    }

    /** An empty selection set: jOOQ's {@code $fields} switch loop never fires, so the projection
     *  reduces to the unconditional reserved-full-row append (the shape the TableRecord else arm
     *  reads back). */
    private static final DataFetchingFieldSelectionSet EMPTY_SELECTION =
        new DataFetchingFieldSelectionSet() {
            @Override public boolean contains(String fieldGlobPattern) { return false; }
            @Override public boolean containsAnyOf(String first, String... rest) { return false; }
            @Override public boolean containsAllOf(String first, String... rest) { return false; }
            @Override public List<SelectedField> getFields() { return List.of(); }
            @Override public List<SelectedField> getImmediateFields() { return List.of(); }
            @Override public List<SelectedField> getFields(String glob, String... rest) { return List.of(); }
            @Override public Map<String, List<SelectedField>> getFieldsGroupedByResultKey() { return Map.of(); }
            @Override public Map<String, List<SelectedField>> getFieldsGroupedByResultKey(String glob, String... rest) { return Map.of(); }
        };

    @Test
    void dollarFieldsSelect_materialisesGenericRecord_notTypedFilmRecord() {
        DataFetchingEnvironment env = DataFetchingEnvironmentImpl.newDataFetchingEnvironment()
            .graphQLContext(GraphQLContext.newContext()
                .of(DSLContext.class, dsl,
                    GraphitronContext.class, GraphitronContext.GraphitronContextImpl.INSTANCE)
                .build())
            .selectionSet(EMPTY_SELECTION)
            .build();

        // The exact field list the SQL parent path selects (Film.$fields carries the reserved
        // full-row projection because titleTitlecase is a Wrap.TableRecord @service child).
        List<Field<?>> fields = Film.$fields(EMPTY_SELECTION, Tables.FILM, env);
        Record row = dsl.select(fields).from(Tables.FILM).limit(1).fetchOne();

        assertThat(row)
            .as("a $fields-built SELECT returns at least one FILM row")
            .isNotNull();
        assertThat(row)
            .as("a $fields-built SELECT materialises a generic Record — the discriminator "
                + "`source instanceof FilmRecord` fork routes it to the reserved-alias else arm")
            .isNotInstanceOf(FilmRecord.class);
    }

    @Test
    void selectFrom_materialisesTypedFilmRecord_theServiceParentShape() {
        // The complementary shape the discriminator's typed arm fires for: a bare selectFrom(FILM)
        // (what SampleQueryService.filmsByService does) yields a typed FilmRecord. Together with the
        // test above this pins both sides of the `instanceof` fork.
        Record row = dsl.selectFrom(Tables.FILM).limit(1).fetchOne();
        assertThat(row)
            .as("selectFrom(FILM) materialises the typed FilmRecord — the service parent shape")
            .isInstanceOf(FilmRecord.class);
    }
}
