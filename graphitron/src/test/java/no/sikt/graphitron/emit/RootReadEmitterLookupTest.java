package no.sikt.graphitron.emit;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.JavaFile;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeSpec;
import com.palantir.javapoet.WildcardTypeName;
import graphql.schema.DataFetchingEnvironment;
import graphql.schema.DataFetchingEnvironmentImpl;
import no.sikt.graphitron.model.test.FactGraphs;
import no.sikt.graphitron.rewrite.generators.schema.GraphitronClientExceptionClassGenerator;
import no.sikt.graphitron.rewrite.generators.util.ColumnValuesClassGenerator;
import no.sikt.graphitron.rewrite.test.compile.EmittedCodeHarness;
import no.sikt.graphitron.rewrite.test.db.TestDatabase;
import no.sikt.graphitron.rewrite.test.jooq.public_.Tables;
import no.sikt.graphitron.rewrite.test.tier.ExecutionTier;
import org.jooq.Condition;
import org.jooq.Record;
import org.jooq.Record1;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import javax.lang.model.element.Modifier;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link RootReadEmitter}'s lookup bodies, emitted from the store's facts, compiled as a consumer compiles
 * them and run against PostgreSQL: a scalar key's list answering one slot per key in key order, a miss
 * holding {@code null} and a repeated key answered at each position, a key several rows match answering
 * one of them, no keys answering an empty list, one key answering its record or {@code null}, an input
 * object's column beside a node id its foreign key carries back, an absent id matching nothing and a
 * malformed one failing the field, and a node id of the lookup's own table. The projection units, the
 * selection scaffold, the node-id encoder and the condition methods are stubbed, so the key rows and the
 * join are what is checked. Read over the {@code scenarios/root-lookup} fact document.
 */
@ExecutionTier
class RootReadEmitterLookupTest {

    @RegisterExtension
    static final FactGraphs.Reader FACTS = FactGraphs.reader();

    private static final String PACKAGE = "rootlookup";

    private static final RootReadEmitter.Names NAMES = new RootReadEmitter.Names() {
        @Override
        public String launcherMethod(String parentType, String fieldName) {
            return fieldName;
        }

        @Override
        public String orderByHelper(String parentType, String fieldName) {
            return fieldName + "OrderBy";
        }

        @Override
        public ClassName conditionsClass(String parentType) {
            return ClassName.get(PACKAGE, "Conditions");
        }

        @Override
        public String conditionMethod(String parentType, String fieldName) {
            return fieldName + "Condition";
        }

        @Override
        public ClassName projectionUnit(String typeName) {
            return ClassName.get(PACKAGE, typeName);
        }

        @Override
        public ClassName selectionOccurrences() {
            return ClassName.get(PACKAGE, "SelectionOccurrences");
        }

        @Override
        public ClassName connectionHelper() {
            return ClassName.get(PACKAGE + ".util", "ConnectionHelper");
        }

        @Override
        public ClassName orderByResult() {
            return ClassName.get(PACKAGE + ".util", "OrderByResult");
        }

        @Override
        public String outputPackage() {
            return PACKAGE;
        }
    };

    private static final List<String> LOOKUPS = List.of("filmsByKey", "filmsByLanguage", "filmByKey",
        "customersByKey", "storesById");

    private static EmittedCodeHarness harness;
    private static Map<String, Method> bodies;

    @BeforeAll
    static void emit() throws Exception {
        var roots = FACTS.read("scenarios/root-lookup", graph -> RootQuery.read(graph.handle()));
        var host = TypeSpec.classBuilder("Bodies").addModifiers(Modifier.PUBLIC);
        for (var field : LOOKUPS) {
            var root = (RootQuery.Emitted) roots.get("Query." + field);
            host.addMethod(RootReadEmitter.render(root, NAMES, null));
        }

        var units = new ArrayList<JavaFile>();
        units.add(JavaFile.builder(PACKAGE, host.build()).build());
        units.add(JavaFile.builder(PACKAGE, projectionStub("Film", "Film", "FILM_ID", "TITLE")).build());
        units.add(JavaFile.builder(PACKAGE, projectionStub("Customer", "Customer", "CUSTOMER_ID", "EMAIL")).build());
        units.add(JavaFile.builder(PACKAGE, projectionStub("Store", "Store", "STORE_ID")).build());
        units.add(JavaFile.builder(PACKAGE, selectionStub()).build());
        units.add(JavaFile.builder(PACKAGE, conditionsStub()).build());
        units.add(JavaFile.builder(PACKAGE + ".util", encoderStub()).build());
        for (var spec : ColumnValuesClassGenerator.generate()) {
            units.add(JavaFile.builder(PACKAGE + ".util", spec).build());
        }
        for (var spec : GraphitronClientExceptionClassGenerator.generate()) {
            units.add(JavaFile.builder(PACKAGE + ".schema", spec).build());
        }
        var dir = Files.createTempDirectory("rootlookup");
        var files = new ArrayList<Path>();
        for (var unit : units) {
            files.add(unit.writeToPath(dir));
        }
        harness = EmittedCodeHarness.compileFilesStrictly(files);
        bodies = new HashMap<>();
        for (var m : harness.load(PACKAGE + ".Bodies").getDeclaredMethods()) {
            m.setAccessible(true);
            bodies.put(m.getName(), m);
        }
    }

