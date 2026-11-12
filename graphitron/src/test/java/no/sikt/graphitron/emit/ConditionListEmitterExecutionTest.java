package no.sikt.graphitron.emit;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.TypeSpec;
import no.sikt.graphitron.model.test.FactGraphs;
import no.sikt.graphitron.rewrite.generators.schema.GraphitronClientExceptionClassGenerator;
import no.sikt.graphitron.rewrite.generators.util.ColumnValuesClassGenerator;
import no.sikt.graphitron.rewrite.generators.util.NodeIdEncoderClassGenerator;
import no.sikt.graphitron.rewrite.test.compile.EmittedCodeHarness;
import no.sikt.graphitron.rewrite.test.db.TestDatabase;
import no.sikt.graphitron.rewrite.test.jooq.public_.Tables;
import no.sikt.graphitron.rewrite.test.tier.ExecutionTier;
import org.jooq.Condition;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import javax.lang.model.element.Modifier;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Filters read through a list of input objects, emitted from the store's rows over the
 * {@code scenarios/condition-list-filter} fact document, compiled and run against PostgreSQL: each
 * selects the rows the same filter written by hand by jOOQ selects, an element matched as a whole row,
 * for lists absent, empty, holding a null element, and elements leaving fields out.
 */
@ExecutionTier
class ConditionListEmitterExecutionTest {

    @RegisterExtension
    static final FactGraphs.Reader FACTS = FactGraphs.reader();

    private static final String PACKAGE = "listconditions";
    private static final no.sikt.graphitron.rewrite.test.jooq.public_.tables.Film FILM = Tables.FILM;
    private static final no.sikt.graphitron.rewrite.test.jooq.public_.tables.FilmActor FILM_ACTOR = Tables.FILM_ACTOR;

    private static final ClassName ENCODER = ClassName.get(PACKAGE + ".util", NodeIdEncoderClassGenerator.CLASS_NAME);
    private static final FillEmitter.Runtime RUNTIME = new FillEmitter.Runtime(
        key -> com.palantir.javapoet.CodeBlock.of("null"),
        coordinate -> com.palantir.javapoet.CodeBlock.of("null"),
        (member, coordinate) -> com.palantir.javapoet.CodeBlock.of("null"),
        new NodeIdDecodeEmitter.Runtime(ENCODER, PACKAGE));

    private static EmittedCodeHarness harness;
    private static Map<String, Method> conditions;
    private static Map<String, ConditionEmitter.Result> results;

    @BeforeAll
    static void emit() throws Exception {
        record Read(List<ConditionQuery.Row> rows, NodeIdDecodeQuery decodes,
                    Map<BuildQuery.Root, BuildQuery.Result> builds) {}
        var read = FACTS.read("scenarios/condition-list-filter",
            graph -> new Read(ConditionQuery.read(graph.handle()), NodeIdDecodeQuery.read(graph.handle()),
                BuildQuery.read(graph.handle())));
        var type = TypeSpec.classBuilder("Conditions").addModifiers(Modifier.PUBLIC);
        results = new HashMap<>();
        for (var row : read.rows()) {
            var name = row.coordinate().substring(row.coordinate().indexOf('.') + 1);
            var result = ConditionEmitter.emit(row, name, read.decodes(), RUNTIME, read.builds());
            results.put(row.coordinate(), result);
            if (result instanceof ConditionEmitter.Result.Emitted emitted) {
                type.addMethod(emitted.method());
                emitted.helpers().forEach(type::addMethod);
            }
        }
        var units = new HashMap<String, TypeSpec>();
        units.put(PACKAGE + ".Conditions", type.build());
        for (TypeSpec spec : NodeIdEncoderClassGenerator.generate(List.of(), PACKAGE, List.of())) {
            units.put(PACKAGE + ".util." + spec.name(), spec);
        }
        for (TypeSpec spec : GraphitronClientExceptionClassGenerator.generate()) {
            units.put(PACKAGE + ".schema." + spec.name(), spec);
        }
        for (TypeSpec spec : ColumnValuesClassGenerator.generate()) {
            units.put(PACKAGE + ".util." + spec.name(), spec);
        }
        harness = EmittedCodeHarness.compileStrictly(units);
        conditions = new HashMap<>();
        for (var m : harness.load(PACKAGE + ".Conditions").getDeclaredMethods()) {
            if (java.lang.reflect.Modifier.isPublic(m.getModifiers())) conditions.put(m.getName(), m);
        }
    }

