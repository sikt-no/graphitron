package no.sikt.graphitron.emit;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.TypeSpec;
import graphql.schema.DataFetchingEnvironment;
import graphql.schema.DataFetchingEnvironmentImpl;
import no.sikt.graphitron.emit.stub.FillRuntime;
import no.sikt.graphitron.model.test.FactGraphs;
import no.sikt.graphitron.rewrite.generators.schema.GraphitronClientExceptionClassGenerator;
import no.sikt.graphitron.rewrite.generators.util.ColumnValuesClassGenerator;
import no.sikt.graphitron.rewrite.generators.util.NodeIdEncoderClassGenerator;
import no.sikt.graphitron.rewrite.test.compile.EmittedCodeHarness;
import no.sikt.graphitron.rewrite.test.jooq.public_.tables.records.FilmRecord;
import no.sikt.graphitron.rewrite.test.jooq.public_.tables.records.InventoryRecord;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.jooq.DSLContext;
import org.jooq.Record1;
import org.jooq.Row1;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import javax.lang.model.element.Modifier;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link FillEmitter}'s node id arguments, compiled strictly with the generated {@code NodeIdEncoder}
 * and run: an id decoded into the parameter's own record, into another table's record through its
 * foreign key, into the node's key as a {@code Record1} and a {@code Row1}, and a list and a set of
 * ids decoded one by one, a null element staying null and an invalid id refused as a client error.
 *
 * <p>The decodes are read from the {@code scenarios/node-id-decode-record} fact document, whose Film
 * carries the type id F. The calls are stated here: the store refuses every one of these parameters
 * at a service argument but the node's own record, and the generator refuses that one too, so no
 * schema that generates states them.
 */
@PipelineTier
class NodeIdFillEmitterTest {

    @RegisterExtension
    static final FactGraphs.Reader FACTS = FactGraphs.reader();

    private static final String PACKAGE = "nodefill";
    private static final String FILM_RECORD = FilmRecord.class.getName();
    private static final ClassName ENCODER = ClassName.get(PACKAGE + ".util", NodeIdEncoderClassGenerator.CLASS_NAME);
    private static final FillEmitter.Runtime RUNTIME = new FillEmitter.Runtime(
        key -> CodeBlock.of("$T.contextArgument(env, $S)", FillRuntime.class, key),
        coordinate -> CodeBlock.of("$T.sessionHandle(dsl, $S)", FillRuntime.class, coordinate),
        (member, coordinate) -> CodeBlock.of("$T.sessionMember_$L(dsl, $S)", FillRuntime.class, member, coordinate),
        new NodeIdDecodeEmitter.Runtime(ENCODER, PACKAGE));

    private static EmittedCodeHarness harness;
    private static Map<String, Method> probes;
    private static Method encode;

    /** A probe: the call's fill of its one node id argument, and the decode it is built with. */
    private record Case(String name, FillQuery.Row row, BuildQuery.Result.Decoded decoded) {}

    private static Case stated(String name, String type, NodeIdDecodeQuery.Decode decode, String shape) {
        var row = new FillQuery.Row("Query." + name, "Query." + name, List.of(new FillQuery.Fill(0, "id",
            type, type, "ARGUMENT", null, null, Optional.of(new FillQuery.Landing(List.of("id"), "SCALAR", true)))),
            true);
        return new Case(name, row, new BuildQuery.Result.Decoded(decode, shape));
    }

