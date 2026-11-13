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
import org.jooq.Field;
import org.jooq.Table;
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

import static no.sikt.graphitron.model.Tables.GRAPHITRON_NODE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A node id naming one node type, filtering a field over a union, emitted from the store and run
 * against PostgreSQL on every table the field's rows are on: each participant decodes the id alike
 * and selects what the same filter written by hand by jOOQ selects there, over a foreign key of its
 * own or over the path it states. An id each participant decodes as a node of its own is not emitted
 * yet. Read over the {@code scenarios/condition-participant} fact document.
 */
@ExecutionTier
class ConditionParticipantExecutionTest {

    @RegisterExtension
    static final FactGraphs.Reader FACTS = FactGraphs.reader();

    private static final String PACKAGE = "participants";
    private static final ClassName ENCODER = ClassName.get(PACKAGE + ".util", NodeIdEncoderClassGenerator.CLASS_NAME);
    private static final FillEmitter.Runtime RUNTIME = new FillEmitter.Runtime(
        key -> com.palantir.javapoet.CodeBlock.of("env.getGraphQlContext().get($S)", key),
        coordinate -> com.palantir.javapoet.CodeBlock.of("null"),
        (member, coordinate) -> com.palantir.javapoet.CodeBlock.of("null"),
        new NodeIdDecodeEmitter.Runtime(ENCODER, PACKAGE));

    private static EmittedCodeHarness harness;
    private static Method encode;
    private static Map<String, Method> conditions;
    private static Map<String, ConditionEmitter.Result> results;
    private static Map<String, String> typeIds;

    @BeforeAll
    static void emit() throws Exception {
        record Read(List<ConditionQuery.Row> rows, NodeIdDecodeQuery decodes,
                    Map<BuildQuery.Root, BuildQuery.Result> builds, Map<String, String> typeIds) {}
        var read = FACTS.read("scenarios/condition-participant",
            graph -> new Read(ConditionQuery.read(graph.handle()), NodeIdDecodeQuery.read(graph.handle()),
                BuildQuery.read(graph.handle()),
                graph.handle().dsl().select(GRAPHITRON_NODE.TYPE_NAME, GRAPHITRON_NODE.TYPE_ID)
                    .from(GRAPHITRON_NODE).where(GRAPHITRON_NODE.GRAPH_NAME.eq(graph.handle().graphName()))
                    .fetchMap(GRAPHITRON_NODE.TYPE_NAME, GRAPHITRON_NODE.TYPE_ID)));
        typeIds = read.typeIds();
        var type = TypeSpec.classBuilder("Conditions").addModifiers(Modifier.PUBLIC);
        results = new HashMap<>();
        for (var row : read.rows()) {
            var name = row.coordinate().substring(row.coordinate().indexOf('.') + 1) + "_" + row.table().sqlName();
            var result = ConditionEmitter.emit(row, name, read.decodes(), RUNTIME, read.builds());
            results.put(name, result);
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

    private static Condition condition(String name, Table<?> table, Map<String, Object> args) throws Exception {
        return (Condition) conditions.get(name).invoke(null, table, args);
    }

    private static String id(String node, Object... key) throws Exception {
        return (String) encode.invoke(null, typeIds.get(node), key);
    }

    /** The keys of the rows of {@code table} that {@code condition} selects, in key order. */
    private static List<Integer> keys(Table<?> table, Field<Integer> key, Condition condition) {
        return TestDatabase.dsl().select(key).from(table).where(condition).orderBy(key).fetch(key);
    }

    @Test
    @DisplayName("an id of one node type is emitted on every table the field's rows are on; one each participant decodes as its own is not yet")
    void oneNodeTypeIsEmittedOnEveryTable() {
        assertThat(results).containsOnlyKeys("occupantById_customer", "occupantById_staff",
            "occupantsByAddress_customer", "occupantsByAddress_staff",
            "stockByLanguage_film", "stockByLanguage_inventory");
        assertThat(results).allSatisfy((name, result) -> {
            if (name.startsWith("occupantById")) {
                assertThat(result).as(name).isEqualTo(new ConditionEmitter.Result.NotYet(
                    ConditionEmitter.Reason.NODE_ID_PARTICIPANT, "Query.occupantById(id:)"));
            } else {
                assertThat(result).as(name).isInstanceOf(ConditionEmitter.Result.Emitted.class);
            }
        });
    }

    @Test
    @DisplayName("an address id filters each participant over the foreign key it holds to address")
    void anAddressIdFiltersEachParticipant() throws Exception {
        var customer = Tables.CUSTOMER;
        var staff = Tables.STAFF;
        var addresses = TestDatabase.dsl().select(customer.ADDRESS_ID).from(customer).orderBy(customer.CUSTOMER_ID)
            .limit(2).fetch(customer.ADDRESS_ID);
        var staffAddress = TestDatabase.dsl().select(staff.ADDRESS_ID).from(staff).orderBy(staff.STAFF_ID)
            .limit(1).fetchOne(staff.ADDRESS_ID);
        var ids = List.of(id("Address", addresses.get(0)), id("Address", addresses.get(1)), id("Address", staffAddress));
        var expectedCustomers = keys(customer, customer.CUSTOMER_ID,
            customer.ADDRESS_ID.in(addresses.get(0), addresses.get(1), staffAddress));
        var expectedStaff = keys(staff, staff.STAFF_ID,
            staff.ADDRESS_ID.in(addresses.get(0), addresses.get(1), staffAddress));

        assertThat(keys(customer, customer.CUSTOMER_ID,
                condition("occupantsByAddress_customer", customer, Map.of("addressId", ids))))
            .isEqualTo(expectedCustomers).isNotEmpty();
        assertThat(keys(staff, staff.STAFF_ID,
                condition("occupantsByAddress_staff", staff, Map.of("addressId", ids))))
            .isEqualTo(expectedStaff).isNotEmpty();
    }

    @Test
    @DisplayName("a language id filters each participant over the path it states, on its own table or through the film it holds")
    void aLanguageIdFiltersEachParticipantOverItsPath() throws Exception {
        var film = Tables.FILM;
        var inventory = Tables.INVENTORY;
        var language = TestDatabase.dsl().select(film.LANGUAGE_ID).from(film).orderBy(film.FILM_ID)
            .limit(1).fetchOne(film.LANGUAGE_ID);
        var args = Map.<String, Object>of("filter", Map.of("languageId", id("LanguageNode", language)));

        assertThat(keys(film, film.FILM_ID, condition("stockByLanguage_film", film, args)))
            .isEqualTo(keys(film, film.FILM_ID, film.LANGUAGE_ID.eq(language))).isNotEmpty();
        var stockedFilms = DSL.select(film.FILM_ID).from(film).where(film.LANGUAGE_ID.eq(language));
        assertThat(keys(inventory, inventory.INVENTORY_ID, condition("stockByLanguage_inventory", inventory, args)))
            .isEqualTo(keys(inventory, inventory.INVENTORY_ID, inventory.FILM_ID.in(stockedFilms))).isNotEmpty();
    }
}
