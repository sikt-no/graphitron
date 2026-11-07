package no.sikt.graphitron.emit;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.TypeSpec;
import graphql.schema.DataFetchingEnvironment;
import graphql.schema.DataFetchingEnvironmentImpl;
import no.sikt.graphitron.model.read.StoreHandle;
import no.sikt.graphitron.model.test.CapturedStore;
import no.sikt.graphitron.model.test.CatalogInput;
import no.sikt.graphitron.model.test.ClasspathCorpus;
import no.sikt.graphitron.rewrite.test.compile.EmittedCodeHarness;
import no.sikt.graphitron.rewrite.test.db.TestDatabase;
import no.sikt.graphitron.rewrite.test.jooq.public_.Tables;
import no.sikt.graphitron.rewrite.test.jooq.public_.tables.records.FilmActorRecord;
import no.sikt.graphitron.rewrite.test.jooq.public_.tables.records.FilmRecord;
import no.sikt.graphitron.rewrite.test.jooq.public_.tables.records.LanguageRecord;
import no.sikt.graphitron.rewrite.test.tier.ExecutionTier;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.lang.model.element.Modifier;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

import static no.sikt.graphitron.common.configuration.TestConfiguration.testContext;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The emitted lift run against PostgreSQL: the lifted rows stand in the order the call handed its
 * records back, whatever order the database reads in, one per record, and a record that is null or
 * whose row is gone lifts a null in its own slot. A composite key lifts the same way, and one record
 * lifts its row or a null. Under an object, one record per parent lifts one row per parent, and a
 * list per parent scatters back to its parent in the call's order, a parent with no list keeping
 * none.
 */
@ExecutionTier
class LiftEmitterExecutionTest {

    private static final String SERVICES = "no.sikt.graphitron.rewrite.test.services.";
    private static final String PACKAGE = "liftrun";

    static final String SDL = """
        type Film @table(name: "film") {
          title: String
          languageByService: Language
            @service(service: {className: "%1$sFilmService", method: "languageByService"})
          castMembers: [FilmActor!]!
            @service(service: {className: "%1$sFilmService", method: "castMembersByFilm"})
        }
        type Language @table(name: "language") { name: String }
        type FilmActor @table(name: "film_actor") { lastUpdate: String @field(name: "last_update") }
        type Query {
          filmsByIds(ids: [Int!]!): [Film!]!
            @service(service: {className: "%1$sFilmCarrierService", method: "filmsByIds"})
          filmById(id: Int!): Film
            @service(service: {className: "%1$sFilmCarrierService", method: "filmById"})
          filmActorsByIds(ids: [Int!]!): [FilmActor!]!
            @service(service: {className: "%1$sFilmActorCarrierService", method: "filmActorsByIds"})
        }
        """.formatted(SERVICES);

    @TempDir
    static Path tmp;

    private static EmittedCodeHarness harness;
    private static java.util.Map<String, Method> lifts;

    @BeforeAll
    static void emit() throws Exception {
        List<LiftQuery.Row> rows;
        try (var store = CapturedStore.ofCatalogWith(tmp, CapturedStore.GRAPH, SDL,
                CatalogInput.of(testContext()), ClasspathCorpus.entries())) {
            rows = LiftQuery.read(new StoreHandle(store.dsl(), CapturedStore.GRAPH));
        }
        var selection = ClassName.get(PACKAGE, "SelectionOccurrences");
        var host = TypeSpec.classBuilder("Lifts").addModifiers(Modifier.PUBLIC);
        var units = new HashMap<String, TypeSpec>();
        for (var row : rows) {
            var source = ClassName.get(PACKAGE, row.targetType() + "Source");
            units.putIfAbsent(source.canonicalName(), LiftEmitter.sourceTable(source, row.table(), row.key()));
            units.putIfAbsent(PACKAGE + "." + row.targetType(),
                LiftEmitterPipelineTest.projectionStub(row.targetType()));
            host.addMethod(LiftEmitter.lift(row, row.coordinate().replace("Query.", "").replace(".", ""), source,
                ClassName.get(PACKAGE, row.targetType()), selection));
        }
        units.put(PACKAGE + ".Lifts", host.build());
        units.put(PACKAGE + ".SelectionOccurrences", LiftEmitterPipelineTest.selectionStub());
        harness = EmittedCodeHarness.compile(units);
        lifts = Arrays.stream(harness.load(PACKAGE + ".Lifts").getDeclaredMethods())
            .collect(Collectors.toMap(Method::getName, Function.identity()));
    }

    @AfterAll
    static void close() {
        if (harness != null) harness.close();
    }

    private static final DataFetchingEnvironment ENV =
        DataFetchingEnvironmentImpl.newDataFetchingEnvironment().build();

    private static DSLContext dsl() {
        return TestDatabase.dsl();
    }

    private static FilmRecord film(int id) {
        var r = new FilmRecord();
        r.set(Tables.FILM.FILM_ID, id);
        return r;
    }

    @SuppressWarnings("unchecked")
    private static List<Record> liftList(String name, List<? extends Record> records) throws Exception {
        return (List<Record>) lifts.get(name).invoke(null, ENV, dsl(), records);
    }

