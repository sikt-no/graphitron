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
import no.sikt.graphitron.rewrite.test.compile.EmittedCodeHarness;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.jooq.DSLContext;
import org.jooq.Record1;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.lang.model.element.Modifier;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
 * <p>The context and session runtime are stubs standing in for the generated ones. Compiled
 * strictly, on the terms a consumer compiles generated sources on, so an unchecked cast without its
 * suppression fails here rather than in the example build.
 */
@UnitTier
class FillEmitterTest {

    private static final String PACKAGE = "fillrun";
    private static final ClassName RUNTIME_CLASS = ClassName.get(PACKAGE, "Runtime");
    private static final FillEmitter.Runtime RUNTIME = new FillEmitter.Runtime(
        key -> CodeBlock.of("$T.contextArgument(env, $S)", RUNTIME_CLASS, key),
        coordinate -> CodeBlock.of("$T.sessionHandle(dsl, $S)", RUNTIME_CLASS, coordinate),
        (member, coordinate) -> CodeBlock.of("$T.sessionMember_$L(dsl, $S)", RUNTIME_CLASS, member, coordinate));

    private static final Map<String, String> STUBS = Map.of(
        "Handle", """
            public record Handle(String label) {}
            """,
        "Mood", """
            public enum Mood { HAPPY, SAD }
            """,
        "Runtime", """
            import graphql.schema.DataFetchingEnvironment;
            import org.jooq.DSLContext;
            public final class Runtime {
                public static Object contextArgument(DataFetchingEnvironment env, String key) {
                    return env.getGraphQlContext().get(key);
                }
                public static Handle sessionHandle(DSLContext dsl, String coordinate) {
                    return new Handle("handle for " + coordinate);
                }
                public static String sessionMember_label(DSLContext dsl, String coordinate) {
                    return "label for " + coordinate;
                }
            }
            """);

    private static final TypeName ENV = ClassName.get(DataFetchingEnvironment.class);
    private static final TypeName DSL_CONTEXT = ClassName.get(DSLContext.class);
    private static final TypeName KEYS = ParameterizedTypeName.get(ClassName.get(Set.class),
        ParameterizedTypeName.get(ClassName.get(Record1.class), ClassName.get(Integer.class)));

    private static EmittedCodeHarness harness;
    private static Map<String, Method> probes;

    private static FillQuery.Fill fill(int position, String name, String type, String erased, String fill,
                                       String contextKey, String member, FillQuery.Landing landing) {
        return new FillQuery.Fill(position, name, type, erased, fill, contextKey, member,
            Optional.ofNullable(landing));
    }

    private static FillQuery.Landing scalar(String... path) {
        return new FillQuery.Landing(List.of(path), "SCALAR", false);
    }

    /** A root call taking every fill that needs no building. */
    private static final FillQuery.Row ROOT = new FillQuery.Row("Query.call", "Query.call", List.of(
        fill(0, "dsl", "org.jooq.DSLContext", "org.jooq.DSLContext", "DSL_CONTEXT", null, null, null),
        fill(1, "limit", "java.lang.Integer", "java.lang.Integer", "ARGUMENT", null, null, scalar("limit")),
        fill(2, "ids", "java.util.List<java.lang.Integer>", "java.util.List", "ARGUMENT", null, null,
            scalar("input", "ids")),
        fill(3, "deep", "java.lang.String", "java.lang.String", "ARGUMENT", null, null,
            scalar("input", "inner", "deep")),
        fill(4, "userId", "java.lang.String", "java.lang.String", "CONTEXT_KEY", "userId", null, null),
        fill(5, "summary", "fillrun.Handle", "fillrun.Handle", "SESSION", null, null, null),
        fill(6, "label", "java.lang.String", "java.lang.String", "SESSION_MEMBER", null, "label", null),
        fill(7, "count", "int", "int", "ARGUMENT", null, null, scalar("input", "count"))),
        true);

    private static FillQuery.Landing enumAt(String... path) {
        return new FillQuery.Landing(List.of(path), "ENUM", false);
    }

