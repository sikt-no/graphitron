package no.sikt.graphitron.emit;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.JavaFile;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.TypeSpec;
import graphql.schema.DataFetchingEnvironment;
import graphql.schema.DataFetchingEnvironmentImpl;
import no.sikt.graphitron.emit.stub.FillRuntime;
import no.sikt.graphitron.model.test.FactGraphs;
import no.sikt.graphitron.rewrite.test.compile.EmittedCodeHarness;
import no.sikt.graphitron.rewrite.test.jooq.public_.Tables;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
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
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link CallEmitter}'s bodies, compiled strictly and run against services that answer without a
 * database, each call and its fills read from the {@code scenarios/service-call-shapes} fact document:
 * at a root a static method, an instance method on an object made with the jOOQ context and one made
 * with nothing, and a checked exception passed through; under an object the batch's keys handed over as
 * the frame's records, as rows, as the parent table's records and as a list in the batch's order,
 * each parent's value read back by its own key, one the service did not answer null.
 */
@PipelineTier
class CallEmitterTest {

    @RegisterExtension
    static final FactGraphs.Reader FACTS = FactGraphs.reader();

    private static final String PACKAGE = "callrun";
    private static final FillEmitter.Runtime RUNTIME = new FillEmitter.Runtime(
        key -> CodeBlock.of("$T.contextArgument(env, $S)", FillRuntime.class, key),
        coordinate -> CodeBlock.of("$T.sessionHandle(dsl, $S)", FillRuntime.class, coordinate),
        (member, coordinate) -> CodeBlock.of("$T.sessionMember_$L(dsl, $S)", FillRuntime.class, member, coordinate));
    private static final TypeName KEY = ParameterizedTypeName.get(ClassName.get(Record1.class), ClassName.get(Integer.class));
    private static final CallEmitter.FrameKey FRAME_KEY = new CallEmitter.FrameKey(KEY, ClassName.get(Tables.class), "FILM");

    private static EmittedCodeHarness harness;
    private static Map<String, Method> bodies;

    /** The bodies, by the name each is emitted under, and the call it is for. */
    private static final Map<String, String> CALLS = Map.of("count", "Query.count", "greet", "Query.greet", "echo", "Query.echo", "byRecord", "Film.byRecord", "byRow", "Film.byRow",
        "byFilm", "Film.byFilm", "inOrder", "Film.inOrder");

    private record Read(List<CallQuery.Row> calls, List<FillQuery.Row> fills) {}

    /**
     * A static method declaring a checked exception, stated here: the generator refuses a declared
     * checked exception no {@code @error} handler covers, and one that is covered is the frame's to
     * route, so no schema that generates states the body passing it through.
     */
    private static final CallQuery.Row FAILING = new CallQuery.Row("Query.failing",
        "no.sikt.graphitron.rewrite.test.services.CallShapeService", "failing", true, Optional.empty(),
        "java.lang.String", Optional.empty());
    private static final FillQuery.Row FAILING_FILLS = new FillQuery.Row("Query.failing", "Query.failing",
        List.of(new FillQuery.Fill(0, "dsl", "org.jooq.DSLContext", "org.jooq.DSLContext", "DSL_CONTEXT", null,
            null, Optional.empty())), true);

