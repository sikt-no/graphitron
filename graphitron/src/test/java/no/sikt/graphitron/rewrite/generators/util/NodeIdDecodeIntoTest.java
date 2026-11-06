package no.sikt.graphitron.rewrite.generators.util;

import com.palantir.javapoet.TypeSpec;
import no.sikt.graphitron.rewrite.TestSchemaHelper;
import no.sikt.graphitron.rewrite.test.compile.EmittedCodeHarness;
import no.sikt.graphitron.rewrite.test.jooq.public_.Tables;
import no.sikt.graphitron.rewrite.test.jooq.public_.tables.records.FilmRecord;
import no.sikt.graphitron.rewrite.test.jooq.public_.tables.records.InventoryRecord;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.jooq.Field;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The generated {@code NodeIdEncoder.decodeInto}, compiled and run against the sakila catalog's own
 * records: an id's key decoded into its node type's key column and, as readily, into a column of
 * another table referencing it; an id of another type refused; and a record two writers fill agreeing
 * on a column both set, and failing the request where they do not.
 */
@UnitTier
class NodeIdDecodeIntoTest {

    private static final String PACKAGE = "decodeinto";

    private static EmittedCodeHarness harness;
    private static Method encode;
    private static Method decodeInto;

    @BeforeAll
    static void compile() throws Exception {
        var schema = TestSchemaHelper.buildSchema("type Query { x: Int }");
        var units = new HashMap<String, TypeSpec>();
        for (TypeSpec spec : NodeIdEncoderClassGenerator.generate(schema, PACKAGE, List.of())) {
            units.put(PACKAGE + ".util." + spec.name(), spec);
        }
        for (TypeSpec spec : ColumnValuesClassGenerator.generate()) {
            units.put(PACKAGE + ".util." + spec.name(), spec);
        }
        harness = EmittedCodeHarness.compileStrictly(units);
        var encoder = harness.load(PACKAGE + ".util." + NodeIdEncoderClassGenerator.CLASS_NAME);
        encode = encoder.getMethod("encode", String.class, Object[].class);
        decodeInto = encoder.getMethod("decodeInto", org.jooq.Record.class, String.class, String.class,
            String.class, Class[].class, Field[].class);
    }

    @AfterAll
    static void close() {
        if (harness != null) harness.close();
    }

    private static String id(String typeId, Object... values) throws Exception {
        return (String) encode.invoke(null, typeId, values);
    }

    private static Object decode(org.jooq.Record into, String typeId, String id, Field<?>... columns) throws Throwable {
        try {
            return decodeInto.invoke(null, into, typeId, id, "filmId", null, columns);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    @Test
    void anIdDecodesIntoItsNodeTypesKeyColumn() throws Throwable {
        var film = (FilmRecord) decode(new FilmRecord(), "Film", id("Film", 7), Tables.FILM.FILM_ID);
        assertThat(film.getFilmId()).isEqualTo(7);
        assertThat(film.touched(Tables.FILM.FILM_ID)).isTrue();
        assertThat(film.touched(Tables.FILM.TITLE)).isFalse();
    }

    @Test
    void anIdDecodesIntoAColumnOfAnotherTableReferencingTheKey() throws Throwable {
        var inventory = (InventoryRecord) decode(new InventoryRecord(), "Film", id("Film", 7), Tables.INVENTORY.FILM_ID);
        assertThat(inventory.getFilmId()).isEqualTo(7);
    }

    @Test
    void anIdOfAnotherTypeIsRefused() throws Throwable {
        assertThat(decode(new FilmRecord(), "Film", id("Actor", 7), Tables.FILM.FILM_ID)).isNull();
    }

    @Test
    void writersThatAgreeOnAColumnShareTheRecord() throws Throwable {
        var inventory = new InventoryRecord();
        inventory.setFilmId(7);
        assertThat(decode(inventory, "Film", id("Film", "7"), Tables.INVENTORY.FILM_ID)).isSameAs(inventory);
        assertThat(inventory.getFilmId()).isEqualTo(7);
    }

    @Test
    void writersThatDisagreeOnAColumnFailTheRequest() {
        var inventory = new InventoryRecord();
        inventory.setFilmId(8);
        assertThatThrownBy(() -> decode(inventory, "Film", id("Film", 7), Tables.INVENTORY.FILM_ID))
            .hasMessageContaining("filmId");
        assertThat(inventory.getFilmId()).isEqualTo(8);
    }
}
