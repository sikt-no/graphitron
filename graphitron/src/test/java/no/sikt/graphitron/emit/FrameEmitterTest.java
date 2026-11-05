package no.sikt.graphitron.emit;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.TypeSpec;
import graphql.execution.DataFetcherResult;
import graphql.execution.ExecutionStepInfo;
import graphql.execution.ResultPath;
import graphql.schema.DataFetchingEnvironment;
import graphql.schema.DataFetchingEnvironmentImpl;
import graphql.Scalars;
import no.sikt.graphitron.rewrite.test.compile.EmittedCodeHarness;
import no.sikt.graphitron.rewrite.test.jooq.public_.Tables;
import no.sikt.graphitron.rewrite.test.jooq.public_.tables.records.FilmRecord;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.dataloader.DataLoaderRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.lang.model.element.Modifier;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link FrameEmitter}'s fetchers, compiled and run: at a root the frame hands back what the body
 * answers and routes what it throws, into the outcome's error list where the outcome claims it;
 * under an object the frame reads each parent's key off its row and runs the body once for the
 * batch, each parent getting its own key's value.
 *
 * <p>The body, the jOOQ context and the error runtime are stubs standing in for the generated ones;
 * the batching runs through a real {@link DataLoaderRegistry}.
 */
@UnitTier
class FrameEmitterTest {

    private static final String PACKAGE = "framerun";
    private static final ClassName BODY = ClassName.get(PACKAGE, "Body");
    private static final ClassName ROUTER = ClassName.get(PACKAGE, "ErrorRouter");
    private static final ClassName OUTCOME = ClassName.get(PACKAGE, "Outcome");
    private static final ClassName MAPPINGS = ClassName.get(PACKAGE, "ErrorMappings");

    private static final FrameEmitter.Runtime RUNTIME = new FrameEmitter.Runtime(ROUTER, OUTCOME, MAPPINGS,
        type -> type.toUpperCase(), env -> CodeBlock.of("$T.dsl($L)", BODY, env));

    private static final FrameQuery.ParentTable FILM = new FrameQuery.ParentTable("film",
        "no.sikt.graphitron.rewrite.test.jooq.public_.Tables", "FILM",
        List.of(new FrameQuery.KeyColumn("film_id", "FILM_ID", "java.lang.Integer")));

    /** The generated runtime and the body, as the stubs this test compiles beside the fetchers. */
    private static final Map<String, String> STUBS = Map.of(
        "Body", """
            import graphql.schema.DataFetchingEnvironment;
            import java.util.Map;
            import java.util.Set;
            import java.util.stream.Collectors;
            import org.jooq.DSLContext;
            import org.jooq.Record1;
            public final class Body {
                public static int batches;
                public static DSLContext dsl(DataFetchingEnvironment env) { return null; }
                public static Integer count(DataFetchingEnvironment env, DSLContext dsl) { return 42; }
                public static Integer fail(DataFetchingEnvironment env, DSLContext dsl) { throw new IllegalStateException("body failed"); }
                public static String lookup(DataFetchingEnvironment env, DSLContext dsl) { return "found"; }
                public static String refuse(DataFetchingEnvironment env, DSLContext dsl) { throw new IllegalStateException("refused"); }
                public static Map<Record1<Integer>, String> upper(Set<Record1<Integer>> keys, DataFetchingEnvironment env, DSLContext dsl) {
                    batches++;
                    return keys.stream().collect(Collectors.toMap(k -> k, k -> "film " + k.value1()));
                }
            }
            """,
        "ErrorRouter", """
            import graphql.GraphqlErrorBuilder;
            import graphql.execution.DataFetcherResult;
            import graphql.schema.DataFetchingEnvironment;
            public final class ErrorRouter {
                public static <P> DataFetcherResult<P> surfaceClientErrorOrRedact(Throwable thrown, DataFetchingEnvironment env) {
                    return DataFetcherResult.<P>newResult()
                        .error(GraphqlErrorBuilder.newError().message("redacted: " + thrown.getMessage()).build()).build();
                }
                public static Throwable claim(Throwable thrown, String[] mappings, DataFetchingEnvironment env) {
                    return "refused".equals(thrown.getMessage()) ? new Claimed(mappings[0] + " " + thrown.getMessage()) : null;
                }
                static final class Claimed extends RuntimeException {
                    Claimed(String message) { super(message); }
                    @Override public String toString() { return getMessage(); }
                }
            }
            """,
        "Outcome", """
            import java.util.List;
            public interface Outcome<T> {
                final class Success<T> implements Outcome<T> {
                    private final T value;
                    public Success(T value) { this.value = value; }
                    @Override public String toString() { return "Success(" + value + ")"; }
                }
                final class ErrorList<T> implements Outcome<T> {
                    private final List<Throwable> errors;
                    public ErrorList(List<Throwable> errors) { this.errors = errors; }
                    @Override public String toString() { return "ErrorList(" + errors + ")"; }
                }
            }
            """,
        "ErrorMappings", """
            public final class ErrorMappings {
                public static final String[] PAYLOAD = {"claimed"};
            }
            """);

