package no.sikt.graphitron.emit;

import com.palantir.javapoet.TypeSpec;
import no.sikt.graphitron.model.test.FactGraphs;
import no.sikt.graphitron.rewrite.test.compile.EmittedCodeHarness;
import no.sikt.graphitron.rewrite.test.db.TestDatabase;
import no.sikt.graphitron.rewrite.test.jooq.public_.Tables;
import no.sikt.graphitron.rewrite.test.tier.ExecutionTier;
import org.jooq.Condition;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import javax.lang.model.element.Modifier;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Field occurrences' conditions emitted from the store's rows, compiled and run against PostgreSQL:
 * each one selects the films the same filter written by hand by jOOQ selects, for values present,
 * absent, empty and read through input objects. Read over the {@code scenarios/condition-filter}
 * fact document.
 */
@ExecutionTier
class ConditionEmitterExecutionTest {

    @RegisterExtension
    static final FactGraphs.Reader FACTS = FactGraphs.reader();

    private static final String PACKAGE = "conditions";
    private static final no.sikt.graphitron.rewrite.test.jooq.public_.tables.Film FILM = Tables.FILM;

    private static EmittedCodeHarness harness;
    private static Map<String, Method> conditions;
    private static Map<String, ConditionEmitter.Result> results;

    @BeforeAll
    static void emit() throws Exception {
        var rows = FACTS.read("scenarios/condition-filter", graph -> ConditionQuery.read(graph.handle()));
        var type = TypeSpec.classBuilder("Conditions").addModifiers(Modifier.PUBLIC);
        results = new HashMap<>();
        for (var row : rows) {
            var name = row.coordinate().substring(row.coordinate().indexOf('.') + 1);
            var result = ConditionEmitter.emit(row, name);
            results.put(row.coordinate(), result);
            if (result instanceof ConditionEmitter.Result.Emitted emitted) {
                type.addMethod(emitted.method());
            }
        }
        harness = EmittedCodeHarness.compileStrictly(Map.of(PACKAGE + ".Conditions", type.build()));
        conditions = new HashMap<>();
        for (var m : harness.load(PACKAGE + ".Conditions").getDeclaredMethods()) {
            if (!m.isSynthetic()) conditions.put(m.getName(), m);
        }
    }

    @AfterAll
    static void close() {
        if (harness != null) harness.close();
    }

    private static Condition condition(String name, Map<String, Object> args) throws Exception {
        return (Condition) conditions.get(name).invoke(null, FILM, args);
    }

    /** The films the condition selects, by key, in key order. */
    private static List<Integer> films(Condition condition) {
        return TestDatabase.dsl().select(FILM.FILM_ID).from(FILM).where(condition).orderBy(FILM.FILM_ID)
            .fetch(FILM.FILM_ID);
    }

    private static Map<String, Object> args(Object... pairs) {
        var args = new HashMap<String, Object>();
        for (int i = 0; i < pairs.length; i += 2) args.put((String) pairs[i], pairs[i + 1]);
        return args;
    }

    @Test
    @DisplayName("every field with filters is emitted, and the lookup's keys leave only its other filter")
    void everyFieldIsEmitted() {
        assertThat(results).allSatisfy((coordinate, result) ->
            assertThat(result).as(coordinate).isInstanceOf(ConditionEmitter.Result.Emitted.class));
        assertThat(conditions).containsOnlyKeys("filmsByTitle", "filmById", "filmsByLengths", "filmsByFilter",
            "filmsByKey");
    }

    @Test
    @DisplayName("a nullable scalar compares by equality when present and filters nothing when absent")
    void nullableScalar() throws Exception {
        var title = TestDatabase.dsl().select(FILM.TITLE).from(FILM).orderBy(FILM.FILM_ID).limit(1).fetchOne(FILM.TITLE);
        assertThat(films(condition("filmsByTitle", args("title", title)))).isEqualTo(films(FILM.TITLE.eq(title)));
        assertThat(films(condition("filmsByTitle", args()))).isEqualTo(films(DSL.noCondition()));
        assertThat(films(condition("filmsByTitle", args("title", null)))).isEqualTo(films(DSL.noCondition()));
    }