    @AfterAll
    static void close() {
        if (harness != null) harness.close();
    }

    private static Condition condition(String name, Table<?> table, Map<String, Object> args) throws Exception {
        return (Condition) conditions.get(name).invoke(null, table, args);
    }

    /** The film-actor pairs the condition selects, in key order. */
    private static List<List<Integer>> pairs(Condition condition) {
        return TestDatabase.dsl().select(FILM_ACTOR.ACTOR_ID, FILM_ACTOR.FILM_ID).from(FILM_ACTOR).where(condition)
            .orderBy(FILM_ACTOR.ACTOR_ID, FILM_ACTOR.FILM_ID)
            .fetch(r -> List.of(r.value1(), r.value2()));
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

    /** A list that may hold nulls, as graphql-java hands one over. */
    private static List<Object> list(Object... elements) {
        return new ArrayList<>(Arrays.asList(elements));
    }

    private static Map<String, Object> pair(int actorId, int filmId) {
        return args("actorId", actorId, "filmId", filmId);
    }

    /** Two seeded pairs whose actors and films differ, so independent memberships would admit a third pair. */
    private static List<List<Integer>> twoPairs() {
        var all = TestDatabase.dsl().select(FILM_ACTOR.ACTOR_ID, FILM_ACTOR.FILM_ID).from(FILM_ACTOR)
            .orderBy(FILM_ACTOR.ACTOR_ID, FILM_ACTOR.FILM_ID).fetch(r -> List.of(r.value1(), r.value2()));
        var first = all.getFirst();
        var second = all.stream()
            .filter(p -> !p.get(0).equals(first.get(0)) && !p.get(1).equals(first.get(1))).findFirst().orElseThrow();
        return List.of(first, second);
    }

    @Test
    @DisplayName("every list filter is emitted, and a @lookupKey list writes no filter")
    void everyListIsEmitted() {
        assertThat(results).containsEntry("Query.filmActorsByKey", new ConditionEmitter.Result.Nothing());
        assertThat(results).allSatisfy((coordinate, result) -> {
            if (!coordinate.equals("Query.filmActorsByKey")) {
                assertThat(result).as(coordinate).isInstanceOf(ConditionEmitter.Result.Emitted.class);
            }
        });
    }

    @Test
    @DisplayName("a list nested in a filter matches the rows equal to an element, not to its fields apart")
    void rowsEqualToAnElement() throws Exception {
        var two = twoPairs();
        var a = two.get(0);
        var b = two.get(1);
        var expected = DSL.row(FILM_ACTOR.ACTOR_ID, FILM_ACTOR.FILM_ID)
            .in(List.of(DSL.row(a.get(0), a.get(1)), DSL.row(b.get(0), b.get(1))));
        var selected = pairs(condition("filmActorsByPairs", FILM_ACTOR,
            args("filter", args("pairs", list(pair(a.get(0), a.get(1)), pair(b.get(0), b.get(1)))))));
        assertThat(selected).isEqualTo(pairs(expected)).containsExactlyInAnyOrder(a, b);
        assertThat(pairs(FILM_ACTOR.ACTOR_ID.in(a.get(0), b.get(0)).and(FILM_ACTOR.FILM_ID.in(a.get(1), b.get(1)))))
            .as("independent memberships would admit more").hasSizeGreaterThanOrEqualTo(selected.size());
    }

    @Test
    @DisplayName("the list as the argument itself compares the same way")
    void asTheArgumentItself() throws Exception {
        var a = twoPairs().getFirst();
        assertThat(pairs(condition("filmActorsByPairList", FILM_ACTOR, args("pairs", list(pair(a.get(0), a.get(1)))))))
            .containsExactly(a);
    }

    @Test
    @DisplayName("an absent, null or empty list filters nothing, and a null element matches nothing")
    void absentEmptyAndNullElement() throws Exception {
        var all = pairs(DSL.noCondition());
        assertThat(pairs(condition("filmActorsByPairs", FILM_ACTOR, args()))).isEqualTo(all);
        assertThat(pairs(condition("filmActorsByPairs", FILM_ACTOR, args("filter", args("pairs", null))))).isEqualTo(all);
        assertThat(pairs(condition("filmActorsByPairs", FILM_ACTOR, args("filter", args("pairs", list()))))).isEqualTo(all);
        assertThat(pairs(condition("filmActorsByPairList", FILM_ACTOR, args("pairs", list((Object) null))))).isEmpty();
        var a = twoPairs().getFirst();
        assertThat(pairs(condition("filmActorsByPairList", FILM_ACTOR,
            args("pairs", list(null, pair(a.get(0), a.get(1))))))).containsExactly(a);
    }

    @Test
    @DisplayName("an element leaving a field out places no constraint on its column, nulls included")
    void elementLeavingAFieldOut() throws Exception {
        var expected = FILM.ORIGINAL_LANGUAGE_ID.eq(2);
        assertThat(films(condition("filmsByLanguages", FILM,
            args("languages", list(args("original", args("id", 2)))))))
            .isEqualTo(films(expected)).isNotEmpty();
        assertThat(films(condition("filmsByLanguages", FILM,
            args("languages", list(args("languageId", 1), args("original", args("id", 2)))))))
            .isEqualTo(films(FILM.LANGUAGE_ID.eq(1).or(expected)));
        assertThat(films(condition("filmsByLanguages", FILM,
            args("languages", list(args("languageId", 1, "original", args("id", 2)))))))
            .isEqualTo(films(FILM.LANGUAGE_ID.eq(1).and(expected)));
    }

    @Test
    @DisplayName("an element giving nothing matches every row, a null element none, an empty list filters nothing")
    void elementGivingNothing() throws Exception {
        var all = films(DSL.noCondition());
        assertThat(films(condition("filmsByLanguages", FILM, args("languages", list(args()))))).isEqualTo(all);
        assertThat(films(condition("filmsByLanguages", FILM, args("languages", list(args("original", null))))))
            .isEqualTo(all);
        assertThat(films(condition("filmsByLanguages", FILM, args("languages", list((Object) null))))).isEmpty();
        assertThat(films(condition("filmsByLanguages", FILM, args("languages", list())))).isEqualTo(all);
    }

    @Test
    @DisplayName("a list reached over a path matches where one reached row equals a whole element")
    void reachedOverAPath() throws Exception {
        var actor = Tables.ACTOR;
        var first = TestDatabase.dsl().selectFrom(actor).orderBy(actor.ACTOR_ID).limit(1).fetchOne();
        var other = TestDatabase.dsl().selectFrom(actor).where(actor.LAST_NAME.ne(first.getLastName()))
            .orderBy(actor.ACTOR_ID).limit(1).fetchOne();
        var expected = FILM.FILM_ID.in(DSL.select(FILM_ACTOR.FILM_ID).from(FILM_ACTOR).join(actor)
            .on(actor.ACTOR_ID.eq(FILM_ACTOR.ACTOR_ID))
            .where(actor.FIRST_NAME.eq(first.getFirstName()), actor.LAST_NAME.eq(first.getLastName())));
        assertThat(films(condition("filmsByCastNames", FILM, args("names",
            list(args("firstName", first.getFirstName(), "lastName", first.getLastName()))))))
            .isEqualTo(films(expected)).isNotEmpty();
        var mixed = FILM.FILM_ID.in(DSL.select(FILM_ACTOR.FILM_ID).from(FILM_ACTOR).join(actor)
            .on(actor.ACTOR_ID.eq(FILM_ACTOR.ACTOR_ID))
            .where(actor.FIRST_NAME.eq(first.getFirstName()), actor.LAST_NAME.eq(other.getLastName())));
        assertThat(films(condition("filmsByCastNames", FILM, args("names",
            list(args("firstName", first.getFirstName(), "lastName", other.getLastName()))))))
            .as("names of two different actors").isEqualTo(films(mixed));
    }
}
