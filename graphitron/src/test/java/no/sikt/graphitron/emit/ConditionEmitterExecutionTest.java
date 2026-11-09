package no.sikt.graphitron.emit;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.TypeSpec;
import no.sikt.graphitron.rewrite.TestSchemaHelper;
import no.sikt.graphitron.rewrite.generators.schema.GraphitronClientExceptionClassGenerator;
import no.sikt.graphitron.rewrite.generators.util.ColumnValuesClassGenerator;
import no.sikt.graphitron.rewrite.generators.util.NodeIdEncoderClassGenerator;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    private static final ClassName ENCODER = ClassName.get(PACKAGE + ".util", NodeIdEncoderClassGenerator.CLASS_NAME);
    private static final NodeIdDecodeEmitter.Runtime RUNTIME = new NodeIdDecodeEmitter.Runtime(ENCODER, PACKAGE);

    private static EmittedCodeHarness harness;
    private static Method encode;
    private static Map<String, Method> conditions;
    private static Map<String, ConditionEmitter.Result> results;

    @BeforeAll
    static void emit() throws Exception {
        record Read(List<ConditionQuery.Row> rows, NodeIdDecodeQuery decodes) {}
        var read = FACTS.read("scenarios/condition-filter",
            graph -> new Read(ConditionQuery.read(graph.handle()), NodeIdDecodeQuery.read(graph.handle())));
        var rows = read.rows();
        var type = TypeSpec.classBuilder("Conditions").addModifiers(Modifier.PUBLIC);
        results = new HashMap<>();
        for (var row : rows) {
            var name = row.coordinate().substring(row.coordinate().indexOf('.') + 1);
            var result = ConditionEmitter.emit(row, name, read.decodes(), RUNTIME);
            results.put(row.coordinate(), result);
            if (result instanceof ConditionEmitter.Result.Emitted emitted) {
                type.addMethod(emitted.method());
                emitted.helpers().forEach(type::addMethod);
            }
        }
        var units = new HashMap<String, TypeSpec>();
        units.put(PACKAGE + ".Conditions", type.build());
        var schema = TestSchemaHelper.buildSchema("type Query { x: Int }");
        for (TypeSpec spec : NodeIdEncoderClassGenerator.generate(schema, PACKAGE, List.of())) {
            units.put(PACKAGE + ".util." + spec.name(), spec);
        }
        for (TypeSpec spec : GraphitronClientExceptionClassGenerator.generate()) {
            units.put(PACKAGE + ".schema." + spec.name(), spec);
        }
        for (TypeSpec spec : ColumnValuesClassGenerator.generate()) {
            units.put(PACKAGE + ".util." + spec.name(), spec);
        }
        harness = EmittedCodeHarness.compileStrictly(units);
        encode = harness.load(ENCODER.reflectionName()).getMethod("encode", String.class, Object[].class);
        conditions = new HashMap<>();
        for (var m : harness.load(PACKAGE + ".Conditions").getDeclaredMethods()) {
            if (java.lang.reflect.Modifier.isPublic(m.getModifiers())) conditions.put(m.getName(), m);
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
            "filmsByKey", "customersByCityId", "filmsByCast", "filmsByBridgedActorFirstName", "filmByNodeId",
            "filmsByNodeIds", "filmActorsByNodeIds", "inventoriesByFilm", "filmsByRating");
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
    @DisplayName("a value whose path steps over a key filters by a correlated EXISTS, converted at the reached column")
    void reachedOverOneKey() throws Exception {
        var customer = Tables.CUSTOMER;
        var address = Tables.ADDRESS;
        var city = TestDatabase.dsl().select(address.CITY_ID).from(customer).join(address)
            .on(address.ADDRESS_ID.eq(customer.ADDRESS_ID)).orderBy(customer.CUSTOMER_ID).limit(1).fetchOne(address.CITY_ID);
        // An ID arrives as a string, and binds as the integer column it lands on.
        var condition = (Condition) conditions.get("customersByCityId").invoke(null, customer, args("cityId", String.valueOf(city)));
        var expected = customer.ADDRESS_ID.in(DSL.select(address.ADDRESS_ID).from(address).where(address.CITY_ID.eq(city)));
        assertThat(customers(condition)).isNotEmpty().isEqualTo(customers(expected));
        assertThat(customers((Condition) conditions.get("customersByCityId").invoke(null, customer, args())))
            .isEqualTo(customers(DSL.noCondition()));
    }

    @Test
    @DisplayName("a path over two keys, read through an input object, comes back once per film however many actors match")
    void reachedOverTwoKeys() throws Exception {
        var actorName = TestDatabase.dsl().select(Tables.ACTOR.FIRST_NAME).from(Tables.ACTOR).orderBy(Tables.ACTOR.ACTOR_ID)
            .limit(1).fetchOne(Tables.ACTOR.FIRST_NAME);
        var expected = FILM.FILM_ID.in(DSL.select(Tables.FILM_ACTOR.FILM_ID).from(Tables.FILM_ACTOR).join(Tables.ACTOR)
            .on(Tables.ACTOR.ACTOR_ID.eq(Tables.FILM_ACTOR.ACTOR_ID)).where(Tables.ACTOR.FIRST_NAME.eq(actorName)));
        assertThat(films(condition("filmsByCast", args("cast", args("firstName", actorName))))).isNotEmpty()
            .isEqualTo(films(expected));
        assertThat(films(condition("filmsByBridgedActorFirstName", args("firstName", actorName))))
            .isEqualTo(films(expected));
    }

    @Test
    @DisplayName("a reached condition applied inside an aliased statement aliases its steps under that alias")
    void reachedUnderAnAlias() throws Exception {
        var outer = FILM.as("outer_film");
        var condition = (Condition) conditions.get("filmsByCast").invoke(null, outer, args("cast", args("firstName", "X")));
        assertThat(condition.toString()).contains("\"outer_film_firstName_0\"").contains("\"outer_film_firstName_1\"");
    }

    @Test
    @DisplayName("an enum compares as the column's constant of its name, a list of them too, where name and literal differ")
    void enums() throws Exception {
        var pg13 = no.sikt.graphitron.rewrite.test.jooq.public_.enums.MpaaRating.PG_13;
        assertThat(films(condition("filmsByRating", args("rating", "PG_13")))).isNotEmpty()
            .isEqualTo(films(FILM.RATING.eq(pg13)));
        assertThat(films(condition("filmsByRating", args("ratings", List.of("PG_13", "G")))))
            .isEqualTo(films(FILM.RATING.in(pg13, no.sikt.graphitron.rewrite.test.jooq.public_.enums.MpaaRating.G)));
    }

    private static String id(String typeId, Object... key) throws Exception {
        return (String) encode.invoke(null, typeId, key);
    }

    @Test
    @DisplayName("a node id compares its key on its own table, and an id of another type is the client's error")
    void nodeId() throws Exception {
        assertThat(films(condition("filmByNodeId", args("id", id("F", 3))))).containsExactly(3);
        assertThatThrownBy(() -> condition("filmByNodeId", args("id", id("FilmActor", 1, 1))))
            .hasRootCauseMessage("Invalid node id \"" + id("FilmActor", 1, 1)
                + "\" for this argument: decodes to type \"FilmActor\", expected a Film id");
    }

    @Test
    @DisplayName("a list of node ids compares by membership, and an empty one filters nothing")
    void nodeIds() throws Exception {
        assertThat(films(condition("filmsByNodeIds", args("ids", List.of(id("F", 1), id("F", 2)))))).containsExactly(1, 2);
        assertThat(films(condition("filmsByNodeIds", args("ids", List.of())))).isEqualTo(films(DSL.noCondition()));
    }

    @Test
    @DisplayName("a node id of a composite key compares as a row value")
    void compositeNodeIds() throws Exception {
        var filmActor = Tables.FILM_ACTOR;
        var pair = TestDatabase.dsl().selectFrom(filmActor).orderBy(filmActor.ACTOR_ID, filmActor.FILM_ID).limit(1).fetchOne();
        var condition = (Condition) conditions.get("filmActorsByNodeIds").invoke(null, filmActor,
            args("ids", List.of(id("FilmActor", pair.getActorId(), pair.getFilmId()))));
        assertThat(TestDatabase.dsl().selectFrom(filmActor).where(condition).fetch())
            .singleElement().satisfies(r -> assertThat(r.getFilmId()).isEqualTo(pair.getFilmId()));
    }

    @Test
    @DisplayName("a node id on a table referencing the node compares that table's foreign key")
    void nodeIdThroughForeignKey() throws Exception {
        var inventory = Tables.INVENTORY;
        var condition = (Condition) conditions.get("inventoriesByFilm").invoke(null, inventory, args("filmId", id("F", 1)));
        assertThat(TestDatabase.dsl().select(inventory.INVENTORY_ID).from(inventory).where(condition).orderBy(inventory.INVENTORY_ID)
                .fetch(inventory.INVENTORY_ID))
            .isNotEmpty()
            .isEqualTo(TestDatabase.dsl().select(inventory.INVENTORY_ID).from(inventory).where(inventory.FILM_ID.eq(1))
                .orderBy(inventory.INVENTORY_ID).fetch(inventory.INVENTORY_ID));
    }

    private static List<Integer> customers(Condition condition) {
        var customer = Tables.CUSTOMER;
        return TestDatabase.dsl().select(customer.CUSTOMER_ID).from(customer).where(condition).orderBy(customer.CUSTOMER_ID)
            .fetch(customer.CUSTOMER_ID);
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