    @AfterAll
    static void close() {
        if (harness != null) harness.close();
    }

    /** A type's projection: the given columns of its table, whatever the selection. */
    private static TypeSpec projectionStub(String typeName, String tableClass, String... columns) {
        var fieldList = ParameterizedTypeName.get(ClassName.get(List.class),
            ParameterizedTypeName.get(ClassName.get("org.jooq", "Field"), WildcardTypeName.subtypeOf(Object.class)));
        var select = String.join(", ", Arrays.stream(columns).map(c -> "table." + c).toList());
        return TypeSpec.classBuilder(typeName).addModifiers(Modifier.PUBLIC)
            .addMethod(MethodSpec.methodBuilder("$project").addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .returns(fieldList)
                .addParameter(Object.class, "occurrences")
                .addParameter(ClassName.get("no.sikt.graphitron.rewrite.test.jooq.public_.tables", tableClass), "table")
                .addParameter(DataFetchingEnvironment.class, "env")
                .addStatement("return $T.of($L)", List.class, select)
                .build())
            .build();
    }

    /** The selection scaffold, its groupings unread by the stubbed projections. */
    private static TypeSpec selectionStub() {
        var selection = ClassName.get("graphql.schema", "DataFetchingFieldSelectionSet");
        return TypeSpec.classBuilder("SelectionOccurrences").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodSpec.methodBuilder("ownFields").addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .returns(Object.class).addParameter(selection, "selection").addStatement("return null").build())
            .build();
    }

    /**
     * Each lookup's condition method, comparing nothing: a lookup's key arguments are its keys, not its
     * filter, so the condition the generator renders for it is {@code noCondition()}.
     */
    private static TypeSpec conditionsStub() {
        var type = TypeSpec.classBuilder("Conditions").addModifiers(Modifier.PUBLIC);
        var args = ParameterizedTypeName.get(Map.class, String.class, Object.class);
        var tables = Map.of("filmsByKey", "Film", "filmsByLanguage", "Film", "filmByKey", "Film",
            "customersByKey", "Customer", "storesById", "Store");
        for (var field : LOOKUPS) {
            type.addMethod(MethodSpec.methodBuilder(NAMES.conditionMethod("Query", field))
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .returns(Condition.class)
                .addParameter(ClassName.get("no.sikt.graphitron.rewrite.test.jooq.public_.tables", tables.get(field)),
                    "table")
                .addParameter(args, "args")
                .addStatement("return $T.noCondition()", DSL.class)
                .build());
        }
        return type.build();
    }

    /**
     * The node-id encoder, a store's id spelled {@code Store:<store_id>}: its decode and the type prefix the
     * refusal peeks.
     */
    private static TypeSpec encoderStub() {
        var record = ParameterizedTypeName.get(ClassName.get(Record1.class), ClassName.get(Integer.class));
        return TypeSpec.classBuilder("NodeIdEncoder").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodSpec.methodBuilder(NodeIdHelperNames.decode("Store"))
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC).returns(record).addParameter(String.class, "id")
                .beginControlFlow("if (!id.startsWith($S))", "Store:")
                .addStatement("return null")
                .endControlFlow()
                .beginControlFlow("try")
                .addStatement("$T key = $T.using($T.DEFAULT).newRecord($T.STORE.STORE_ID)", record, DSL.class,
                    SQLDialect.class, Tables.class)
                .addStatement("key.value1($T.parseInt(id.substring(6)))", Integer.class)
                .addStatement("return key")
                .nextControlFlow("catch ($T e)", NumberFormatException.class)
                .addStatement("return null")
                .endControlFlow()
                .build())
            .addMethod(MethodSpec.methodBuilder("peekTypeId").addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .returns(String.class).addParameter(String.class, "id")
                .addStatement("int colon = id == null ? -1 : id.indexOf(':')")
                .addStatement("return colon <= 0 ? null : id.substring(0, colon)")
                .build())
            .build();
    }

    private static DataFetchingEnvironment env(Map<String, Object> arguments) {
        return DataFetchingEnvironmentImpl.newDataFetchingEnvironment().arguments(arguments).build();
    }

    private static Object lookup(String body, Map<String, Object> arguments) throws Exception {
        try {
            return bodies.get(body).invoke(null, TestDatabase.dsl(), env(arguments));
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof Exception cause) throw cause;
            throw e;
        }
    }

    /** Each slot's value of {@code column}, {@code null} for an empty slot. */
    private static List<Object> slots(Object answer, String column) {
        var out = new ArrayList<Object>();
        for (var slot : (List<?>) answer) {
            out.add(slot == null ? null : ((Record) slot).get(column));
        }
        return out;
    }

    private static Map<String, Object> arguments(String name, Object value) {
        var arguments = new HashMap<String, Object>();
        arguments.put(name, value);
        return arguments;
    }

    @Test
    void aScalarKeysListAnswersOneSlotPerKeyInKeyOrder() throws Exception {
        var answer = lookup("filmsByKey", Map.of("film_id", Arrays.asList(3, 999999, 1, 3)));
        assertThat(slots(answer, "film_id")).containsExactly(3, null, 1, 3);
    }

    @Test
    void noKeysAnswerAnEmptyList() throws Exception {
        assertThat((List<?>) lookup("filmsByKey", Map.of("film_id", List.of()))).isEmpty();
        assertThat((List<?>) lookup("filmsByKey", arguments("film_id", null))).isEmpty();
    }

    @Test
    void aKeySeveralRowsMatchAnswersOneOfThemAtItsPosition() throws Exception {
        var language = TestDatabase.dsl().select(Tables.FILM.LANGUAGE_ID).from(Tables.FILM)
            .groupBy(Tables.FILM.LANGUAGE_ID).having(DSL.count().gt(1)).limit(1).fetchOne(Tables.FILM.LANGUAGE_ID);
        var answer = (List<?>) lookup("filmsByLanguage", Map.of("language_id", Arrays.asList(language, 999999)));
        assertThat(answer).hasSize(2);
        assertThat(answer.get(1)).isNull();
        var film = ((Record) answer.get(0)).get("film_id", Integer.class);
        assertThat(TestDatabase.dsl().fetchValue(DSL.select(Tables.FILM.LANGUAGE_ID).from(Tables.FILM)
            .where(Tables.FILM.FILM_ID.eq(film)))).isEqualTo(language);
    }

    @Test
    void oneKeyAnswersItsRecordOrNull() throws Exception {
        assertThat(((Record) lookup("filmByKey", Map.of("film_id", 2))).get("film_id")).isEqualTo(2);
        assertThat(lookup("filmByKey", Map.of("film_id", 999999))).isNull();
    }

    @Test
    void anInputObjectsColumnAndCarriedNodeIdBothNarrowTheKey() throws Exception {
        var customer = TestDatabase.dsl().selectFrom(Tables.CUSTOMER).where(Tables.CUSTOMER.EMAIL.isNotNull())
            .orderBy(Tables.CUSTOMER.CUSTOMER_ID).limit(1).fetchOne();
        var email = customer.get(Tables.CUSTOMER.EMAIL);
        var store = customer.get(Tables.CUSTOMER.STORE_ID);
        var otherStore = TestDatabase.dsl().select(Tables.STORE.STORE_ID).from(Tables.STORE)
            .where(Tables.STORE.STORE_ID.ne(store)).limit(1).fetchOne(Tables.STORE.STORE_ID);
        var absent = new HashMap<String, Object>();
        absent.put("email", email);
        var answer = lookup("customersByKey", Map.of("key", List.of(
            Map.of("email", email, "store", "Store:" + otherStore),
            Map.of("email", email, "store", "Store:" + store),
            absent)));
        assertThat(slots(answer, "customer_id")).containsExactly(null, customer.get(Tables.CUSTOMER.CUSTOMER_ID), null);
    }

    @Test
    void aMalformedNodeIdFailsTheField() {
        assertThatThrownBy(() -> lookup("customersByKey",
            Map.of("key", List.of(Map.of("email", "x", "store", "not-a-node-id")))))
            .hasMessageContaining("Invalid node id \"not-a-node-id\"")
            .hasMessageContaining("not a valid Store id");
        assertThatThrownBy(() -> lookup("storesById", Map.of("id", List.of("Film:1"))))
            .hasMessageContaining("decodes to type \"Film\", expected a Store id");
    }

    @Test
    void aNodeIdOfTheLookupsOwnTableAnswersEachStore() throws Exception {
        var answer = lookup("storesById", Map.of("id", List.of("Store:2", "Store:999999", "Store:1")));
        assertThat(slots(answer, "store_id")).containsExactly(2, null, 1);
    }
}
