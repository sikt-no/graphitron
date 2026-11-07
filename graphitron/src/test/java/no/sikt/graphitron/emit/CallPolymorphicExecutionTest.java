package no.sikt.graphitron.emit;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.TypeSpec;
import graphql.schema.DataFetchingEnvironment;
import graphql.schema.DataFetchingEnvironmentImpl;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.WildcardTypeName;
import graphql.schema.SelectedField;
import no.sikt.graphitron.emit.stub.FillRuntime;
import no.sikt.graphitron.emit.stub.ProjectedSelections;
import no.sikt.graphitron.emit.stub.Selected;
import no.sikt.graphitron.emit.stub.Selection;
import no.sikt.graphitron.rewrite.generators.schema.GraphitronClientExceptionClassGenerator;
import no.sikt.graphitron.rewrite.generators.util.SelectionOccurrencesClassGenerator;
import no.sikt.graphitron.model.read.StoreHandle;
import no.sikt.graphitron.model.test.CapturedStore;
import no.sikt.graphitron.model.test.CatalogInput;
import no.sikt.graphitron.model.test.ClasspathCorpus;
import no.sikt.graphitron.rewrite.test.compile.EmittedCodeHarness;
import no.sikt.graphitron.rewrite.test.db.TestDatabase;
import no.sikt.graphitron.rewrite.test.jooq.public_.Tables;
import no.sikt.graphitron.rewrite.test.tier.ExecutionTier;
import org.jooq.Record;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.lang.model.element.Modifier;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static no.sikt.graphitron.common.configuration.TestConfiguration.testContext;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A root service call answering a multitable interface, its body emitted from the store's rows and
 * run against PostgreSQL: each record the service hands back is lifted onto its own table's row,
 * told apart by its class, carrying its type name for the resolver, in the service's order. Each
 * member's projection is handed only its own occurrences of the fetched field's selection, through
 * the generated selection adapter, so a field another member's fragment selects never reaches it.
 */
@ExecutionTier
class CallPolymorphicExecutionTest {

    private static final String PACKAGE = "callpoly";
    private static final String SERVICE = "no.sikt.graphitron.rewrite.test.services.PolymorphicSearchService";
    private static final String SDL = """
        interface Searchable { id: Int }
        type Film implements Searchable @table(name: "film") { id: Int @field(name: "FILM_ID") }
        type Actor implements Searchable @table(name: "actor") { id: Int @field(name: "ACTOR_ID") }
        type Query {
          searchMany: [Searchable!]! @service(service: {className: "%1$s", method: "searchDocuments"})
          searchOne: Searchable @service(service: {className: "%1$s", method: "searchOne"})
        }
        """.formatted(SERVICE);
    private static final FillEmitter.Runtime RUNTIME = new FillEmitter.Runtime(
        key -> CodeBlock.of("$T.contextArgument(env, $S)", FillRuntime.class, key),
        coordinate -> CodeBlock.of("$T.sessionHandle(dsl, $S)", FillRuntime.class, coordinate),
        (member, coordinate) -> CodeBlock.of("$T.sessionMember_$L(dsl, $S)", FillRuntime.class, member, coordinate));

    @TempDir
    static Path tmp;

    private static EmittedCodeHarness harness;
    private static Map<String, Method> bodies;

    @BeforeAll
    static void emit() throws Exception {
        List<PolymorphicLiftQuery.Row> rows;
        List<CallQuery.Row> calls;
        List<FillQuery.Row> fills;
        try (var store = CapturedStore.ofCatalogWith(tmp, CapturedStore.GRAPH, SDL,
                CatalogInput.of(testContext()), ClasspathCorpus.entries())) {
            var handle = new StoreHandle(store.dsl(), CapturedStore.GRAPH);
            rows = PolymorphicLiftQuery.read(handle);
            calls = CallQuery.read(handle);
            fills = FillQuery.read(handle);
        }
        assertThat(rows).extracting(PolymorphicLiftQuery.Row::coordinate)
            .containsExactly("Query.searchMany", "Query.searchOne");
        var liftHost = ClassName.get(PACKAGE, "Lifts");
        var selection = ClassName.get(PACKAGE + ".util", "SelectionOccurrences");
        var liftType = TypeSpec.classBuilder(liftHost).addModifiers(Modifier.PUBLIC);
        var bodyType = TypeSpec.classBuilder("Bodies").addModifiers(Modifier.PUBLIC);
        var units = new HashMap<String, TypeSpec>();
        for (var row : rows) {
            var name = row.coordinate().replace("Query.", "");
            var members = new ArrayList<CallEmitter.Polymorphic.Member>();
            for (var participant : row.participants()) {
                var source = ClassName.get(PACKAGE, participant.typeName() + "Source");
                units.putIfAbsent(source.canonicalName(),
                    LiftEmitter.sourceTable(source, participant.table(), participant.key()));
                units.putIfAbsent(PACKAGE + "." + participant.typeName(), recordingProjection(participant.typeName()));
                var liftRow = new LiftQuery.Row(row.coordinate(), participant.typeName(), true, true,
                    participant.table(), participant.key());
                var method = name + participant.typeName();
                liftType.addMethod(LiftEmitter.lift(liftRow, method, source,
                    ClassName.get(PACKAGE, participant.typeName()), selection, Optional.of(participant.typeName())));
                members.add(new CallEmitter.Polymorphic.Member(participant.typeName(),
                    ClassName.bestGuess(participant.table().recordClassName()), liftHost, method));
            }
            var call = calls.stream().filter(c -> c.coordinate().equals(row.coordinate())).findFirst().orElseThrow();
            var filled = fills.stream().filter(f -> f.coordinate().equals(row.coordinate())).findFirst().orElseThrow();
            var result = CallEmitter.body(call, filled, Map.of(), RUNTIME, name, Optional.empty(), Optional.empty(),
                Optional.of(new CallEmitter.Polymorphic(row.list(), members)));
            assertThat(result).as(row.coordinate()).isInstanceOf(CallEmitter.Result.Emitted.class);
            var emitted = (CallEmitter.Result.Emitted) result;
            bodyType.addMethod(emitted.body());
            emitted.helpers().forEach(bodyType::addMethod);
        }
        units.put(liftHost.canonicalName(), liftType.build());
        units.put(PACKAGE + ".Bodies", bodyType.build());
        for (TypeSpec spec : SelectionOccurrencesClassGenerator.generate(PACKAGE)) {
            units.put(PACKAGE + ".util." + spec.name(), spec);
        }
        for (TypeSpec spec : GraphitronClientExceptionClassGenerator.generate()) {
            units.put(PACKAGE + ".schema." + spec.name(), spec);
        }
        harness = EmittedCodeHarness.compileStrictly(units);
        bodies = new HashMap<>();
        for (var m : harness.load(PACKAGE + ".Bodies").getDeclaredMethods()) bodies.put(m.getName(), m);
    }