    @Test
    void theRowsStandInTheCallsOrderWithANullWhereNoRowIs() throws Exception {
        var records = new java.util.ArrayList<FilmRecord>();
        records.add(film(3));
        records.add(null);
        records.add(film(1));
        records.add(film(999999));
        records.add(film(2));
        var lifted = liftList("filmsByIds", records);
        assertThat(lifted).hasSize(5);
        assertThat(lifted.get(0).get(Tables.FILM.FILM_ID.getName())).isEqualTo(3);
        assertThat(lifted.get(1)).isNull();
        assertThat(lifted.get(2).get(Tables.FILM.FILM_ID.getName())).isEqualTo(1);
        assertThat(lifted.get(3)).isNull();
        assertThat(lifted.get(4).get(Tables.FILM.FILM_ID.getName())).isEqualTo(2);
    }

    @Test
    void nothingHandedBackLiftsNothing() throws Exception {
        assertThat(liftList("filmsByIds", List.of())).isEmpty();
    }

    @Test
    void aCompositeKeyLiftsInTheCallsOrder() throws Exception {
        var seeded = dsl().selectFrom(Tables.FILM_ACTOR)
            .orderBy(Tables.FILM_ACTOR.ACTOR_ID, Tables.FILM_ACTOR.FILM_ID).limit(2).fetch();
        assertThat(seeded).hasSize(2);
        List<FilmActorRecord> reversed = List.of(seeded.get(1), seeded.get(0));
        var lifted = liftList("filmActorsByIds", reversed);
        assertThat(lifted).extracting(r -> r.get(Tables.FILM_ACTOR.FILM_ID.getName()))
            .containsExactly(seeded.get(1).getFilmId(), seeded.get(0).getFilmId());
        assertThat(lifted).extracting(r -> r.get(Tables.FILM_ACTOR.ACTOR_ID.getName()))
            .containsExactly(seeded.get(1).getActorId(), seeded.get(0).getActorId());
    }

    @Test
    void oneRecordPerParentLiftsOneRowPerParent() throws Exception {
        var records = new java.util.ArrayList<LanguageRecord>();
        records.add(language(2));
        records.add(null);
        records.add(language(1));
        var lifted = liftList("FilmlanguageByService", records);
        assertThat(lifted).hasSize(3);
        assertThat(lifted.get(0).get(Tables.LANGUAGE.LANGUAGE_ID.getName())).isEqualTo(2);
        assertThat(lifted.get(1)).isNull();
        assertThat(lifted.get(2).get(Tables.LANGUAGE.LANGUAGE_ID.getName())).isEqualTo(1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void aListPerParentScattersBackToItsParentsInTheCallsOrder() throws Exception {
        var seeded = dsl().selectFrom(Tables.FILM_ACTOR)
            .orderBy(Tables.FILM_ACTOR.ACTOR_ID, Tables.FILM_ACTOR.FILM_ID).limit(3).fetch();
        assertThat(seeded).hasSize(3);
        var missing = new FilmActorRecord();
        missing.set(Tables.FILM_ACTOR.ACTOR_ID, 999999);
        missing.set(Tables.FILM_ACTOR.FILM_ID, 999999);
        var perParent = new java.util.ArrayList<List<FilmActorRecord>>();
        perParent.add(List.of(seeded.get(1), seeded.get(0)));
        perParent.add(null);
        perParent.add(List.of());
        perParent.add(java.util.Arrays.asList(seeded.get(2), null, missing));
        var lifted = (List<List<Record>>) lifts.get("FilmcastMembers").invoke(null, ENV, dsl(), perParent);
        assertThat(lifted).hasSize(4);
        assertThat(lifted.get(0)).extracting(r -> r.get(Tables.FILM_ACTOR.ACTOR_ID.getName()))
            .containsExactly(seeded.get(1).getActorId(), seeded.get(0).getActorId());
        assertThat(lifted.get(1)).isNull();
        assertThat(lifted.get(2)).isEmpty();
        assertThat(lifted.get(3)).hasSize(3);
        assertThat(lifted.get(3).get(0).get(Tables.FILM_ACTOR.FILM_ID.getName()))
            .isEqualTo(seeded.get(2).getFilmId());
        assertThat(lifted.get(3).get(1)).isNull();
        assertThat(lifted.get(3).get(2)).isNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void aBatchHandingBackNothingRunsNoStatementAndKeepsItsParents() throws Exception {
        var perParent = new java.util.ArrayList<List<FilmActorRecord>>();
        perParent.add(null);
        perParent.add(List.of());
        var lifted = (List<List<Record>>) lifts.get("FilmcastMembers").invoke(null, ENV, dsl(), perParent);
        assertThat(lifted).hasSize(2);
        assertThat(lifted.get(0)).isNull();
        assertThat(lifted.get(1)).isEmpty();
    }

    private static LanguageRecord language(int id) {
        var r = new LanguageRecord();
        r.set(Tables.LANGUAGE.LANGUAGE_ID, id);
        return r;
    }

    @Test
    void oneRecordLiftsItsRowOrANull() throws Exception {
        var one = lifts.get("filmById");
        assertThat(((Record) one.invoke(null, ENV, dsl(), film(1))).get(Tables.FILM.FILM_ID.getName()))
            .isEqualTo(1);
        assertThat(one.invoke(null, ENV, dsl(), film(999999))).isNull();
        assertThat(one.invoke(null, ENV, dsl(), (Object) null)).isNull();
    }
}