    @BeforeAll
    static void compile() throws Exception {
        var read = FACTS.read("scenarios/service-call-shapes",
            graph -> new Read(CallQuery.read(graph.handle()), FillQuery.read(graph.handle())));
        var host = TypeSpec.classBuilder("Bodies").addModifiers(Modifier.PUBLIC);
        for (var entry : CALLS.entrySet()) {
            var call = read.calls().stream().filter(r -> r.coordinate().equals(entry.getValue()))
                .findFirst().orElseThrow();
            var fills = read.fills().stream().filter(r -> r.coordinate().equals(entry.getValue()))
                .findFirst().orElseThrow();
            var result = CallEmitter.body(call, fills, Map.of(), RUNTIME, entry.getKey(),
                call.source().isPresent() ? Optional.of(FRAME_KEY) : Optional.empty(), Optional.empty());
            assertThat(result).as(entry.getKey()).isInstanceOf(CallEmitter.Result.Emitted.class);
            var emitted = (CallEmitter.Result.Emitted) result;
            host.addMethod(emitted.body());
            emitted.helpers().forEach(host::addMethod);
        }
        var failing = (CallEmitter.Result.Emitted) CallEmitter.body(FAILING, FAILING_FILLS, Map.of(), RUNTIME,
            "failing", Optional.empty(), Optional.empty());
        host.addMethod(failing.body());
        failing.helpers().forEach(host::addMethod);
        var dir = Files.createTempDirectory("call");
        harness = EmittedCodeHarness.compileFilesStrictly(
            List.of(JavaFile.builder(PACKAGE, host.build()).build().writeToPath(dir)));
        bodies = new HashMap<>();
        for (var m : harness.load(PACKAGE + ".Bodies").getDeclaredMethods()) bodies.put(m.getName(), m);
    }

    @AfterAll
    static void close() {
        if (harness != null) harness.close();
    }

    private static DataFetchingEnvironment env(Map<String, Object> arguments) {
        return DataFetchingEnvironmentImpl.newDataFetchingEnvironment().arguments(arguments).build();
    }

    private static Object root(String body, Map<String, Object> arguments) throws Throwable {
        try {
            return bodies.get(body).invoke(null, env(arguments), DSL.using(SQLDialect.POSTGRES));
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private static Set<Record1<Integer>> keys(int... ids) {
        var out = new LinkedHashSet<Record1<Integer>>();
        for (int id : ids) {
            out.add(DSL.using(SQLDialect.POSTGRES).newRecord(Tables.FILM.FILM_ID).values(id));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<Record1<Integer>, Object> batch(String body, Set<Record1<Integer>> keys) throws Throwable {
        try {
            return (Map<Record1<Integer>, Object>) bodies.get(body).invoke(null, keys, env(Map.of()),
                DSL.using(SQLDialect.POSTGRES));
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    /** The value the body answered for the key {@code id}, which may be null; "missing" for no key. */
    private static Object valueFor(Map<Record1<Integer>, Object> answered, int id) {
        for (var entry : answered.entrySet()) {
            if (entry.getKey().value1() == id) {
                return entry.getValue();
            }
        }
        return "missing";
    }

    @Test
    void aStaticMethodIsCalledOnItsClass() throws Throwable {
        assertThat(root("count", Map.of())).isEqualTo(42);
    }

    @Test
    void aCheckedExceptionPassesThroughTheBody() {
        assertThatThrownBy(() -> root("failing", Map.of())).hasMessage("checked");
    }

    @Test
    void anInstanceMethodRunsOnAnObjectMadeWithTheContextOrWithNothing() throws Throwable {
        assertThat(root("greet", Map.of("name", "Ada"))).isEqualTo("hello Ada");
        assertThat(root("echo", Map.of("name", "Ada"))).isEqualTo("Ada");
    }

    @Test
    void aBatchTakenAsTheFramesRecordsIsReadBackByKey() throws Throwable {
        var answered = batch("byRecord", keys(1, 2));
        assertThat(valueFor(answered, 1)).isEqualTo("record 1");
        assertThat(valueFor(answered, 2)).isEqualTo("record 2");
    }

    @Test
    void aBatchTakenAsRowsIsReadBackByTheKeysRow() throws Throwable {
        assertThat(valueFor(batch("byRow", keys(3)), 3)).isEqualTo("row 3");
    }

    @Test
    void aBatchTakenAsTheTablesRecordsIsReadBackAndAnUnansweredKeyIsNull() throws Throwable {
        var answered = batch("byFilm", keys(1, 2));
        assertThat(valueFor(answered, 1)).isEqualTo("film 1");
        assertThat(valueFor(answered, 2)).isNull();
    }

    @Test
    void aBatchTakenAsAListIsReadBackByPosition() throws Throwable {
        var answered = batch("inOrder", keys(5, 6));
        assertThat(valueFor(answered, 5)).isEqualTo("at 5");
        assertThat(valueFor(answered, 6)).isEqualTo("at 6");
    }
}
