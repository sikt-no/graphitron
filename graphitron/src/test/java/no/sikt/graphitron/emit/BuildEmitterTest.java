package no.sikt.graphitron.emit;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.JavaFile;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.TypeSpec;
import graphql.schema.DataFetchingEnvironment;
import graphql.schema.DataFetchingEnvironmentImpl;
import no.sikt.graphitron.emit.stub.FillRuntime;
import no.sikt.graphitron.model.test.FactGraphs;
import no.sikt.graphitron.rewrite.test.compile.EmittedCodeHarness;
import no.sikt.graphitron.rewrite.test.services.FilmBundleDraft;
import no.sikt.graphitron.rewrite.test.services.FilmDraft;
import no.sikt.graphitron.rewrite.test.services.FilmMood;
import no.sikt.graphitron.rewrite.test.services.FilmSurvey;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.jooq.DSLContext;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link BuildEmitter}'s helpers, through the fills that call them, compiled strictly and run: an
 * input object made into a record by its constructor, a list of them into a list, a grouping's field
 * filling the enclosing record's member, and a class made by its setters, a setter called only for a
 * field the client sent, a primitive and an enum among them. Each call and what its argument is built
 * into are read from the {@code scenarios/service-input-build} fact document.
 */
@PipelineTier
class BuildEmitterTest {

    @RegisterExtension
    static final FactGraphs.Reader FACTS = FactGraphs.reader();

    private static final String PACKAGE = "buildrun";
    private static final FillEmitter.Runtime RUNTIME = new FillEmitter.Runtime(
        key -> CodeBlock.of("$T.contextArgument(env, $S)", FillRuntime.class, key),
        coordinate -> CodeBlock.of("$T.sessionHandle(dsl, $S)", FillRuntime.class, coordinate),
        (member, coordinate) -> CodeBlock.of("$T.sessionMember_$L(dsl, $S)", FillRuntime.class, member, coordinate));

    private static EmittedCodeHarness harness;
    private static Map<String, Method> probes;

    @BeforeAll
    static void emit() throws Exception {
        var read = FACTS.read("scenarios/service-input-build",
            graph -> new Read(FillQuery.read(graph.handle()), BuildQuery.read(graph.handle())));
        var host = TypeSpec.classBuilder("Probes").addModifiers(Modifier.PUBLIC);
        probe(host, "drafts", read, "Query.countFilmDrafts");
        probe(host, "bundle", read, "Query.countFilmBundleDraft");
        probe(host, "bean", read, "Query.countFilmSurvey");
        var dir = Files.createTempDirectory("build");
        harness = EmittedCodeHarness.compileFilesStrictly(
            List.of(JavaFile.builder(PACKAGE, host.build()).build().writeToPath(dir)));
        probes = new HashMap<>();
        for (var m : harness.load(PACKAGE + ".Probes").getDeclaredMethods()) probes.put(m.getName(), m);
    }

    /** What the document states: each call's fills, and what each argument is built into. */
    private record Read(List<FillQuery.Row> calls, Map<BuildQuery.Root, BuildQuery.Result> builds) {}

    /** A probe with the call's signature, its argument built as the store says, and the helpers. */
    private static void probe(TypeSpec.Builder host, String name, Read read, String call) {
        var row = read.calls().stream().filter(r -> r.coordinate().equals(call)).findFirst().orElseThrow();
        var builds = new HashMap<Integer, BuildQuery.Result>();
        read.builds().forEach((root, result) -> {
            if (root.callCoordinate().equals(call)) builds.put(root.position(), result);
        });
        var emitted = (FillEmitter.Result.Emitted) FillEmitter.emit(row, Optional.empty(), RUNTIME, builds);
        var method = MethodSpec.methodBuilder(name)
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(Object.class)
            .addParameter(ClassName.get(DataFetchingEnvironment.class), "env")
            .addParameter(ClassName.get(DSLContext.class), "dsl");
        emitted.statements().forEach(method::addStatement);
        method.addStatement("return $L", emitted.arguments().getFirst());
        host.addMethod(method.build());
        emitted.helpers().forEach(host::addMethod);
    }

    @AfterAll
    static void close() {
        if (harness != null) harness.close();
    }

    private static Object run(String probe, Object in) throws Exception {
        var arguments = new HashMap<String, Object>();
        if (in != null) arguments.put("in", in);
        DataFetchingEnvironment env = DataFetchingEnvironmentImpl.newDataFetchingEnvironment()
            .arguments(arguments).build();
        return probes.get(probe).invoke(null, env, DSL.using(SQLDialect.POSTGRES));
    }

    @Test
    void aListOfInputObjectsIsMadeIntoAListOfRecords() throws Exception {
        assertThat(run("drafts", List.of(Map.of("title", "a"), Map.of("title", "b"))))
            .isEqualTo(List.of(new FilmDraft("a"), new FilmDraft("b")));
    }

    @Test
    void aGroupingsFieldAndANestedListFillTheEnclosingRecord() throws Exception {
        assertThat(run("bundle", Map.of("name", "box", "limits", Map.of("capacity", 3),
                "films", List.of(Map.of("title", "a")))))
            .isEqualTo(new FilmBundleDraft("box", 3, List.of(new FilmDraft("a"))));
    }

    @Test
    void anAbsentGroupingLeavesItsFieldsNull() throws Exception {
        assertThat(run("bundle", Map.of("name", "box"))).isEqualTo(new FilmBundleDraft("box", null, null));
    }

    @Test
    void aClassMadeBySettersIsFilledOnlyForWhatTheClientSent() throws Exception {
        var bean = (FilmSurvey) run("bean", Map.of("count", 4, "mood", "SAD"));
        assertThat(bean.getCount()).isEqualTo(4);
        assertThat(bean.getMood()).isEqualTo(FilmMood.SAD);
        assertThat(bean.getNote()).isEqualTo("untouched");
    }

    @Test
    void anAbsentInputObjectIsNull() throws Exception {
        assertThat(run("bean", null)).isNull();
    }

    @Test
    void aNullElementOfAListIsRefused() {
        assertThatThrownBy(() -> run("drafts", java.util.Arrays.asList(Map.of("title", "a"), null)))
            .hasRootCauseInstanceOf(IllegalArgumentException.class)
            .rootCause().hasMessageEndingWith("null element not allowed in list argument");
    }

}