    @AfterAll
    static void close() {
        if (harness != null) harness.close();
    }

    /**
     * A projection unit's {@code $project} that selects every column of its table, as the real unit
     * would for a selection naming them all, and records the selection it was handed.
     */
    private static TypeSpec recordingProjection(String type) {
        TypeName fields = ParameterizedTypeName.get(ClassName.get(List.class),
            ParameterizedTypeName.get(ClassName.get("org.jooq", "Field"), WildcardTypeName.subtypeOf(Object.class)));
        return TypeSpec.classBuilder(type).addModifiers(Modifier.PUBLIC)
            .addMethod(MethodSpec.methodBuilder("$project")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .returns(fields)
                .addParameter(Object.class, "selection")
                .addParameter(ParameterizedTypeName.get(ClassName.get("org.jooq", "Table"),
                    WildcardTypeName.subtypeOf(Object.class)), "table")
                .addParameter(ClassName.get("graphql.schema", "DataFetchingEnvironment"), "env")
                .addStatement("$T.record($S, selection)", ProjectedSelections.class, type)
                .addStatement("return $T.of(table.fields())", List.class)
                .build())
            .build();
    }

    /**
     * The fetched field's selection: id on both members, title through a fragment on Film, and
     * firstName through a fragment on Actor under the key title, which a member's projection must
     * not be handed unless it is the member's own.
     */
    private static final DataFetchingEnvironment ENV = DataFetchingEnvironmentImpl.newDataFetchingEnvironment()
        .selectionSet(Selection.of(
            new Selected("id", "id", List.of("Film", "Actor")),
            new Selected("title", "title", List.of("Film")),
            new Selected("firstName", "title", List.of("Actor"))))
        .build();

    @BeforeEach
    void forget() {
        ProjectedSelections.clear();
    }

    /** The field names in the selection a member's projection was last handed, by result key. */
    @SuppressWarnings("unchecked")
    private static Map<String, List<String>> handed(String type) {
        var all = ProjectedSelections.handed(type);
        assertThat(all).as(type + "'s projection was called").isNotEmpty();
        var grouped = (Map<String, List<SelectedField>>) all.getLast();
        var names = new java.util.LinkedHashMap<String, List<String>>();
        grouped.forEach((key, fields) -> names.put(key, fields.stream().map(SelectedField::getName).toList()));
        return names;
    }

    @Test
    void eachMembersProjectionIsHandedOnlyItsOwnOccurrences() throws Exception {
        bodies.get("searchMany").invoke(null, ENV, TestDatabase.dsl());
        assertThat(handed("Film")).containsExactly(
            Map.entry("id", List.of("id")), Map.entry("title", List.of("title")));
        assertThat(handed("Actor")).containsExactly(
            Map.entry("id", List.of("id")), Map.entry("title", List.of("firstName")));
    }

    @Test
    @SuppressWarnings("unchecked")
    void eachRecordIsLiftedOntoItsOwnTableInTheServicesOrderWithItsType() throws Exception {
        var rows = (List<Record>) bodies.get("searchMany").invoke(null, ENV, TestDatabase.dsl());
        assertThat(rows).extracting(r -> r.get("__typename")).containsExactly("Film", "Actor");
        assertThat(rows.get(0).get(Tables.FILM.FILM_ID.getName())).isEqualTo(1);
        assertThat(rows.get(1).get(Tables.ACTOR.ACTOR_ID.getName())).isEqualTo(1);
        assertThat(rows.get(1).get(Tables.ACTOR.FIRST_NAME.getName())).isNotNull();
    }

    @Test
    void oneRecordIsLiftedOntoItsRowWithItsType() throws Exception {
        var row = (Record) bodies.get("searchOne").invoke(null, ENV, TestDatabase.dsl());
        assertThat(row.get("__typename")).isEqualTo("Film");
        assertThat(row.get(Tables.FILM.FILM_ID.getName())).isEqualTo(1);
    }
}
