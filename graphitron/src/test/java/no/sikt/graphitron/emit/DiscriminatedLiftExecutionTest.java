package no.sikt.graphitron.emit;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.TypeSpec;
import com.palantir.javapoet.WildcardTypeName;
import graphql.schema.DataFetchingEnvironment;
import graphql.schema.DataFetchingEnvironmentImpl;
import graphql.schema.SelectedField;
import no.sikt.graphitron.model.test.FactGraphs;
import no.sikt.graphitron.rewrite.test.compile.EmittedCodeHarness;
import no.sikt.graphitron.rewrite.test.db.TestDatabase;
import no.sikt.graphitron.rewrite.test.jooq.public_.Tables;
import no.sikt.graphitron.rewrite.test.jooq.public_.tables.records.FanBaseRecord;
import no.sikt.graphitron.rewrite.test.tier.ExecutionTier;
import org.jooq.Record;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import javax.lang.model.element.Modifier;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The lift of a @service's records onto a single-table discriminated interface, emitted from the
 * store's facts and run against PostgreSQL, its participants' projection units stubbed so the fold
 * itself is what is checked: each participant's selection is restricted keeping the names whose
 * terms land under an alias no participant qualifies, a term every participant projects lands in
 * the select once, a participant's own terms ride beside it, a scalar one step off the shared table
 * is read for that participant's rows alone and only where the selection asks for it, and the rows
 * stand in the call's order, a record whose row is gone lifting a null. Read over the
 * {@code scenarios/discriminated-service-lift} fact document.
 */
@ExecutionTier
class DiscriminatedLiftExecutionTest {

    @RegisterExtension
    static final FactGraphs.Reader FACTS = FactGraphs.reader();

    private static final String PACKAGE = "discriminatedlift";
    private static final String COORDINATE = "Query.fanItemsByService";

    private static DiscriminatedQuery.Row discriminated;
    private static EmittedCodeHarness harness;
    private static Method lift;
    private static Class<?> occurrences;

    @BeforeAll
    static void emit() throws Exception {
        var row = FACTS.read("scenarios/discriminated-service-lift", graph -> LiftQuery.read(graph.handle())).stream()
            .filter(r -> r.coordinate().equals(COORDINATE)).findFirst().orElseThrow();
        discriminated = FACTS.read("scenarios/discriminated-service-lift",
            graph -> DiscriminatedQuery.read(graph.handle(), Map.of(COORDINATE, row.targetType()))).get(COORDINATE);
        var source = ClassName.get(PACKAGE, "FanItemSource");
        var units = new HashMap<String, TypeSpec>();
        units.put(source.canonicalName(), LiftEmitter.sourceTable(source, row.table(), row.key()));
        for (var participant : discriminated.participants()) {
            units.put(PACKAGE + "." + participant.typeName(), projectionStub(participant.typeName()));
        }
        units.put(PACKAGE + ".SelectionOccurrences", selectionStub());
        units.put(PACKAGE + ".Lifts", TypeSpec.classBuilder("Lifts").addModifiers(Modifier.PUBLIC)
            .addMethod(LiftEmitter.discriminatedLift(row, discriminated, "fanItems", source,
                type -> ClassName.get(PACKAGE, type), ClassName.get(PACKAGE, "SelectionOccurrences")))
            .build());
        harness = EmittedCodeHarness.compile(units);
        lift = java.util.Arrays.stream(harness.load(PACKAGE + ".Lifts").getDeclaredMethods())
            .filter(m -> m.getName().equals("fanItems")).findFirst().orElseThrow();
        occurrences = harness.load(PACKAGE + ".SelectionOccurrences");
    }

    @AfterAll
    static void close() {
        if (harness != null) harness.close();
    }

    @BeforeEach
    void reset() throws Exception {
        restricted().clear();
        occurrences.getField("SELECTION").set(null, Map.of());
    }

    @SuppressWarnings("unchecked")
    private static List<String> restricted() throws Exception {
        return (List<String>) occurrences.getField("RESTRICTED").get(null);
    }

    private static FanBaseRecord fan(int id) {
        var r = new FanBaseRecord();
        r.set(Tables.FAN_BASE.FAN_BASE_ID, id);
        return r;
    }

    @SuppressWarnings("unchecked")
    private static List<Record> lift(List<? extends Record> records) throws Exception {
        DataFetchingEnvironment env = DataFetchingEnvironmentImpl.newDataFetchingEnvironment().build();
        return (List<Record>) lift.invoke(null, env, TestDatabase.dsl(), records);
    }

    /** A selected field of {@code name} on each of {@code types}, as graphql-java hands one over. */
    private static SelectedField selected(String name, String... types) {
        return (SelectedField) Proxy.newProxyInstance(SelectedField.class.getClassLoader(),
            new Class<?>[] {SelectedField.class}, (proxy, method, args) -> switch (method.getName()) {
                case "getName", "getResultKey" -> name;
                case "getObjectTypeNames" -> List.of(types);
                default -> null;
            });
    }

    @Test
    void theStoreStatesWhichNamesEveryParticipantKeeps() {
        assertThat(discriminated.refusal()).isEmpty();
        assertThat(discriminated.sharedAliasFieldNames())
            .as("each nesting field, and the child the interface declares; never a participant's own child")
            .containsExactly("alphaPlacement", "betaPlacement", "details");
    }

