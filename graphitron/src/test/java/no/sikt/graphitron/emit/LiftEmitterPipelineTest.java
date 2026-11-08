package no.sikt.graphitron.emit;

import no.sikt.graphitron.model.test.FactGraphs;
import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.TypeSpec;
import com.palantir.javapoet.WildcardTypeName;
import no.sikt.graphitron.rewrite.test.compile.EmittedCodeHarness;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import javax.lang.model.element.Modifier;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link LiftEmitter} over the lifts a captured schema states: each lift renders a method that
 * compiles against the test catalog, taking the records the call handed back and answering the
 * lifted rows: a list for a list and one row for one at a root, a list for one record per parent
 * and a list of lists for a list per parent under an object; a composite key compiles as one.
 *
 * <p>The projection unit and the selection scaffold the methods call are stubs here, standing in for
 * the generated ones, so what compiles is the lift itself: its derived source table with its
 * constants, its typed key cells and its join. Read over the {@code scenarios/service-lift} fact
 * document, as the execution tests that run these lifts are.
 */
@PipelineTier
class LiftEmitterPipelineTest {

    @RegisterExtension
    static final FactGraphs.Reader FACTS = FactGraphs.reader();

    private static final String PACKAGE = "liftprobe";

    private static Map<String, LiftQuery.Row> rows;

    @BeforeAll
    static void read() {
        rows = FACTS.read("scenarios/service-lift", graph -> LiftQuery.read(graph.handle()).stream()
            .collect(Collectors.toMap(LiftQuery.Row::coordinate, Function.identity())));
    }

    @Test
    void theRootLiftsCompileAndTakeWhatTheCallHandedBack() throws Exception {
        var selection = ClassName.get(PACKAGE, "SelectionOccurrences");
        var host = TypeSpec.classBuilder("Lifts").addModifiers(Modifier.PUBLIC);
        var units = new java.util.HashMap<String, TypeSpec>();
        for (var coordinate : List.of("Query.filmsByIds", "Query.filmById", "Query.filmActorsByIds",
                "Film.languageByService", "Film.castMembers")) {
            var row = rows.get(coordinate);
            assertThat(row).as(coordinate).isNotNull();
            var source = ClassName.get(PACKAGE, row.targetType() + "Source");
            units.put(source.canonicalName(), LiftEmitter.sourceTable(source, row.table(), row.key()));
            host.addMethod(LiftEmitter.lift(row, "lift" + row.coordinate().replace(".", ""), source,
                ClassName.get(PACKAGE, row.targetType()), selection));
        }
        units.put(PACKAGE + ".Lifts", host.build());
        units.put(PACKAGE + ".SelectionOccurrences", selectionStub());
        units.put(PACKAGE + ".Film", projectionStub("Film"));
        units.put(PACKAGE + ".FilmActor", projectionStub("FilmActor"));
        units.put(PACKAGE + ".Language", projectionStub("Language"));
        try (var harness = EmittedCodeHarness.compile(units)) {
            var methods = Arrays.stream(harness.load(PACKAGE + ".Lifts").getDeclaredMethods())
                .collect(Collectors.toMap(Method::getName, Function.identity()));
            assertThat(methods.get("liftQueryfilmsByIds").getReturnType()).isEqualTo(List.class);
            assertThat(methods.get("liftQueryfilmsByIds").getParameterTypes())
                .containsExactly(graphql.schema.DataFetchingEnvironment.class,
                    org.jooq.DSLContext.class, List.class);
            assertThat(methods.get("liftQueryfilmById").getReturnType()).isEqualTo(org.jooq.Record.class);
            assertThat(methods.get("liftQueryfilmById").getParameterTypes()[2])
                .isEqualTo(org.jooq.Record.class);
            assertThat(harness.load(PACKAGE + ".FilmActorSource").getField("ACTOR_ID")).isNotNull();
            assertThat(methods.get("liftFilmlanguageByService").getReturnType())
                .as("one record per parent lifts as a list, a parent's position its record's")
                .isEqualTo(List.class);
            assertThat(methods.get("liftFilmcastMembers").getGenericReturnType().getTypeName())
                .as("a list per parent lifts as a list of lists")
                .isEqualTo("java.util.List<java.util.List<org.jooq.Record>>");
            assertThat(methods.get("liftQueryfilmActorsByIds").getReturnType()).isEqualTo(List.class);
        }
    }

    /** {@code SelectionOccurrences.ownFields(selectionSet)}: what the projection is handed. */
    static TypeSpec selectionStub() {
        return TypeSpec.classBuilder("SelectionOccurrences").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodSpec.methodBuilder("ownFields")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .returns(Object.class)
                .addParameter(ClassName.get("graphql.schema", "DataFetchingFieldSelectionSet"), "set")
                .addStatement("return set")
                .build())
            .build();
    }

    /** A projection unit's {@code $project(selection, table, env)}: the fields it selects. */
    static TypeSpec projectionStub(String type) {
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
                .addStatement("return $T.of(table.fields())", List.class)
                .build())
            .build();
    }
}
