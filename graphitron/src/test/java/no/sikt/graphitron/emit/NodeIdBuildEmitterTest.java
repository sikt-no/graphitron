package no.sikt.graphitron.emit;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.TypeSpec;
import no.sikt.graphitron.emit.stub.Stock;
import no.sikt.graphitron.model.test.FactGraphs;
import no.sikt.graphitron.rewrite.TestSchemaHelper;
import no.sikt.graphitron.rewrite.generators.schema.GraphitronClientExceptionClassGenerator;
import no.sikt.graphitron.rewrite.generators.util.ColumnValuesClassGenerator;
import no.sikt.graphitron.rewrite.generators.util.NodeIdEncoderClassGenerator;
import no.sikt.graphitron.rewrite.test.compile.EmittedCodeHarness;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import javax.lang.model.element.Modifier;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link BuildEmitter}'s node id members, compiled strictly with the generated {@code NodeIdEncoder}
 * and run: a node id decoded into a record of its own table, two node ids of different types decoded
 * into one record through its foreign keys, two that disagree on a column failing the request, an
 * invalid id refused, and a member no id fills left null.
 *
 * <p>Each decode is read from the {@code scenarios/node-id-decode-record} fact document, whose Film
 * carries the type id F. The build itself is stated here: the generator refuses two input fields
 * binding one member, so no schema that generates states three ids filling one inventory.
 */
@PipelineTier
class NodeIdBuildEmitterTest {

    @RegisterExtension
    static final FactGraphs.Reader FACTS = FactGraphs.reader();

    private static final String PACKAGE = "nodebuild";
    private static final String RECORDS = "no.sikt.graphitron.rewrite.test.jooq.public_.tables.records.";
    private static final ClassName ENCODER = ClassName.get(PACKAGE + ".util", NodeIdEncoderClassGenerator.CLASS_NAME);
    private static final NodeIdDecodeEmitter.Runtime RUNTIME = new NodeIdDecodeEmitter.Runtime(ENCODER, PACKAGE);

    private static EmittedCodeHarness harness;
    private static Method build;
    private static Method encode;

    /** A member filled by decoding {@code field}'s id as {@code decode} says. */
    private static BuildQuery.Member decoding(String slotName, int position, NodeIdDecodeQuery.Decode decode,
                                              String field) {
        var slot = new BuildQuery.Slot("<init>", "()V", position, slotName, decode.recordClass(),
            decode.recordClass(), null);
        return new BuildQuery.Member(slot, List.of(field), "SCALAR", false, Optional.empty(), Optional.of(decode));
    }

    private static NodeIdDecodeQuery.Decode resolved(NodeIdDecodeQuery decodes, String coordinate, String record) {
        return ((NodeIdDecodeQuery.Resolution.Resolved) decodes.resolve(coordinate, record)).decode();
    }

    @BeforeAll
    static void compile() throws Exception {
        var decodes = FACTS.read("scenarios/node-id-decode-record", graph -> NodeIdDecodeQuery.read(graph.handle()));
        var film = decoding("film", 0, resolved(decodes, "Query.inventories(filmId:)", RECORDS + "FilmRecord"), "filmId");
        var inventoryFilm = resolved(decodes, "Query.inventories(filmId:)", RECORDS + "InventoryRecord");
        var stockFilm = decoding("inventory", 1, inventoryFilm, "stockFilmId");
        var stockStore = decoding("inventory", 1, resolved(decodes, "Store.id", RECORDS + "InventoryRecord"), "storeId");
        var otherFilm = decoding("inventory", 1, inventoryFilm, "otherFilmId");
        var stock = new BuildQuery.Build(Stock.class.getName(), "POSITIONAL", "()V",
            List.of(film.slot(), stockFilm.slot()), List.of(film, stockFilm, stockStore, otherFilm));
        var host = TypeSpec.classBuilder("Probes").addModifiers(Modifier.PUBLIC);
        // The helpers are private; a public entry calls the first so the test can reach it.
        BuildEmitter.helpers(stock, "buildStock", RUNTIME).forEach(host::addMethod);
        host.addMethod(MethodSpec.methodBuilder("probe").addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(Stock.class)
            .addParameter(com.palantir.javapoet.ParameterizedTypeName.get(ClassName.get(Map.class),
                com.palantir.javapoet.WildcardTypeName.subtypeOf(Object.class),
                com.palantir.javapoet.WildcardTypeName.subtypeOf(Object.class)), "in")
            .addStatement("return buildStock(in)").build());
        var schema = TestSchemaHelper.buildSchema("type Query { x: Int }");
        var units = new HashMap<String, TypeSpec>();
        units.put(PACKAGE + ".Probes", host.build());
        for (TypeSpec spec : NodeIdEncoderClassGenerator.generate(schema, PACKAGE, List.of())) {
            units.put(PACKAGE + ".util." + spec.name(), spec);
        }
        for (TypeSpec spec : GraphitronClientExceptionClassGenerator.generate()) {
            units.put(PACKAGE + ".schema." + spec.name(), spec);
        }
        for (TypeSpec spec : ColumnValuesClassGenerator.generate()) {
            units.put(PACKAGE + ".util." + spec.name(), spec);
        }
        harness = EmittedCodeHarness.compileStrictly(units);
        build = harness.load(PACKAGE + ".Probes").getMethod("probe", Map.class);
        encode = harness.load(ENCODER.reflectionName()).getMethod("encode", String.class, Object[].class);
    }

    @AfterAll
    static void close() {
        if (harness != null) harness.close();
    }

    private static String id(String typeId, Object value) throws Exception {
        return (String) encode.invoke(null, typeId, new Object[] {value});
    }

    private static Stock run(Map<String, Object> in) throws Throwable {
        try {
            return (Stock) build.invoke(null, in);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    @Test
    void anIdDecodesIntoARecordOfItsOwnTable() throws Throwable {
        assertThat(run(Map.of("filmId", id("F", 7))).film().getFilmId()).isEqualTo(7);
    }

    @Test
    void twoIdsFillOneRecordThroughItsForeignKeys() throws Throwable {
        var inventory = run(Map.of("stockFilmId", id("F", 7), "storeId", id("Store", 2))).inventory();
        assertThat(inventory.getFilmId()).isEqualTo(7);
        assertThat(inventory.getStoreId()).isEqualTo(2);
    }

    @Test
    void idsThatAgreeOnAColumnShareIt() throws Throwable {
        assertThat(run(Map.of("stockFilmId", id("F", 7), "otherFilmId", id("F", 7))).inventory().getFilmId())
            .isEqualTo(7);
    }

    @Test
    void idsThatDisagreeOnAColumnFailTheRequest() {
        assertThatThrownBy(() -> run(Map.of("stockFilmId", id("F", 7), "otherFilmId", id("F", 8))))
            .hasMessageContaining("otherFilmId");
    }

    @Test
    void anInvalidIdIsRefusedAsAClientErrorNamingWhatItDecodesTo() {
        assertThatThrownBy(() -> run(Map.of("filmId", id("Actor", 7))))
            .satisfies(e -> assertThat(e.getClass().getSimpleName()).isEqualTo("GraphitronClientException"))
            .hasMessageEndingWith("decodes to type \"Actor\", expected a Film id");
    }

    @Test
    void aMemberNoIdFillsIsNull() throws Throwable {
        var stock = run(Map.of());
        assertThat(stock.film()).isNull();
        assertThat(stock.inventory()).isNull();
    }
}