    @BeforeAll
    static void compile() throws Exception {
        var decodes = FACTS.read("scenarios/node-id-decode-record", graph -> NodeIdDecodeQuery.read(graph.handle()));
        var film = ((NodeIdDecodeQuery.Resolution.Resolved)
            decodes.resolve("Query.inventories(filmId:)", FILM_RECORD)).decode();
        var inventoryFilm = ((NodeIdDecodeQuery.Resolution.Resolved)
            decodes.resolve("Query.inventories(filmId:)", InventoryRecord.class.getName())).decode();
        var cases = List.of(
            stated("film", FILM_RECORD, film, "RECORD"),
            stated("inventory", InventoryRecord.class.getName(), inventoryFilm, "RECORD"),
            stated("key", "org.jooq.Record1<java.lang.Integer>", film, "RECORD_N"),
            stated("row", "org.jooq.Row1<java.lang.Integer>", film, "ROW_N"),
            stated("keys", "java.util.List<org.jooq.Record1<java.lang.Integer>>", film, "RECORD_N"),
            stated("films", "java.util.Set<" + FILM_RECORD + ">", film, "RECORD"));
        var host = TypeSpec.classBuilder("Probes").addModifiers(Modifier.PUBLIC);
        for (var c : cases) {
            var emitted = (FillEmitter.Result.Emitted) FillEmitter.emit(c.row(), Optional.empty(), RUNTIME,
                Map.of(0, c.decoded()));
            var method = MethodSpec.methodBuilder(c.name()).addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .returns(Object.class)
                .addParameter(ClassName.get(DataFetchingEnvironment.class), "env")
                .addParameter(ClassName.get(DSLContext.class), "dsl");
            emitted.statements().forEach(method::addStatement);
            method.addStatement("return $L", emitted.arguments().getFirst());
            host.addMethod(method.build());
            emitted.helpers().forEach(host::addMethod);
        }
        var units = new HashMap<String, TypeSpec>();
        units.put(PACKAGE + ".Probes", host.build());
        for (TypeSpec spec : NodeIdEncoderClassGenerator.generate(List.of(), PACKAGE, List.of())) {
            units.put(PACKAGE + ".util." + spec.name(), spec);
        }
        for (TypeSpec spec : GraphitronClientExceptionClassGenerator.generate()) {
            units.put(PACKAGE + ".schema." + spec.name(), spec);
        }
        for (TypeSpec spec : ColumnValuesClassGenerator.generate()) {
            units.put(PACKAGE + ".util." + spec.name(), spec);
        }
        harness = EmittedCodeHarness.compileStrictly(units);
        probes = new HashMap<>();
        for (var m : harness.load(PACKAGE + ".Probes").getDeclaredMethods()) probes.put(m.getName(), m);
        encode = harness.load(ENCODER.reflectionName()).getMethod("encode", String.class, Object[].class);
    }

    @AfterAll
    static void close() {
        if (harness != null) harness.close();
    }

    private static String id(String typeId, int value) throws Exception {
        return (String) encode.invoke(null, typeId, new Object[] {value});
    }

    private static Object run(String probe, Object id) throws Throwable {
        var arguments = new HashMap<String, Object>();
        if (id != null) arguments.put("id", id);
        DataFetchingEnvironment env = DataFetchingEnvironmentImpl.newDataFetchingEnvironment().arguments(arguments).build();
        try {
            return probes.get(probe).invoke(null, env, DSL.using(SQLDialect.POSTGRES));
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    @Test
    void anIdDecodesIntoTheParametersOwnRecord() throws Throwable {
        assertThat(((FilmRecord) run("film", id("F", 7))).getFilmId()).isEqualTo(7);
    }

    @Test
    void anIdDecodesIntoAnotherTablesRecordThroughItsForeignKey() throws Throwable {
        assertThat(((InventoryRecord) run("inventory", id("F", 7))).getFilmId()).isEqualTo(7);
    }

    @Test
    void anIdIsTakenAsTheKeysRecordOrRow() throws Throwable {
        assertThat(((Record1<?>) run("key", id("F", 7))).value1()).isEqualTo(7);
        assertThat(((Row1<?>) run("row", id("F", 7))).field1().toString()).contains("7");
    }

    @Test
    void aListOfIdsDecodesEachAndKeepsANullElement() throws Throwable {
        var ids = new ArrayList<>(Arrays.asList(id("F", 7), null, id("F", 8)));
        var keys = (List<?>) run("keys", ids);
        assertThat(keys).hasSize(3);
        assertThat(((Record1<?>) keys.get(0)).value1()).isEqualTo(7);
        assertThat(keys.get(1)).isNull();
        assertThat(((Record1<?>) keys.get(2)).value1()).isEqualTo(8);
    }

    @Test
    void aSetOfIdsDecodesIntoASetOfRecords() throws Throwable {
        var films = (Set<?>) run("films", List.of(id("F", 7), id("F", 8)));
        assertThat(films).extracting(f -> ((FilmRecord) f).getFilmId()).containsExactlyInAnyOrder(7, 8);
    }

    @Test
    void anAbsentIdIsNullAndAnInvalidOneRefused() throws Throwable {
        assertThat(run("film", null)).isNull();
        assertThatThrownBy(() -> run("film", id("Actor", 7)))
            .satisfies(e -> assertThat(e.getClass().getSimpleName()).isEqualTo("GraphitronClientException"))
            .hasMessageEndingWith("decodes to type \"Actor\", expected a Film id");
    }
}
