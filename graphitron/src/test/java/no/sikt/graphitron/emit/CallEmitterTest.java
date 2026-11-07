package no.sikt.graphitron.emit;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.JavaFile;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.TypeSpec;
import graphql.schema.DataFetchingEnvironment;
import graphql.schema.DataFetchingEnvironmentImpl;
import no.sikt.graphitron.emit.stub.Calls;
import no.sikt.graphitron.emit.stub.DslHolder;
import no.sikt.graphitron.emit.stub.FillRuntime;
import no.sikt.graphitron.emit.stub.PlainHolder;
import no.sikt.graphitron.rewrite.test.compile.EmittedCodeHarness;
import no.sikt.graphitron.rewrite.test.jooq.public_.Tables;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.jooq.DSLContext;
import org.jooq.Record1;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

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
 * {@link CallEmitter}'s bodies, compiled strictly and run against the stub services: at a root a
 * static method, an instance method on an object made with the jOOQ context and one made with
 * nothing, and a checked exception passed through; under an object the batch's keys handed over as
 * the frame's records, as rows, as the parent table's records and as a list in the batch's order,
 * each parent's value read back by its own key, one the service did not answer null.
 */
@UnitTier
class CallEmitterTest {

    private static final String PACKAGE = "callrun";
    private static final FillEmitter.Runtime RUNTIME = new FillEmitter.Runtime(
        key -> CodeBlock.of("$T.contextArgument(env, $S)", FillRuntime.class, key),
        coordinate -> CodeBlock.of("$T.sessionHandle(dsl, $S)", FillRuntime.class, coordinate),
        (member, coordinate) -> CodeBlock.of("$T.sessionMember_$L(dsl, $S)", FillRuntime.class, member, coordinate));
    private static final TypeName KEY = ParameterizedTypeName.get(ClassName.get(Record1.class), ClassName.get(Integer.class));
    private static final CallEmitter.FrameKey FRAME_KEY = new CallEmitter.FrameKey(KEY, ClassName.get(Tables.class), "FILM");
    private static final String KEY_SPELLING = "org.jooq.Record1<java.lang.Integer>";

    private static EmittedCodeHarness harness;
    private static Map<String, Method> bodies;

    private static FillQuery.Fill fill(int position, String name, String type, String fill, FillQuery.Landing landing) {
        return new FillQuery.Fill(position, name, type, type, fill, null, null, Optional.ofNullable(landing));
    }

    private static final FillQuery.Fill DSL_FILL = fill(0, "dsl", "org.jooq.DSLContext", "DSL_CONTEXT", null);

    private record Case(String name, CallQuery.Row call, List<FillQuery.Fill> fills, boolean batched) {}

    private static CallQuery.Row call(String name, String className, String method, boolean isStatic,
                                      String constructor, String result, CallQuery.Source source) {
        return new CallQuery.Row("Query." + name, className, method, isStatic, Optional.ofNullable(constructor),
            result, Optional.ofNullable(source));
    }

    private static final List<Case> CASES = List.of(
        new Case("count", call("count", Calls.class.getName(), "count", true, null, "java.lang.Integer", null),
            List.of(DSL_FILL), false),
        new Case("failing", call("failing", Calls.class.getName(), "failing", true, null, "java.lang.String", null),
            List.of(DSL_FILL), false),
        new Case("greet", call("greet", DslHolder.class.getName(), "greet", false, CallQuery.DSL_CONSTRUCTOR,
            "java.lang.String", null),
            List.of(fill(0, "name", "java.lang.String", "ARGUMENT", new FillQuery.Landing(List.of("name"), "SCALAR", false))),
            false),
        new Case("echo", call("echo", PlainHolder.class.getName(), "echo", false, "()V", "java.lang.String", null),
            List.of(fill(0, "name", "java.lang.String", "ARGUMENT", new FillQuery.Landing(List.of("name"), "SCALAR", false))),
            false),
        new Case("byRecord", call("byRecord", Calls.class.getName(), "byRecord", true, null,
            "java.util.Map<" + KEY_SPELLING + ",java.lang.String>", new CallQuery.Source(0, "java.util.Set<" + KEY_SPELLING + ">")),
            List.of(fill(0, "keys", "java.util.Set<" + KEY_SPELLING + ">", "SOURCE", null),
                fill(1, "dsl", "org.jooq.DSLContext", "DSL_CONTEXT", null)), true),
        new Case("byRow", call("byRow", Calls.class.getName(), "byRow", true, null,
            "java.util.Map<org.jooq.Row1<java.lang.Integer>,java.lang.String>",
            new CallQuery.Source(0, "java.util.Set<org.jooq.Row1<java.lang.Integer>>")),
            List.of(fill(0, "keys", "java.util.Set<org.jooq.Row1<java.lang.Integer>>", "SOURCE", null)), true),
        new Case("byFilm", call("byFilm", Calls.class.getName(), "byFilm", true, null,
            "java.util.Map<no.sikt.graphitron.rewrite.test.jooq.public_.tables.records.FilmRecord,java.lang.String>",
            new CallQuery.Source(0, "java.util.Set<no.sikt.graphitron.rewrite.test.jooq.public_.tables.records.FilmRecord>")),
            List.of(fill(0, "films", "java.util.Set<no.sikt.graphitron.rewrite.test.jooq.public_.tables.records.FilmRecord>",
                "SOURCE", null)), true),
        new Case("inOrder", call("inOrder", Calls.class.getName(), "inOrder", true, null, "java.util.List<java.lang.String>",
            new CallQuery.Source(0, "java.util.List<" + KEY_SPELLING + ">")),
            List.of(fill(0, "keys", "java.util.List<" + KEY_SPELLING + ">", "SOURCE", null)), true));

    @BeforeAll
    static void compile() throws Exception {
        var host = TypeSpec.classBuilder("Bodies").addModifiers(Modifier.PUBLIC);
        for (var c : CASES) {
            var fills = new FillQuery.Row(c.call().coordinate(), c.call().coordinate(), c.fills(), true);
            var result = CallEmitter.body(c.call(), fills, Map.of(), RUNTIME, c.name(),
                c.batched() ? Optional.of(FRAME_KEY) : Optional.empty(), Optional.empty());
            assertThat(result).as(c.name()).isInstanceOf(CallEmitter.Result.Emitted.class);
            var emitted = (CallEmitter.Result.Emitted) result;
            host.addMethod(emitted.body());
            emitted.helpers().forEach(host::addMethod);
        }
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