    @Test
    void eachParticipantsSelectionIsRestrictedKeepingTheSharedNames() throws Exception {
        lift(List.of(fan(1)));
        assertThat(restricted()).containsExactly(
            "FanAlpha:[alphaPlacement, betaPlacement, details]",
            "FanBeta:[alphaPlacement, betaPlacement, details]");
    }

    @Test
    void eachRowIsItsParticipantsWithASharedTermOnceInTheCallsOrder() throws Exception {
        var records = new ArrayList<FanBaseRecord>();
        records.add(fan(2));
        records.add(null);
        records.add(fan(1));
        records.add(fan(999999));
        var rows = lift(records);
        assertThat(rows).hasSize(4);
        assertThat(rows.get(1)).isNull();
        assertThat(rows.get(3)).as("a record whose row is gone").isNull();
        assertThat(rows.get(0).get("__discriminator__")).isEqualTo("BETA");
        assertThat(rows.get(2).get("__discriminator__")).isEqualTo("ALPHA");
        for (var row : List.of(rows.get(0), rows.get(2))) {
            assertThat(java.util.Arrays.stream(row.fields()).filter(f -> f.getName().equals("shared")).count())
                .as("a term every participant projects lands in the select once").isEqualTo(1);
            assertThat(row.get("own_FanAlpha")).isEqualTo("FanAlpha");
            assertThat(row.get("own_FanBeta")).isEqualTo("FanBeta");
        }
    }

    @Test
    void aScalarOneStepOffIsReadForItsParticipantsRowsOnlyWhereSelected() throws Exception {
        var rows = lift(List.of(fan(1), fan(2)));
        assertThat(java.util.Arrays.stream(rows.get(0).fields()).map(org.jooq.Field::getName))
            .as("not selected, not read").doesNotContain("FanAlpha_note");
        occurrences.getField("SELECTION").set(null, Map.of("note", List.of(selected("note", "FanAlpha"))));
        rows = lift(List.of(fan(1), fan(2)));
        var expected = TestDatabase.dsl().select(Tables.FAN_DETAIL.NOTE).from(Tables.FAN_DETAIL)
            .where(Tables.FAN_DETAIL.FAN_BASE_ID.eq(1)).limit(1).fetchOne(Tables.FAN_DETAIL.NOTE);
        assertThat(rows.get(0).get("FanAlpha_note")).as("the alpha row's one step").isEqualTo(expected);
        assertThat(rows.get(1).get("FanAlpha_note")).as("a beta row reads no alpha field").isNull();
    }

    /**
     * The selection helper the lift calls: {@code ownFields} hands back the selection a test set, and
     * {@code restrictTo} records the participant and the names it keeps, handing the selection on.
     */
    private static TypeSpec selectionStub() {
        TypeName grouped = ParameterizedTypeName.get(ClassName.get(Map.class), ClassName.get(String.class),
            ParameterizedTypeName.get(ClassName.get(List.class), ClassName.get(SelectedField.class)));
        TypeName strings = ParameterizedTypeName.get(ClassName.get(List.class), ClassName.get(String.class));
        return TypeSpec.classBuilder("SelectionOccurrences").addModifiers(Modifier.PUBLIC)
            .addField(com.palantir.javapoet.FieldSpec.builder(grouped, "SELECTION", Modifier.PUBLIC, Modifier.STATIC)
                .initializer("$T.of()", Map.class).build())
            .addField(com.palantir.javapoet.FieldSpec.builder(strings, "RESTRICTED", Modifier.PUBLIC,
                    Modifier.STATIC, Modifier.FINAL)
                .initializer("new $T<>()", ArrayList.class).build())
            .addMethod(MethodSpec.methodBuilder("ownFields").addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .returns(grouped)
                .addParameter(ClassName.get("graphql.schema", "DataFetchingFieldSelectionSet"), "set")
                .addStatement("return SELECTION").build())
            .addMethod(MethodSpec.methodBuilder("restrictTo").addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .returns(grouped)
                .addParameter(grouped, "selection")
                .addParameter(String.class, "type")
                .addParameter(ParameterizedTypeName.get(ClassName.get(java.util.Set.class), ClassName.get(String.class)),
                    "shared")
                .addStatement("RESTRICTED.add(type + \":\" + new $T<>(shared))", java.util.TreeSet.class)
                .addStatement("return selection").build())
            .build();
    }

    /**
     * A participant's projection unit: the shared row's key, a term every participant projects under
     * one alias, and one of its own.
     */
    private static TypeSpec projectionStub(String type) {
        TypeName fields = ParameterizedTypeName.get(ClassName.get(List.class),
            ParameterizedTypeName.get(ClassName.get("org.jooq", "Field"), WildcardTypeName.subtypeOf(Object.class)));
        var dsl = ClassName.get("org.jooq.impl", "DSL");
        return TypeSpec.classBuilder(type).addModifiers(Modifier.PUBLIC)
            .addMethod(MethodSpec.methodBuilder("$project").addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .returns(fields)
                .addParameter(Object.class, "selection")
                .addParameter(ParameterizedTypeName.get(ClassName.get("org.jooq", "Table"),
                    WildcardTypeName.subtypeOf(Object.class)), "table")
                .addParameter(ClassName.get(DataFetchingEnvironment.class), "env")
                .addStatement("return $T.of(table.field($S), $T.inline($S).as($S), $T.inline($S).as($S))", List.class,
                    "fan_base_id", dsl, "shared", "shared", dsl, type, "own_" + type)
                .build())
            .build();
    }
}
