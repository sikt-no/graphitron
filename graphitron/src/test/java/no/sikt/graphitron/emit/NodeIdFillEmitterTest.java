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
import no.sikt.graphitron.rewrite.test.jooq.public_.tables.records.ConverterOrgRecord;
import no.sikt.graphitron.rewrite.test.jooq.public_.tables.records.StaffRecord;
import no.sikt.graphitron.rewrite.test.jooq.public_.tables.records.CustomerRecord;
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
        return stated(name, type, List.of(decode), shape, Optional.empty());
    }

    private static Case stated(String name, String type, List<NodeIdDecodeQuery.Decode> decodes, String shape,
                               Optional<String> column) {
        var row = new FillQuery.Row("Query." + name, "Query." + name, List.of(new FillQuery.Fill(0, "id",
            type, type, "ARGUMENT", null, null, Optional.of(new FillQuery.Landing(List.of("id"), "SCALAR", true)))),
            true);
        return new Case(name, row, new BuildQuery.Result.Decoded(decodes, shape, column,
            type.contains("<") ? type.substring(0, type.indexOf('<')) : type));
    }

    /** The decode of the id at {@code coordinate} as the node on {@code bound}, into its own record. */
    private static NodeIdDecodeQuery.Decode own(NodeIdDecodeQuery decodes, String coordinate, String bound) {
        return ((NodeIdDecodeQuery.Resolution.Resolved) decodes.resolve(coordinate, bound, Optional.empty())).decode();
    }

    @BeforeAll
    static void compile() throws Exception {
        var decodes = FACTS.read("scenarios/node-id-decode-record", graph -> NodeIdDecodeQuery.read(graph.handle()));
        var film = ((NodeIdDecodeQuery.Resolution.Resolved)
            decodes.resolve("Query.inventories(filmId:)", "public.film", Optional.of(FILM_RECORD))).decode();
        var inventoryFilm = ((NodeIdDecodeQuery.Resolution.Resolved)
            decodes.resolve("Query.inventories(filmId:)", "public.film", Optional.of(InventoryRecord.class.getName()))).decode();
        var occupants = FACTS.read("defects/polymorphic-node-id-decode-containers",
            graph -> NodeIdDecodeQuery.read(graph.handle()));
        var customer = own(occupants, "Query.occupant(occupant:)", "public.customer");
        var staff = own(occupants, "Query.occupant(occupant:)", "public.staff");
        var orgs = FACTS.read("defects/node-id-decode-landing-defect-verdicts",
            graph -> NodeIdDecodeQuery.read(graph.handle()));
        var org = own(orgs, "Query.divergedChildren(ofOrg:)", "public.converter_org");
        var cases = List.of(
            stated("occupant", "org.jooq.UpdatableRecord<?>", List.of(customer, staff), "SUPERTYPE", Optional.empty()),
            stated("occupants", "java.util.List<org.jooq.UpdatableRecord<?>>", List.of(customer, staff), "SUPERTYPE",
                Optional.empty()),
            stated("filmIds", "java.util.List<java.lang.Integer>", List.of(film), "KEY_VALUE", Optional.of("FILM_ID")),
            stated("filmId", "java.lang.Integer", List.of(film), "KEY_VALUE", Optional.of("FILM_ID")),
            stated("org", ConverterOrgRecord.class.getName(), org, "RECORD"),
            stated("orgCodes", "java.util.List<java.lang.String>", List.of(org), "KEY_VALUE", Optional.of("ORG_CODE")),
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

    private static String id(String typeId, Object... values) throws Exception {
        return (String) encode.invoke(null, typeId, values);
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

    // ===== An id of several node types, decoded into whichever member's record it names =====

    @Test
    void anIdOfEitherMemberDecodesIntoThatMembersRecord() throws Throwable {
        assertThat(run("occupant", id("Customer", 4))).isInstanceOfSatisfying(CustomerRecord.class,
            c -> assertThat(c.getCustomerId()).isEqualTo(4));
        assertThat(run("occupant", id("Staff", 2))).isInstanceOfSatisfying(StaffRecord.class,
            s -> assertThat(s.getStaffId()).isEqualTo(2));
    }

    @Test
    void anIdOfNoMemberTypeIsRefusedNamingEveryMember() throws Throwable {
        assertThatThrownBy(() -> run("occupant", id("Film", 7)))
            .hasMessageContaining("decodes to type \"Film\"").hasMessageContaining("Customer, Staff");
        assertThatThrownBy(() -> run("occupant", "not-a-node-id"))
            .hasMessageContaining("not a valid id").hasMessageContaining("Customer, Staff");
        assertThatThrownBy(() -> run("occupant", id("Customer", 4, 9)))
            .as("a member's type id with the wrong arity").hasMessageContaining("not a valid id");
        assertThatThrownBy(() -> run("occupant", id("Customer", "x")))
            .as("a member's type id carrying a value that is no key value").hasMessageContaining("not a valid id");
    }

    @Test
    void aListOfIdsOfMixedMembersDecodesEachAndKeepsANullElement() throws Throwable {
        var decoded = (List<?>) run("occupants", new ArrayList<>(Arrays.asList(id("Staff", 1), null, id("Customer", 3))));
        assertThat(decoded).hasSize(3);
        assertThat(decoded.get(0)).isInstanceOf(StaffRecord.class);
        assertThat(decoded.get(1)).isNull();
        assertThat(decoded.get(2)).isInstanceOf(CustomerRecord.class);
        assertThatThrownBy(() -> run("occupants", List.of(id("Customer", 3), id("Film", 1))))
            .as("one bad element fails the whole argument").hasMessageContaining("decodes to type \"Film\"");
    }

    // ===== One key column projected out of each id =====

    @Test
    void aProjectedColumnIsTakenOffEachIdKeepingANullElement() throws Throwable {
        assertThat(new ArrayList<Object>((List<?>) run("filmIds", new ArrayList<>(Arrays.asList(id("F", 8), null, id("F", 7))))))
            .containsExactly(8, null, 7);
        assertThat(run("filmId", id("F", 9))).isEqualTo(9);
        assertThat((List<?>) run("filmIds", List.of())).isEmpty();
        assertThatThrownBy(() -> run("filmIds", List.of(id("Actor", 1))))
            .hasMessageEndingWith("decodes to type \"Actor\", expected a Film id");
    }

    // ===== A key column carrying a converter =====

    @Test
    void aConverterBackedKeyDecodesAsTheConvertersUserType() throws Throwable {
        assertThat(run("org", id("ConverterOrg", "1120"))).isInstanceOfSatisfying(ConverterOrgRecord.class,
            o -> assertThat(o.getOrgCode()).isEqualTo("1120"));
        assertThat(new ArrayList<Object>((List<?>) run("orgCodes", List.of(id("ConverterOrg", "1120"), id("ConverterOrg", "186")))))
            .containsExactly("1120", "186");
    }

    @Test
    void aConverterBackedKeyRefusesTextTheEncoderNeverWrites() throws Throwable {
        assertThatThrownBy(() -> run("org", id("ConverterOrg", "0186")))
            .as("the canonical value is \"186\"").hasMessageContaining("not a valid ConverterOrg id");
        assertThatThrownBy(() -> run("org", id("ConverterOrg", "abc")))
            .as("not a value of the bigint below the converter").hasMessageContaining("not a valid ConverterOrg id");
    }
}
