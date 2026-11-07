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
import no.sikt.graphitron.emit.stub.Body;
import no.sikt.graphitron.emit.stub.ErrorMappings;
import no.sikt.graphitron.emit.stub.ErrorRouter;
import no.sikt.graphitron.emit.stub.Outcome;
import no.sikt.graphitron.rewrite.test.compile.EmittedCodeHarness;
import no.sikt.graphitron.rewrite.test.jooq.public_.Tables;
import no.sikt.graphitron.rewrite.test.jooq.public_.tables.records.FilmRecord;
import no.sikt.graphitron.rewrite.test.services.FilmKeySummary;
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
 * under an object the frame reads each parent's key off its row, or off the record the parent's member
 * hands back where the parent holds a plain object, and runs the body once for the batch, each parent
 * getting its own key's value and a parent whose member hands back nothing a null.
 *
 * <p>The body, the jOOQ context and the error runtime are the stubs in
 * {@link no.sikt.graphitron.emit.stub}, standing in for the generated ones; the batching runs through
 * a real {@link DataLoaderRegistry}. Compiled strictly, as a consumer compiles generated sources.
 */
@UnitTier
class FrameEmitterTest {

    private static final String PACKAGE = "framerun";
    private static final ClassName BODY = ClassName.get(Body.class);
    private static final ClassName ROUTER = ClassName.get(ErrorRouter.class);
    private static final ClassName OUTCOME = ClassName.get(Outcome.class);
    private static final ClassName MAPPINGS = ClassName.get(ErrorMappings.class);

    private static final FrameEmitter.Runtime RUNTIME = new FrameEmitter.Runtime(ROUTER, OUTCOME, MAPPINGS,
        type -> type.toUpperCase(), env -> CodeBlock.of("$T.dsl($L)", BODY, env));

    private static final FrameQuery.ParentTable FILM = new FrameQuery.ParentTable("film",
        "no.sikt.graphitron.rewrite.test.jooq.public_.Tables", "FILM",
        List.of(new FrameQuery.KeyColumn("film_id", "FILM_ID", "java.lang.Integer")));

    private static EmittedCodeHarness harness;
    private static Map<String, Method> fetchers;

    @BeforeAll
    static void emit() throws Exception {
        var plain = new FrameQuery.Row("Query.count", "Query", Optional.empty(), Optional.empty());
        var outcome = new FrameQuery.Row("Query.lookup", "Query",
            Optional.of(new FrameQuery.Outcome("Payload", "errors")), Optional.empty());
        var child = new FrameQuery.Row("Film.upper", "Film", Optional.empty(), Optional.of(FILM));
        var throughAccessor = new FrameQuery.Row("FilmKeySummary.upper", "FilmKeySummary", Optional.empty(),
            Optional.of(FILM), Optional.of(new FrameQuery.Accessor(FilmKeySummary.class.getName(), "film",
                FilmRecord.class.getName())));
        var host = TypeSpec.classBuilder("Fetchers").addModifiers(Modifier.PUBLIC)
            .addMethod(FrameEmitter.render(plain, "count", TypeName.get(Integer.class), BODY, "count", RUNTIME))
            .addMethod(FrameEmitter.render(plain, "failing", TypeName.get(Integer.class), BODY, "fail", RUNTIME))
            .addMethod(FrameEmitter.render(outcome, "lookup", TypeName.get(String.class), BODY, "lookup", RUNTIME))
            .addMethod(FrameEmitter.render(outcome, "refused", TypeName.get(String.class), BODY, "refuse", RUNTIME))
            .addMethod(FrameEmitter.render(child, "upper", TypeName.get(String.class), BODY, "upper", RUNTIME))
            .addMethod(FrameEmitter.render(throughAccessor, "summaryUpper", TypeName.get(String.class), BODY, "upper",
                RUNTIME))
            .build();
        var dir = java.nio.file.Files.createTempDirectory("frame");
        harness = EmittedCodeHarness.compileFilesStrictly(
            List.of(com.palantir.javapoet.JavaFile.builder(PACKAGE, host).build().writeToPath(dir)));
        fetchers = new java.util.HashMap<>();
        for (var m : harness.load(PACKAGE + ".Fetchers").getDeclaredMethods()) fetchers.put(m.getName(), m);
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
        Body.batches = 0;
        var futures = new java.util.ArrayList<CompletableFuture<DataFetcherResult<String>>>();
        for (int id : new int[] {3, 1, 2}) {
            var film = new FilmRecord();
            film.set(Tables.FILM.FILM_ID, id);
            futures.add((CompletableFuture<DataFetcherResult<String>>) fetchers.get("upper")
                .invoke(null, env(film, registry, "/films[" + id + "]/upper")));
        }
        registry.dispatchAll();
        assertThat(futures).extracting(f -> f.get().getData()).containsExactly("film 3", "film 1", "film 2");
        assertThat(Body.batches).isEqualTo(1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void underAPlainObjectTheKeyIsReadOffWhatItsMemberHandsBack() throws Exception {
        var registry = new DataLoaderRegistry();
        var futures = new java.util.ArrayList<CompletableFuture<DataFetcherResult<String>>>();
        Body.batches = 0;
        Integer[] ids = {3, null, 1};
        for (int i = 0; i < ids.length; i++) {
            Integer id = ids[i];
            FilmRecord film = null;
            if (id != null) {
                film = new FilmRecord();
                film.set(Tables.FILM.FILM_ID, id);
            }
            futures.add((CompletableFuture<DataFetcherResult<String>>) fetchers.get("summaryUpper")
                .invoke(null, env(new FilmKeySummary(film, "label"), registry, "/summaries[" + i + "]/upper")));
        }
        registry.dispatchAll();
        assertThat(futures.get(0).get().getData()).isEqualTo("film 3");
        assertThat(futures.get(1).get()).isNull();
        assertThat(futures.get(2).get().getData()).isEqualTo("film 1");
        assertThat(Body.batches).isEqualTo(1);
    }
}
