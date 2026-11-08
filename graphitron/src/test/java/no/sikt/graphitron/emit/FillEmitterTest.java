package no.sikt.graphitron.emit;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.JavaFile;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.TypeSpec;
import graphql.GraphQLContext;
import graphql.schema.DataFetchingEnvironment;
import graphql.schema.DataFetchingEnvironmentImpl;
import no.sikt.graphitron.emit.stub.FillRuntime;
import no.sikt.graphitron.model.test.FactGraphs;
import no.sikt.graphitron.rewrite.test.services.FilmKeySummary;
import no.sikt.graphitron.rewrite.test.services.FilmMood;
import no.sikt.graphitron.rewrite.test.compile.EmittedCodeHarness;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.jooq.DSLContext;
import org.jooq.Record1;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import javax.lang.model.element.Modifier;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link FillEmitter}'s fills, compiled and run: each parameter's value read off a real fetch, a
 * request context, a jOOQ context and the batch's keys, the locals typed as the parameters are so
 * that compiling them checks the types, and a value the wire does not hand over as the parameter
 * takes it refused with the parameter's name rather than emitted.
 *
 * <p>Each call is read from the {@code scenarios/occurrence-call-parameter} fact document, so its fills
 * are ones a capture states. The context and session runtime are the stubs in
 * {@link no.sikt.graphitron.emit.stub}, standing in for the generated ones. Compiled
 * strictly, on the terms a consumer compiles generated sources on, so an unchecked cast without its
 * suppression fails here rather than in the example build.
 */
@PipelineTier
class FillEmitterTest {

    @RegisterExtension
    static final FactGraphs.Reader FACTS = FactGraphs.reader();

    private static final String PACKAGE = "fillrun";
    private static final ClassName RUNTIME_CLASS = ClassName.get(FillRuntime.class);
    private static final FillEmitter.Runtime RUNTIME = new FillEmitter.Runtime(
        key -> CodeBlock.of("$T.contextArgument(env, $S)", RUNTIME_CLASS, key),
        coordinate -> CodeBlock.of("$T.sessionHandle(dsl, $S)", RUNTIME_CLASS, coordinate),
        (member, coordinate) -> CodeBlock.of("$T.sessionMember_$L(dsl, $S)", RUNTIME_CLASS, member, coordinate));

    private static final TypeName ENV = ClassName.get(DataFetchingEnvironment.class);
    private static final TypeName DSL_CONTEXT = ClassName.get(DSLContext.class);
    private static final TypeName KEYS = ParameterizedTypeName.get(ClassName.get(Set.class),
        ParameterizedTypeName.get(ClassName.get(Record1.class), ClassName.get(Integer.class)));

    private static EmittedCodeHarness harness;
    private static Map<String, Method> probes;

    /** Every call the fill emitter is shown, as the store states it. */
    private static Map<String, FillQuery.Row> rows;

    private static FillQuery.Row row(String coordinate) {
        return java.util.Objects.requireNonNull(rows.get(coordinate), coordinate);
    }

    @BeforeAll
    static void emit() throws Exception {
        rows = FACTS.read("scenarios/occurrence-call-parameter", graph -> FillQuery.read(graph.handle()).stream()
            .collect(java.util.stream.Collectors.toMap(FillQuery.Row::coordinate, java.util.function.Function.identity())));
        var host = TypeSpec.classBuilder("Probes").addModifiers(Modifier.PUBLIC)
            .addMethod(probe("root", row("Query.callEveryFill"), Optional.empty()))
            .addMethod(probe("configured", row("Query.callCountOn"), Optional.empty()))
            .addMethod(probe("enums", row("Query.callMoods"), Optional.empty()))
            .addMethod(probe("listed", row("Query.callListed"), Optional.empty()))
            .addMethod(probe("child", row("Film.titleUppercase"), Optional.of(KEYS)))
            .build();
        var dir = Files.createTempDirectory("fill");
        harness = EmittedCodeHarness.compileFilesStrictly(
            List.of(JavaFile.builder(PACKAGE, host).build().writeToPath(dir)));
        probes = new HashMap<>();
        for (var m : harness.load(PACKAGE + ".Probes").getDeclaredMethods()) probes.put(m.getName(), m);
    }

    /** A method with the body's signature, declaring the fills and handing back the arguments. */
    private static MethodSpec probe(String name, FillQuery.Row row, Optional<TypeName> keys) {
        var emitted = (FillEmitter.Result.Emitted) FillEmitter.emit(row, keys, RUNTIME);
        var method = MethodSpec.methodBuilder(name)
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(Object[].class);
        keys.ifPresent(type -> method.addParameter(type, "keys"));
        method.addParameter(ENV, "env").addParameter(DSL_CONTEXT, "dsl");
        emitted.statements().forEach(method::addStatement);
        method.addStatement("return new Object[] {$L}", CodeBlock.join(emitted.arguments(), ", "));
        return method.build();
    }

    @AfterAll
    static void close() {
        if (harness != null) harness.close();
    }

    private static DataFetchingEnvironment env(Map<String, Object> arguments) {
        return DataFetchingEnvironmentImpl.newDataFetchingEnvironment()
            .arguments(arguments)
            .graphQLContext(GraphQLContext.newContext().of("userId", "u-7").build())
            .build();
    }

