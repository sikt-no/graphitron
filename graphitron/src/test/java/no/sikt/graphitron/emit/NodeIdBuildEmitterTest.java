package no.sikt.graphitron.emit;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.TypeSpec;
import no.sikt.graphitron.emit.stub.Stock;
import no.sikt.graphitron.rewrite.TestSchemaHelper;
import no.sikt.graphitron.rewrite.generators.util.ColumnValuesClassGenerator;
import no.sikt.graphitron.rewrite.generators.util.NodeIdEncoderClassGenerator;
import no.sikt.graphitron.rewrite.test.compile.EmittedCodeHarness;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

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
 */
@UnitTier
class NodeIdBuildEmitterTest {

    private static final String PACKAGE = "nodebuild";
    private static final String TABLES = "no.sikt.graphitron.rewrite.test.jooq.public_.Tables";
    private static final String RECORDS = "no.sikt.graphitron.rewrite.test.jooq.public_.tables.records.";
    private static final ClassName ENCODER = ClassName.get(PACKAGE + ".util", NodeIdEncoderClassGenerator.CLASS_NAME);
    private static final BuildEmitter.Runtime RUNTIME = new BuildEmitter.Runtime(ENCODER,
        label -> CodeBlock.of("throw new $T($S)", IllegalArgumentException.class, "invalid id for " + label));

    private static EmittedCodeHarness harness;
    private static Method build;
    private static Method encode;

    private static BuildQuery.Member decoding(String slotName, int position, String recordClass, String typeId,
                                              String tableField, String column, String field) {
        var slot = new BuildQuery.Slot("<init>", "()V", position, slotName, recordClass, recordClass, null);
        return new BuildQuery.Member(slot, List.of(field), "SCALAR", false, Optional.empty(),
            Optional.of(new BuildQuery.Decode(typeId, TABLES, tableField, recordClass, List.of(column))));
    }

    @BeforeAll
    static void compile() throws Exception {
        var film = decoding("film", 0, RECORDS + "FilmRecord", "Film", "FILM", "FILM_ID", "filmId");
        var stockFilm = decoding("inventory", 1, RECORDS + "InventoryRecord", "Film", "INVENTORY", "FILM_ID", "stockFilmId");
        var stockStore = decoding("inventory", 1, RECORDS + "InventoryRecord", "Store", "INVENTORY", "STORE_ID", "storeId");
        var otherFilm = decoding("inventory", 1, RECORDS + "InventoryRecord", "Film", "INVENTORY", "FILM_ID", "otherFilmId");
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
        assertThat(run(Map.of("filmId", id("Film", 7))).film().getFilmId()).isEqualTo(7);
    }

    @Test
    void twoIdsFillOneRecordThroughItsForeignKeys() throws Throwable {
        var inventory = run(Map.of("stockFilmId", id("Film", 7), "storeId", id("Store", 2))).inventory();
        assertThat(inventory.getFilmId()).isEqualTo(7);
        assertThat(inventory.getStoreId()).isEqualTo(2);
    }

    @Test
    void idsThatAgreeOnAColumnShareIt() throws Throwable {
        assertThat(run(Map.of("stockFilmId", id("Film", 7), "otherFilmId", id("Film", 7))).inventory().getFilmId())
            .isEqualTo(7);
    }

    @Test
    void idsThatDisagreeOnAColumnFailTheRequest() {
        assertThatThrownBy(() -> run(Map.of("stockFilmId", id("Film", 7), "otherFilmId", id("Film", 8))))
            .hasMessageContaining("otherFilmId");
    }

    @Test
    void anInvalidIdIsRefusedForItsField() {
        assertThatThrownBy(() -> run(Map.of("filmId", id("Actor", 7))))
            .hasMessage("invalid id for filmId");
    }

    @Test
    void aMemberNoIdFillsIsNull() throws Throwable {
        var stock = run(Map.of());
        assertThat(stock.film()).isNull();
        assertThat(stock.inventory()).isNull();
    }
}