    private static EmittedCodeHarness harness;
    private static Map<String, Method> fetchers;
    private static Class<?> body;

    @BeforeAll
    static void emit() throws Exception {
        var plain = new FrameQuery.Row("Query.count", "Query", Optional.empty(), Optional.empty());
        var outcome = new FrameQuery.Row("Query.lookup", "Query",
            Optional.of(new FrameQuery.Outcome("Payload", "errors")), Optional.empty());
        var child = new FrameQuery.Row("Film.upper", "Film", Optional.empty(), Optional.of(FILM));
        var host = TypeSpec.classBuilder("Fetchers").addModifiers(Modifier.PUBLIC)
            .addMethod(FrameEmitter.render(plain, "count", TypeName.get(Integer.class), BODY, "count", RUNTIME))
            .addMethod(FrameEmitter.render(plain, "failing", TypeName.get(Integer.class), BODY, "fail", RUNTIME))
            .addMethod(FrameEmitter.render(outcome, "lookup", TypeName.get(String.class), BODY, "lookup", RUNTIME))
            .addMethod(FrameEmitter.render(outcome, "refused", TypeName.get(String.class), BODY, "refuse", RUNTIME))
            .addMethod(FrameEmitter.render(child, "upper", TypeName.get(String.class), BODY, "upper", RUNTIME))
            .build();
        var dir = java.nio.file.Files.createTempDirectory("frame");
        var files = new java.util.ArrayList<java.nio.file.Path>();
        files.add(com.palantir.javapoet.JavaFile.builder(PACKAGE, host).build().writeToPath(dir));
        for (var stub : STUBS.entrySet()) {
            var file = dir.resolve(PACKAGE).resolve(stub.getKey() + ".java");
            java.nio.file.Files.writeString(file, "package " + PACKAGE + ";\n" + stub.getValue());
            files.add(file);
        }
        harness = EmittedCodeHarness.compileFiles(files);
        fetchers = new java.util.HashMap<>();
        for (var m : harness.load(PACKAGE + ".Fetchers").getDeclaredMethods()) fetchers.put(m.getName(), m);
        body = harness.load(PACKAGE + ".Body");
    }

    @AfterAll
    static void close() {
        if (harness != null) harness.close();
    }

    private static DataFetchingEnvironment env(Object source, DataLoaderRegistry registry, String path) {
        var step = ExecutionStepInfo.newExecutionStepInfo()
            .type(Scalars.GraphQLString)
            .path(ResultPath.parse(path))
            .build();
        return DataFetchingEnvironmentImpl.newDataFetchingEnvironment()
            .source(source)
            .dataLoaderRegistry(registry)
            .executionStepInfo(step)
            .build();
    }

    private static DataFetcherResult<?> call(String fetcher) throws Exception {
        return (DataFetcherResult<?>) fetchers.get(fetcher).invoke(null, env(null, new DataLoaderRegistry(), "/x"));
    }

    @Test
    void atARootTheFrameHandsBackWhatTheBodyAnswers() throws Exception {
        assertThat(call("count").getData()).isEqualTo(42);
    }

    @Test
    void atARootAThrowIsSurfacedOrRedacted() throws Exception {
        var result = call("failing");
        assertThat(result.getData()).isNull();
        assertThat(result.getErrors()).singleElement()
            .satisfies(e -> assertThat(e.getMessage()).isEqualTo("redacted: body failed"));
    }

    @Test
    void anOutcomeWrapsTheValueAndClaimsWhatItsMappingsClaim() throws Exception {
        assertThat(call("lookup").getData().toString()).isEqualTo("Success(found)");
        assertThat(call("refused").getData().toString()).isEqualTo("ErrorList([claimed refused])");
    }

    @Test
    @SuppressWarnings("unchecked")
    void underAnObjectTheBodyRunsOnceForTheBatchAndEachParentGetsItsValue() throws Exception {
        var registry = new DataLoaderRegistry();
        var futures = new java.util.ArrayList<CompletableFuture<DataFetcherResult<String>>>();
        for (int id : new int[] {3, 1, 2}) {
            var film = new FilmRecord();
            film.set(Tables.FILM.FILM_ID, id);
            futures.add((CompletableFuture<DataFetcherResult<String>>) fetchers.get("upper")
                .invoke(null, env(film, registry, "/films[" + id + "]/upper")));
        }
        registry.dispatchAll();
        assertThat(futures).extracting(f -> f.get().getData()).containsExactly("film 3", "film 1", "film 2");
        assertThat(body.getField("batches").get(null)).isEqualTo(1);
    }
}
