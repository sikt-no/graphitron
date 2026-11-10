package no.sikt.graphitron.emit;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeSpec;
import com.palantir.javapoet.WildcardTypeName;
import no.sikt.graphitron.model.test.FactGraphs;
import no.sikt.graphitron.rewrite.TestSchemaHelper;
import no.sikt.graphitron.rewrite.generators.schema.GraphitronClientExceptionClassGenerator;
import no.sikt.graphitron.rewrite.generators.util.ColumnValuesClassGenerator;
import no.sikt.graphitron.rewrite.generators.util.NodeIdEncoderClassGenerator;
import no.sikt.graphitron.rewrite.test.compile.EmittedCodeHarness;
import no.sikt.graphitron.rewrite.test.jooq.public_.Tables;
import no.sikt.graphitron.rewrite.test.jooq.public_.tables.records.FilmRecord;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link BuildEmitter#recordHelper}, compiled strictly with the generated {@code NodeIdEncoder} and
 * run: an input object made into a jOOQ record on the column axis, a column written only for a field
 * the client sent so the record's flags say what it wrote, an explicit null written, a deprecated
 * alias writing its column where the live field was not sent and losing to it where both were, a
 * node id decoded into the record's key, an explicit null on a {@code NOT NULL} column kept however
 * many values are loaded after it, and a node id sent as an explicit null nulling the key. The build
 * is read from the {@code scenarios/service-input-build} fact document.
 */
@PipelineTier
class RecordBuildEmitterTest {

    @RegisterExtension
    static final FactGraphs.Reader FACTS = FactGraphs.reader();

    private static final String PACKAGE = "recordbuild";
    private static final ClassName ENCODER = ClassName.get(PACKAGE + ".util", NodeIdEncoderClassGenerator.CLASS_NAME);
    private static final NodeIdDecodeEmitter.Runtime RUNTIME = new NodeIdDecodeEmitter.Runtime(ENCODER, PACKAGE);

    private static EmittedCodeHarness harness;
    private static Method build;
    private static Method encode;

    @BeforeAll
    static void compile() throws Exception {
        var film = FACTS.read("scenarios/service-input-build", graph -> BuildQuery.read(graph.handle()))
            .entrySet().stream()
            .filter(e -> e.getKey().callCoordinate().equals("Query.countFilmRecordDatedDrafts"))
            .map(e -> ((BuildQuery.Result.Columns) e.getValue()).build())
            .findFirst().orElseThrow();
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

    @Test
    void anExplicitNullOnANotNullColumnSurvivesAValueLoadedAfterIt() throws Throwable {
        var in = new HashMap<String, Object>();
        in.put("title", null);
        in.put("releaseYear", 2001);
        var film = run(in);
        assertThat(film.touched(Tables.FILM.TITLE)).as("title, NOT NULL, is still written").isTrue();
        assertThat(film.getTitle()).isNull();
        assertThat(film.getReleaseYear()).isEqualTo(2001);
    }

    @Test
    void aNodeIdSentAsNullNullsTheKeyItWouldHaveWritten() throws Throwable {
        var in = new HashMap<String, Object>();
        in.put("filmId", null);
        in.put("title", "Alien");
        var film = run(in);
        assertThat(film.touched(Tables.FILM.FILM_ID)).isTrue();
        assertThat(film.getFilmId()).isNull();
    }
}