    @Test
    void eachFillReadsItsValueAsTheParameterTakesIt() throws Exception {
        var dsl = DSL.using(SQLDialect.POSTGRES);
        var arguments = Map.<String, Object>of("limit", 5,
            "input", Map.of("ids", List.of(1, 2), "inner", Map.of("deep", "down"), "count", 3));
        var values = (Object[]) probes.get("root").invoke(null, env(arguments), dsl);
        assertThat(values[0]).isSameAs(dsl);
        assertThat(values[1]).isEqualTo(5);
        assertThat(values[2]).isEqualTo(List.of(1, 2));
        assertThat(values[3]).isEqualTo("down");
        assertThat(values[4]).isEqualTo("u-7");
        assertThat(((FilmKeySummary) values[5]).label()).isEqualTo("handle for Query.callEveryFill");
        assertThat(values[6]).isEqualTo("label for Query.callEveryFill");
        assertThat(values[7]).isEqualTo(3);
    }

    @Test
    void anInputFieldUnderAnAbsentInputObjectIsNull() throws Exception {
        var values = (Object[]) probes.get("root").invoke(null, env(Map.of("limit", 5)),
            DSL.using(SQLDialect.POSTGRES));
        assertThat(values[2]).isNull();
        assertThat(values[3]).isNull();
    }

    @Test
    void anEnumIsPassedAsItsStringOrTurnedIntoTheJavaConstant() throws Exception {
        var arguments = Map.<String, Object>of("moodName", "HAPPY", "input", Map.of("mood", "SAD"),
            "moods", List.of("HAPPY", "SAD"));
        var values = (Object[]) probes.get("enums").invoke(null, env(arguments), DSL.using(SQLDialect.POSTGRES));
        assertThat(values[0]).isEqualTo("HAPPY");
        assertThat(values[1]).isEqualTo(FilmMood.SAD);
        assertThat(values[2]).isEqualTo(List.of(FilmMood.HAPPY, FilmMood.SAD));
    }

    @Test
    void anAbsentEnumIsNull() throws Exception {
        var values = (Object[]) probes.get("enums").invoke(null, env(Map.of()), DSL.using(SQLDialect.POSTGRES));
        assertThat(values).containsOnlyNulls();
    }

    @Test
    void aScalarReadThroughListsIsOneListDeeperForEach() throws Exception {
        var arguments = Map.<String, Object>of("input", Map.of(
            "items", List.of(Map.of("id", 1), Map.of("id", 3)),
            "groups", List.of(Map.of("items", List.of(Map.of("id", 1), Map.of("id", 2))),
                Map.of("items", List.of(Map.of("id", 4))))));
        var values = (Object[]) probes.get("listed").invoke(null, env(arguments), DSL.using(SQLDialect.POSTGRES));
        assertThat(values[0]).isEqualTo(List.of(1, 3));
        assertThat(values[1]).isEqualTo(List.of(List.of(1, 2), List.of(4)));
    }

    @Test
    void anAbsentLevelThroughAListIsNullAtItsDepth() throws Exception {
        var items = new java.util.ArrayList<Object>();
        items.add(Map.of("id", 1));
        items.add(null);
        var groups = new java.util.ArrayList<Object>();
        groups.add(Map.of());
        var values = (Object[]) probes.get("listed").invoke(null,
            env(Map.of("input", Map.of("items", items, "groups", groups))), DSL.using(SQLDialect.POSTGRES));
        assertThat(values[0]).isEqualTo(java.util.Arrays.asList(1, null));
        assertThat(values[1]).isEqualTo(java.util.Collections.singletonList(null));
        var absent = (Object[]) probes.get("listed").invoke(null, env(Map.of()), DSL.using(SQLDialect.POSTGRES));
        assertThat(absent).containsOnlyNulls();
    }

    @Test
    void anEnumReadThroughAListIsLeftToBuilding() {
        var row = row("Query.callMoodsThroughList");
        assertThat(FillEmitter.emit(row, Optional.empty(), RUNTIME))
            .isInstanceOf(FillEmitter.Result.NeedsBuilding.class);
    }

    @Test
    void aConfigurationTakesTheContextsConfiguration() throws Exception {
        var dsl = DSL.using(SQLDialect.POSTGRES);
        var values = (Object[]) probes.get("configured").invoke(null, env(Map.of()), dsl);
        assertThat(values[0]).isSameAs(dsl.configuration());
    }

    @Test
    void aBatchedCallTakesTheFramesKeys() throws Exception {
        var keys = Set.of(DSL.using(SQLDialect.POSTGRES).newRecord(DSL.field("film_id", Integer.class)).values(1));
        var values = (Object[]) probes.get("child").invoke(null, keys, env(Map.of()), DSL.using(SQLDialect.POSTGRES));
        assertThat(values[0]).isSameAs(keys);
    }

    @Test
    void anInputObjectIsLeftToBuildingByName() {
        var row = row("Query.countDrafts");
        assertThat(FillEmitter.emit(row, Optional.empty(), RUNTIME))
            .isInstanceOfSatisfying(FillEmitter.Result.NeedsBuilding.class, refused -> {
                assertThat(refused.parameterName()).isEqualTo("drafts");
                assertThat(refused.reason()).contains("input object in");
            });
    }

    @Test
    void keysInAnotherShapeAreLeftToBuilding() {
        // Its source takes films whole, which keys of the frame's shape are not.
        var row = row("Film.titleTitlecase");
        assertThat(FillEmitter.emit(row, Optional.of(KEYS), RUNTIME))
            .isInstanceOf(FillEmitter.Result.NeedsBuilding.class);
    }
}
