package no.sikt.graphitron.emit;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeSpec;
import com.palantir.javapoet.WildcardTypeName;
import no.sikt.graphitron.rewrite.TestSchemaHelper;
import no.sikt.graphitron.rewrite.generators.util.ColumnValuesClassGenerator;
import no.sikt.graphitron.rewrite.generators.util.NodeIdEncoderClassGenerator;
import no.sikt.graphitron.rewrite.test.compile.EmittedCodeHarness;
import no.sikt.graphitron.rewrite.test.jooq.public_.Tables;
import no.sikt.graphitron.rewrite.test.jooq.public_.tables.records.FilmRecord;
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

/**
 * {@link BuildEmitter#recordHelper}, compiled strictly with the generated {@code NodeIdEncoder} and
 * run: an input object made into a jOOQ record on the column axis, a column written only for a field
 * the client sent so the record's flags say what it wrote, an explicit null written, a deprecated
 * alias writing its column where the live field was not sent and losing to it where both were, and a
 * node id decoded into the record's key.
 */
@UnitTier
class RecordBuildEmitterTest {

    private static final String PACKAGE = "recordbuild";
    private static final String TABLES = "no.sikt.graphitron.rewrite.test.jooq.public_.Tables";
    private static final ClassName ENCODER = ClassName.get(PACKAGE + ".util", NodeIdEncoderClassGenerator.CLASS_NAME);
    private static final BuildEmitter.Runtime RUNTIME = new BuildEmitter.Runtime(ENCODER,
        label -> CodeBlock.of("throw new $T($S)", IllegalArgumentException.class, "invalid id for " + label));

    private static EmittedCodeHarness harness;
    private static Method build;
    private static Method encode;

    @BeforeAll
    static void compile() throws Exception {
        var filmId = new BuildQuery.Member(
            new BuildQuery.Slot(null, null, 0, "film_id", FilmRecord.class.getName(), FilmRecord.class.getName(), null),
            List.of("filmId"), "SCALAR", false, Optional.empty(),
            Optional.of(new BuildQuery.Decode("Film", TABLES, "FILM", FilmRecord.class.getName(), List.of("FILM_ID"))));
        var film = new BuildQuery.RecordBuild(FilmRecord.class.getName(), TABLES, "FILM", List.of(
            new BuildQuery.ColumnWrite("TITLE", List.of(List.of("title"))),
            new BuildQuery.ColumnWrite("RELEASE_YEAR", List.of(List.of("releaseYear"), List.of("year")))),
            List.of(filmId));
        var anyMap = ParameterizedTypeName.get(ClassName.get(Map.class),
            WildcardTypeName.subtypeOf(Object.class), WildcardTypeName.subtypeOf(Object.class));
        var host = TypeSpec.classBuilder("Probes").addModifiers(Modifier.PUBLIC)
            .addMethod(BuildEmitter.recordHelper(film, "buildFilm", RUNTIME))
            .addMethod(MethodSpec.methodBuilder("probe").addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .returns(FilmRecord.class).addParameter(anyMap, "in")
                .addStatement("return buildFilm(in)").build());
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

    private static FilmRecord run(Map<String, Object> in) throws Throwable {
        try {
            return (FilmRecord) build.invoke(null, in);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    @Test
    void aColumnIsWrittenOnlyForAFieldTheClientSent() throws Throwable {
        var film = run(Map.of("title", "Alien"));
        assertThat(film.getTitle()).isEqualTo("Alien");
        assertThat(film.touched(Tables.FILM.TITLE)).isTrue();
        assertThat(film.touched(Tables.FILM.RELEASE_YEAR)).isFalse();
    }

    @Test
    void anExplicitNullIsWritten() throws Throwable {
        var in = new HashMap<String, Object>();
        in.put("title", null);
        var film = run(in);
        assertThat(film.getTitle()).isNull();
        assertThat(film.touched(Tables.FILM.TITLE)).isTrue();
    }

    @Test
    void aDeprecatedAliasWritesWhereTheLiveFieldWasNotSentAndLosesWhereItWas() throws Throwable {
        assertThat(run(Map.of("year", 1979)).getReleaseYear()).isEqualTo(1979);
        assertThat(run(Map.of("releaseYear", 1986, "year", 1979)).getReleaseYear()).isEqualTo(1986);
    }

    @Test
    void aNodeIdDecodesIntoTheRecordsKey() throws Throwable {
        var id = (String) encode.invoke(null, "Film", new Object[] {7});
        var film = run(Map.of("filmId", id, "title", "Alien"));
        assertThat(film.getFilmId()).isEqualTo(7);
        assertThat(film.getTitle()).isEqualTo("Alien");
    }
}