    /** A root call taking enum arguments: as a String, as a Java enum nested, as a list of one. */
    private static final FillQuery.Row ENUMS = new FillQuery.Row("Query.enums", "Query.enums", List.of(
        fill(0, "moodName", "java.lang.String", "java.lang.String", "ARGUMENT", null, null, enumAt("moodName")),
        fill(1, "mood", "fillrun.Mood", "fillrun.Mood", "ARGUMENT", null, null, enumAt("input", "mood")),
        fill(2, "moods", "java.util.List<fillrun.Mood>", "java.util.List", "ARGUMENT", null, null,
            enumAt("moods"))),
        true);

    /** A root call taking the jOOQ context as a Configuration. */
    private static final FillQuery.Row CONFIGURED = new FillQuery.Row("Query.configured", "Query.configured",
        List.of(fill(0, "configuration", "org.jooq.Configuration", "org.jooq.Configuration", "DSL_CONTEXT",
            null, null, null)),
        true);

    /** A batched call taking the frame's keys. */
    private static final FillQuery.Row CHILD = new FillQuery.Row("Film.upper", "Film.upper", List.of(
        fill(0, "filmIds", "java.util.Set<org.jooq.Record1<java.lang.Integer>>", "java.util.Set", "SOURCE",
            null, null, null),
        fill(1, "dsl", "org.jooq.DSLContext", "org.jooq.DSLContext", "DSL_CONTEXT", null, null, null)),
        true);

    @BeforeAll
    static void emit() throws Exception {
        var host = TypeSpec.classBuilder("Probes").addModifiers(Modifier.PUBLIC)
            .addMethod(probe("root", ROOT, Optional.empty()))
            .addMethod(probe("configured", CONFIGURED, Optional.empty()))
            .addMethod(probe("enums", ENUMS, Optional.empty()))
            .addMethod(probe("child", CHILD, Optional.of(KEYS)))
            .build();
        var dir = Files.createTempDirectory("fill");
        var files = new ArrayList<Path>();
        files.add(JavaFile.builder(PACKAGE, host).build().writeToPath(dir));
        for (var stub : STUBS.entrySet()) {
            var file = dir.resolve(PACKAGE).resolve(stub.getKey() + ".java");
            Files.writeString(file, "package " + PACKAGE + ";\n" + stub.getValue());
            files.add(file);
        }
        harness = EmittedCodeHarness.compileFilesStrictly(files);
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
        assertThat(values[5]).hasToString("Handle[label=handle for Query.call]");
        assertThat(values[6]).isEqualTo("label for Query.call");
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
        var mood = harness.load(PACKAGE + ".Mood");
        assertThat(values[0]).isEqualTo("HAPPY");
        assertThat(values[1]).isEqualTo(mood.getEnumConstants()[1]);
        assertThat(values[2]).isEqualTo(List.of(mood.getEnumConstants()));
    }

    @Test
    void anAbsentEnumIsNull() throws Exception {
        var values = (Object[]) probes.get("enums").invoke(null, env(Map.of()), DSL.using(SQLDialect.POSTGRES));
        assertThat(values).containsOnlyNulls();
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
        var row = new FillQuery.Row("Query.drafts", "Query.drafts", List.of(
            fill(0, "drafts", "java.util.List<fillrun.Handle>", "java.util.List", "ARGUMENT", null, null,
                new FillQuery.Landing(List.of("in"), "INPUT_OBJECT", false))), true);
        assertThat(FillEmitter.emit(row, Optional.empty(), RUNTIME))
            .isInstanceOfSatisfying(FillEmitter.Result.NeedsBuilding.class, refused -> {
                assertThat(refused.parameterName()).isEqualTo("drafts");
                assertThat(refused.reason()).contains("input object in");
            });
    }

    @Test
    void keysInAnotherShapeAreLeftToBuilding() {
        var row = new FillQuery.Row("Film.titlecase", "Film.titlecase", List.of(
            fill(0, "films", "java.util.Set<fillrun.Handle>", "java.util.Set", "SOURCE", null, null, null)),
            true);
        assertThat(FillEmitter.emit(row, Optional.of(KEYS), RUNTIME))
            .isInstanceOf(FillEmitter.Result.NeedsBuilding.class);
    }
}