    @Test
    @DisplayName("a non-null scalar is compared unguarded")
    void nonNullScalar() throws Exception {
        assertThat(films(condition("filmById", args("filmId", 3)))).containsExactly(3);
        assertThat(condition("filmById", args("filmId", 3))).hasToString(FILM.FILM_ID.eq(3).toString());
    }

    @Test
    @DisplayName("a list compares by membership, and an absent or empty list filters nothing")
    void list() throws Exception {
        var lengths = List.<Object>of(86, 48);
        assertThat(films(condition("filmsByLengths", args("lengths", lengths))))
            .isEqualTo(films(FILM.LENGTH.in(lengths)));
        assertThat(films(condition("filmsByLengths", args("lengths", List.of())))).isEqualTo(films(DSL.noCondition()));
        assertThat(films(condition("filmsByLengths", args()))).isEqualTo(films(DSL.noCondition()));
    }

    @Test
    @DisplayName("input fields are read through their input objects, and an absent object filters nothing")
    void throughInputObjects() throws Exception {
        var film = TestDatabase.dsl().selectFrom(FILM).orderBy(FILM.FILM_ID).limit(1).fetchOne();
        var filter = args("title", film.getTitle(), "releaseYear", film.getReleaseYear(),
            "inner", args("length", film.getLength()));
        assertThat(films(condition("filmsByFilter", args("filter", filter)))).isEqualTo(films(DSL.and(
            FILM.TITLE.eq(film.getTitle()), FILM.RELEASE_YEAR.eq(film.getReleaseYear()), FILM.LENGTH.eq(film.getLength()))));
        assertThat(films(condition("filmsByFilter", args("filter", args("inner", null)))))
            .isEqualTo(films(DSL.noCondition()));
        assertThat(films(condition("filmsByFilter", args()))).isEqualTo(films(DSL.noCondition()));
    }

    @Test
    @DisplayName("a lookup's key argument writes nothing, its other argument filters")
    void lookupKey() throws Exception {
        assertThat(condition("filmsByKey", args("film_id", List.of(1, 2), "title", "X")))
            .hasToString(FILM.TITLE.eq("X").toString());
    }

    @Test
    @DisplayName("the emitted method reads, types at its column and composes as the emitter documents")
    void emittedShape() {
        var emitted = (ConditionEmitter.Result.Emitted) results.get("Query.filmsByFilter");
        assertThat(emitted.method().toString()).isEqualTo("""
            public static org.jooq.Condition filmsByFilter(
                no.sikt.graphitron.rewrite.test.jooq.public_.tables.Film table,
                java.util.Map<java.lang.String, java.lang.Object> args) {
              java.lang.Short length = org.jooq.impl.DSL.val((args.get("filter") instanceof java.util.Map<?, ?> m1 && m1.get("inner") instanceof java.util.Map<?, ?> m2 ? m2.get("length") : null), table.LENGTH).getValue();
              java.lang.Integer releaseYear = org.jooq.impl.DSL.val((args.get("filter") instanceof java.util.Map<?, ?> m3 ? m3.get("releaseYear") : null), table.RELEASE_YEAR).getValue();
              java.lang.String title = org.jooq.impl.DSL.val((args.get("filter") instanceof java.util.Map<?, ?> m4 ? m4.get("title") : null), table.TITLE).getValue();
              java.util.List<org.jooq.Condition> conditions = new java.util.ArrayList<>();
              if (length != null) conditions.add(table.LENGTH.eq(length));
              if (releaseYear != null) conditions.add(table.RELEASE_YEAR.eq(releaseYear));
              if (title != null) conditions.add(table.TITLE.eq(title));
              return org.jooq.impl.DSL.and(conditions);
            }
            """);
    }
}
