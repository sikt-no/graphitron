package no.sikt.graphitron.emit;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.JavaFile;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.TypeSpec;
import graphql.schema.DataFetchingEnvironment;
import graphql.schema.DataFetchingEnvironmentImpl;
import no.sikt.graphitron.emit.stub.Bean;
import no.sikt.graphitron.emit.stub.Bundle;
import no.sikt.graphitron.emit.stub.Draft;
import no.sikt.graphitron.emit.stub.FillRuntime;
import no.sikt.graphitron.emit.stub.Mood;
import no.sikt.graphitron.rewrite.test.compile.EmittedCodeHarness;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.lang.model.element.Modifier;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link BuildEmitter}'s helpers, through the fills that call them, compiled strictly and run: an
 * input object made into a record by its constructor, a list of them into a list, a grouping's field
 * filling the enclosing record's member, and a class made by its setters, a setter called only for a
 * field the client sent, a primitive and an enum among them.
 */
@UnitTier
class BuildEmitterTest {

    private static final String PACKAGE = "buildrun";
    private static final String STUB = "no.sikt.graphitron.emit.stub.";
    private static final FillEmitter.Runtime RUNTIME = new FillEmitter.Runtime(
        key -> CodeBlock.of("$T.contextArgument(env, $S)", FillRuntime.class, key),
        coordinate -> CodeBlock.of("$T.sessionHandle(dsl, $S)", FillRuntime.class, coordinate),
        (member, coordinate) -> CodeBlock.of("$T.sessionMember_$L(dsl, $S)", FillRuntime.class, member, coordinate));

    private static EmittedCodeHarness harness;
    private static Map<String, Method> probes;

    private static BuildQuery.Slot slot(String method, int position, String name, String type) {
        return new BuildQuery.Slot(method, "()V", position, name, type, null, null);
    }

    private static BuildQuery.Member member(BuildQuery.Slot slot, List<String> path, String kind, boolean list,
                                            BuildQuery.Build nested) {
        return new BuildQuery.Member(slot, path, kind, list, Optional.ofNullable(nested));
    }

    private static final BuildQuery.Slot TITLE = slot("<init>", 0, "title", "java.lang.String");
    private static final BuildQuery.Build DRAFT = new BuildQuery.Build(STUB + "Draft", "POSITIONAL", "(Ljava/lang/String;)V",
        List.of(TITLE), List.of(member(TITLE, List.of("title"), "SCALAR", false, null)));

    private static final BuildQuery.Slot NAME = slot("<init>", 0, "name", "java.lang.String");
    private static final BuildQuery.Slot CAPACITY = slot("<init>", 1, "capacity", "java.lang.Integer");
    private static final BuildQuery.Slot FILMS = slot("<init>", 2, "films", "java.util.List<" + STUB + "Draft>");
    private static final BuildQuery.Build BUNDLE = new BuildQuery.Build(STUB + "Bundle", "POSITIONAL", "()V",
        List.of(NAME, CAPACITY, FILMS), List.of(
            member(NAME, List.of("name"), "SCALAR", false, null),
            member(CAPACITY, List.of("limits", "capacity"), "SCALAR", false, null),
            member(FILMS, List.of("films"), "INPUT_OBJECT", true, DRAFT)));

    private static final BuildQuery.Slot COUNT = slot("setCount", 0, "count", "int");
    private static final BuildQuery.Slot MOOD = slot("setMood", 0, "mood", STUB + "Mood");
    private static final BuildQuery.Slot NOTE = slot("setNote", 0, "note", "java.lang.String");
    private static final BuildQuery.Build BEAN = new BuildQuery.Build(STUB + "Bean", "SETTERS", "()V",
        List.of(COUNT, MOOD, NOTE), List.of(
            member(COUNT, List.of("count"), "SCALAR", false, null),
            member(MOOD, List.of("mood"), "ENUM", false, null),
            member(NOTE, List.of("note"), "SCALAR", false, null)));

    private static FillQuery.Row call(String coordinate, String type, boolean list) {
        return new FillQuery.Row(coordinate, coordinate, List.of(new FillQuery.Fill(0, "in", type,
            list ? "java.util.List" : type, "ARGUMENT", null, null,
            Optional.of(new FillQuery.Landing(List.of("in"), "INPUT_OBJECT", false)))), true);
    }

    @BeforeAll
    static void emit() throws Exception {
        var host = TypeSpec.classBuilder("Probes").addModifiers(Modifier.PUBLIC);
        probe(host, "drafts", call("Query.drafts", "java.util.List<" + STUB + "Draft>", true), DRAFT);
        probe(host, "bundle", call("Query.bundle", STUB + "Bundle", false), BUNDLE);
        probe(host, "bean", call("Query.bean", STUB + "Bean", false), BEAN);
        var dir = Files.createTempDirectory("build");
        harness = EmittedCodeHarness.compileFilesStrictly(
            List.of(JavaFile.builder(PACKAGE, host.build()).build().writeToPath(dir)));
        probes = new HashMap<>();
        for (var m : harness.load(PACKAGE + ".Probes").getDeclaredMethods()) probes.put(m.getName(), m);
    }

    /** A probe with the body's signature, its fills built as {@code build} says, and their helpers. */
    private static void probe(TypeSpec.Builder host, String name, FillQuery.Row row, BuildQuery.Build build) {
        var emitted = (FillEmitter.Result.Emitted) FillEmitter.emit(row, Optional.empty(), RUNTIME,
            Map.of(0, new BuildQuery.Result.Built(build)));
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
            .isEqualTo(List.of(new Draft("a"), new Draft("b")));
    }

    @Test
    void aGroupingsFieldAndANestedListFillTheEnclosingRecord() throws Exception {
        assertThat(run("bundle", Map.of("name", "box", "limits", Map.of("capacity", 3),
                "films", List.of(Map.of("title", "a")))))
            .isEqualTo(new Bundle("box", 3, List.of(new Draft("a"))));
    }

    @Test
    void anAbsentGroupingLeavesItsFieldsNull() throws Exception {
        assertThat(run("bundle", Map.of("name", "box"))).isEqualTo(new Bundle("box", null, null));
    }

    @Test
    void aClassMadeBySettersIsFilledOnlyForWhatTheClientSent() throws Exception {
        var bean = (Bean) run("bean", Map.of("count", 4, "mood", "SAD"));
        assertThat(bean.getCount()).isEqualTo(4);
        assertThat(bean.getMood()).isEqualTo(Mood.SAD);
        assertThat(bean.getNote()).isEqualTo("untouched");
    }

    @Test
    void anAbsentInputObjectIsNull() throws Exception {
        assertThat(run("bean", null)).isNull();
    }

}
