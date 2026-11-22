package no.sikt.graphitron.emit;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.JavaFile;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.TypeSpec;
import graphql.schema.DataFetchingEnvironment;
import graphql.schema.DataFetchingEnvironmentImpl;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLSchema;
import graphql.schema.idl.RuntimeWiring;
import graphql.schema.idl.SchemaGenerator;
import graphql.schema.idl.SchemaParser;
import no.sikt.graphitron.model.test.FactGraphs;
import no.sikt.graphitron.rewrite.generators.schema.GraphitronClientExceptionClassGenerator;
import no.sikt.graphitron.rewrite.test.compile.EmittedCodeHarness;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import javax.lang.model.element.Modifier;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link GeneratedScalarEmitter}'s decoders and {@link GeneratedScalarDecodeEmitter}'s requirement
 * checks, compiled and run: a value read off a real fetch, decoded into its record against a schema,
 * each requirement its {@code @assertType}s state checked on the record, and a wrong value or a failed
 * requirement thrown as the generated {@code GraphitronClientException} with the message a client
 * reads.
 *
 * <p>The generated scalars and each argument's decode are read from the
 * {@code scenarios/generated-scalar-call} fact document, so the requirements are ones a capture
 * states. The schema a decode checks against is built here from SDL with no fetchers, standing in for
 * the served one, so no request runs and no database is reached: it holds what the cases step through,
 * a connection, lists and non-null wrappers, an interface implementing another, a union, an enum, an
 * input type and a type whose name starts with {@code _}. The client exception is the generated one.
 * Compiled strictly, on the terms a consumer compiles generated sources on.
 */
@PipelineTier
class GeneratedScalarDecodeEmitterTest {

    @RegisterExtension
    static final FactGraphs.Reader FACTS = FactGraphs.reader();

    private static final String DOCUMENT = "scenarios/generated-scalar-call";
    private static final String PACKAGE = "scalarrun";
    private static final ClassName SCALARS = GeneratedScalarEmitter.className(PACKAGE);

    private static final GraphQLSchema SCHEMA = new SchemaGenerator().makeExecutableSchema(new SchemaParser().parse("""
        interface Node { id: ID! }
        interface Named implements Node { id: ID! name: String }
        type Film implements Node { id: ID! title: String mood: Mood actors: [Actor!]! }
        type Actor { firstName: String }
        type Person implements Named & Node { id: ID! name: String }
        type Customer { firstName: String }
        type Staff { firstName: String }
        union Occupant = Customer | Staff
        type OccupantConnection { edges: [OccupantEdge!]! nodes: [Occupant!]! }
        type OccupantEdge { node: Occupant cursor: String }
        enum Mood { HAPPY SAD }
        input FilmFilter { title: String }
        type _Hidden { secret: String }
        type Query {
          films(filter: FilmFilter): [Film!]!
          node(id: ID!): Node
          named: Named
          occupants: OccupantConnection
          hidden: _Hidden
        }
        """), RuntimeWiring.MOCKED_WIRING);

    private static EmittedCodeHarness harness;
    private static Map<String, Method> probes;

    /** Every decode the scenario states, by its occurrence's coordinate. */
    private static Map<String, GeneratedScalarQuery.Decode> decodes;

    private static GeneratedScalarQuery.Decode decode(String coordinate) {
        return Objects.requireNonNull(decodes.get(coordinate), () -> coordinate + " among " + decodes.keySet());
    }

    @BeforeAll
    static void emit() throws Exception {
        var rows = FACTS.read(DOCUMENT, graph -> GeneratedScalarQuery.read(graph.handle()));
        decodes = FACTS.read(DOCUMENT, graph -> GeneratedScalarQuery.decodes(graph.handle()));
        var host = TypeSpec.classBuilder("Probes").addModifiers(Modifier.PUBLIC)
            .addMethod(probe("along", decode("Query.along(path:)")))
            .addMethod(probe("named", decode("Query.named(type:)")))
            .addMethod(probe("alongFilm", decode("Query.alongFilm(path:)")))
            .addMethod(probe("alongNode", decode("Query.alongNode(path:)")))
            .addMethod(probe("alongToNode", decode("Query.alongToNode(path:)")))
            .addMethod(probe("alongEither", decode("Query.alongEither(path:)")))
            .addMethod(probe("alongNotNode", decode("Query.alongNotNode(path:)")))
            .addMethod(probe("alongNested", decode("Query.alongNested(path:)")))
            .addMethod(probe("inputP", decode("Query.alongInput(input.p:)")))
            .addMethod(probe("inputT", decode("Query.alongInput(input.t:)")))
            .addMethod(probe("innerP", decode("Query.alongInput(input.inner.p:)")))
            .build();
        var dir = Files.createTempDirectory("scalar");
        var files = new ArrayList<Path>();
        files.add(JavaFile.builder(PACKAGE, host).build().writeToPath(dir));
        files.add(JavaFile.builder(SCALARS.packageName(), GeneratedScalarEmitter.render(rows, PACKAGE)).build()
            .writeToPath(dir));
        for (TypeSpec spec : GraphitronClientExceptionClassGenerator.generate()) {
            files.add(JavaFile.builder(SCALARS.packageName(), spec).build().writeToPath(dir));
        }
        harness = EmittedCodeHarness.compileFilesStrictly(files);
        probes = new HashMap<>();
        for (var m : harness.load(PACKAGE + ".Probes").getDeclaredMethods()) probes.put(m.getName(), m);
    }

    /** A method running the decode and its requirement checks off the fetch, handing back the record. */
    private static MethodSpec probe(String name, GeneratedScalarQuery.Decode decode) {
        var local = GeneratedScalarDecodeEmitter.local(decode);
        var method = MethodSpec.methodBuilder(name)
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(Object.class)
            .addParameter(DataFetchingEnvironment.class, "env");
        GeneratedScalarDecodeEmitter.statements(decode, local, SCALARS).forEach(method::addStatement);
        method.addStatement("return $L", local);
        return method.build();
    }

    @AfterAll
    static void close() {
        if (harness != null) harness.close();
    }

    /** The decoded record's components, in order, from running {@code probe} on {@code arguments}. */
    private static List<String> run(String probe, Map<String, Object> arguments) throws Throwable {
        DataFetchingEnvironment env = DataFetchingEnvironmentImpl.newDataFetchingEnvironment()
            .graphQLSchema(SCHEMA)
            .arguments(arguments)
            .build();
        Object decoded;
        try {
            decoded = probes.get(probe).invoke(null, env);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
        var components = new ArrayList<String>();
        for (var component : decoded.getClass().getRecordComponents()) {
            components.add((String) component.getAccessor().invoke(decoded));
        }
        return components;
    }

    private static List<String> path(String probe, String value) throws Throwable {
        return run(probe, Map.of("path", value));
    }

    private static void assertRefused(String probe, Map<String, Object> arguments, String message) {
        assertThatThrownBy(() -> run(probe, arguments))
            .satisfies(e -> assertThat(e.getClass().getSimpleName()).isEqualTo("GraphitronClientException"))
            .hasMessage(message);
    }

    private static void assertPathRefused(String probe, String value, String message) {
        assertRefused(probe, Map.of("path", value), message);
    }

    // ===== A path, decoded =====

    @Test
    void aPathDecodesIntoItsTextStartStepsAndEnd() throws Throwable {
        assertThat(path("along", "Film.title")).containsExactly("Film.title", "Film", "title", "String");
    }

    @Test
    void aPathOfNoStepsHasEmptyStepsAndEndsWhereItStarts() throws Throwable {
        assertThat(path("along", "Film")).containsExactly("Film", "Film", "", "Film");
    }

    @Test
    void aPathStepsThroughListsAndNonNullWrappers() throws Throwable {
        assertThat(path("along", "Query.films")).containsExactly("Query.films", "Query", "films", "Film");
        assertThat(path("along", "Query.films.actors.firstName"))
            .containsExactly("Query.films.actors.firstName", "Query", "films.actors.firstName", "String");
    }

    @Test
    void aPathStepsThroughAConnectionsEdgesOrNodesAndMayEndAtAUnion() throws Throwable {
        assertThat(path("along", "Query.occupants.edges.node"))
            .containsExactly("Query.occupants.edges.node", "Query", "occupants.edges.node", "Occupant");
        assertThat(path("along", "Query.occupants.nodes"))
            .containsExactly("Query.occupants.nodes", "Query", "occupants.nodes", "Occupant");
    }

    @Test
    void aPathMayStartAndEndAtAnInterface() throws Throwable {
        assertThat(path("along", "Query.node")).containsExactly("Query.node", "Query", "node", "Node");
        assertThat(path("along", "Named.name")).containsExactly("Named.name", "Named", "name", "String");
    }

    @Test
    void aNullPathDecodesToNullsWithoutChecking() throws Throwable {
        assertThat(run("along", Map.of())).hasSize(4).containsOnlyNulls();
    }

    @Test
    void aValueThatIsNoPathIsRefused() {
        assertPathRefused("along", "",
            "path: \"\" is not a path: a type name followed by field names, separated by dots");
        assertPathRefused("along", ".Film",
            "path: \".Film\" is not a path: a type name followed by field names, separated by dots");
        assertPathRefused("along", "Film.",
            "path: \"Film.\" is not a path: a type name followed by field names, separated by dots");
        assertPathRefused("along", "Film..title",
            "path: \"Film..title\" is not a path: a type name followed by field names, separated by dots");
    }

    @Test
    void aNameNoTypeOrFieldCouldHaveIsRefusedAsOneTheSchemaDoesNotHave() {
        // The decoder checks no name grammar: a character no GraphQL name holds names nothing the
        // schema has, and is refused as an unknown type or field.
        assertPathRefused("along", "Fi-lm.title", "path: Fi-lm is no object or interface type of this schema");
        assertPathRefused("along", "Film.ti-tle", "path: Film has no field ti-tle");
    }

    @Test
    void anUnknownTypeOrFieldIsRefused() {
        assertPathRefused("along", "Nope.title", "path: Nope is no object or interface type of this schema");
        assertPathRefused("along", "Film.nope", "path: Film has no field nope");
    }

    @Test
    void aStepPastAScalarAnEnumOrAUnionIsRefused() {
        assertPathRefused("along", "Film.title.length",
            "path: String has no fields, so the path cannot step to length");
        assertPathRefused("along", "Film.mood.name", "path: Mood has no fields, so the path cannot step to name");
        assertPathRefused("along", "Query.occupants.edges.node.firstName",
            "path: Occupant has no fields, so the path cannot step to firstName");
    }

    @Test
    void aPathStartingWithAnUnderscoreIsRefusedThoughTheTypeExists() {
        assertThat(SCHEMA.getType("_Hidden")).isInstanceOf(GraphQLObjectType.class);
        assertPathRefused("along", "_Hidden.secret", "path: _Hidden is no object or interface type of this schema");
        assertPathRefused("along", "__Type.name", "path: __Type is no object or interface type of this schema");
    }

    @Test
    void aPathStartingAtAnInputTypeAnEnumAUnionOrAScalarIsRefused() {
        assertPathRefused("along", "FilmFilter.title",
            "path: FilmFilter is no object or interface type of this schema");
        assertPathRefused("along", "Mood", "path: Mood is no object or interface type of this schema");
        assertPathRefused("along", "Occupant", "path: Occupant is no object or interface type of this schema");
        assertPathRefused("along", "String", "path: String is no object or interface type of this schema");
    }

    // ===== A type name, decoded =====

    @Test
    void aTypeNameDecodesIntoItsTextAndName() throws Throwable {
        assertThat(run("named", Map.of("type", "Film"))).containsExactly("Film", "Film");
        assertThat(run("named", Map.of())).hasSize(2).containsOnlyNulls();
    }

    @Test
    void aTypeNameNamesAnObjectTypeAndNothingElse() {
        assertRefused("named", Map.of("type", "Nope"), "type: Nope is no object type of this schema");
        assertRefused("named", Map.of("type", "Node"), "type: Node is no object type of this schema");
        assertRefused("named", Map.of("type", "Occupant"), "type: Occupant is no object type of this schema");
        assertRefused("named", Map.of("type", "FilmFilter"), "type: FilmFilter is no object type of this schema");
        assertRefused("named", Map.of("type", "Mood"), "type: Mood is no object type of this schema");
        assertRefused("named", Map.of("type", "_Hidden"), "type: _Hidden is no object type of this schema");
        assertRefused("named", Map.of("type", "__Type"), "type: __Type is no object type of this schema");
    }

    // ===== The requirements an @assertType states =====

    @Test
    void anIsRequirementComparesTheComponentsName() throws Throwable {
        assertThat(path("alongFilm", "Film")).containsExactly("Film", "Film", "", "Film");
        assertThat(path("alongFilm", "Query.films")).containsExactly("Query.films", "Query", "films", "Film");
        assertPathRefused("alongFilm", "Actor", "path: the path's endType `Actor` is not `Film`");
    }

    @Test
    void anImplementsRequirementAsksTheSchema() throws Throwable {
        assertThat(path("alongNode", "Film.title")).containsExactly("Film.title", "Film", "title", "String");
        assertPathRefused("alongNode", "Actor.firstName", "path: the path's startType `Actor` does not implement `Node`");
        assertPathRefused("alongToNode", "Film.title", "path: the path's endType `String` does not implement `Node`");
        assertPathRefused("alongToNode", "Query.occupants.nodes",
            "path: the path's endType `Occupant` does not implement `Node`");
    }

    @Test
    void anImplementsRequirementHoldsThroughAnInterfaceChain() throws Throwable {
        // Named implements Node, and Person implements Named, so both implement Node.
        assertThat(path("alongNode", "Person")).containsExactly("Person", "Person", "", "Person");
        assertThat(path("alongNode", "Named.name")).containsExactly("Named.name", "Named", "name", "String");
        assertThat(path("alongToNode", "Query.named")).containsExactly("Query.named", "Query", "named", "Named");
    }

    @Test
    void anInterfaceDoesNotImplementItself() {
        assertPathRefused("alongNode", "Node", "path: the path's startType `Node` does not implement `Node`");
        assertPathRefused("alongToNode", "Query.node", "path: the path's endType `Node` does not implement `Node`");
    }

    @Test
    void anAnyOfRequirementHoldsWhenOneOfItsConditionsDoes() throws Throwable {
        assertThat(path("alongEither", "Film")).containsExactly("Film", "Film", "", "Film");
        assertThat(path("alongEither", "Actor")).containsExactly("Actor", "Actor", "", "Actor");
        assertPathRefused("alongEither", "Film.title",
            "path: the path's endType `String` does not meet {anyOf: [{is: \"Film\"}, {is: \"Actor\"}]}");
    }

    @Test
    void aNotRequirementHoldsWhenItsConditionDoesNot() throws Throwable {
        assertThat(path("alongNotNode", "Actor")).containsExactly("Actor", "Actor", "", "Actor");
        assertPathRefused("alongNotNode", "Film",
            "path: the path's startType `Film` does not meet {not: {implements: \"Node\"}}");
        assertPathRefused("alongNotNode", "Person",
            "path: the path's startType `Person` does not meet {not: {implements: \"Node\"}}");
    }

    @Test
    void anAllOfRequirementHoldsWhenEveryNestedConditionDoes() throws Throwable {
        assertThat(path("alongNested", "Film")).containsExactly("Film", "Film", "", "Film");
        var written = "{allOf: [{anyOf: [{is: \"Film\"}, {is: \"Actor\"}]}, {not: {is: \"Actor\"}}]}";
        assertPathRefused("alongNested", "Actor", "path: the path's endType `Actor` does not meet " + written);
        assertPathRefused("alongNested", "Person", "path: the path's endType `Person` does not meet " + written);
    }

    @Test
    void aRequirementOnANullValueHolds() throws Throwable {
        assertThat(run("alongFilm", Map.of())).hasSize(4).containsOnlyNulls();
        assertThat(run("alongNode", Map.of())).hasSize(4).containsOnlyNulls();
    }

    @Test
    void aValueTheSchemaDoesNotHaveIsRefusedBeforeItsRequirementsAreChecked() {
        assertPathRefused("alongFilm", "Film.nope", "path: Film has no field nope");
    }

    // ===== A value inside an input object =====

    @Test
    void aValueInsideAnInputObjectIsReadAndRefusedAtItsOwnPath() throws Throwable {
        assertThat(run("innerP", Map.of("input", Map.of("inner", Map.of("p", "Film")))))
            .containsExactly("Film", "Film", "", "Film");
        assertThat(run("innerP", Map.of("input", Map.of()))).hasSize(4).containsOnlyNulls();
        assertRefused("innerP", Map.of("input", Map.of("inner", Map.of("p", "Film.nope"))),
            "input.inner.p: Film has no field nope");
        assertRefused("inputP", Map.of("input", Map.of("p", "Film.title")),
            "input.p: the path's endType `String` is not `Film`");
        assertRefused("inputT", Map.of("input", Map.of("t", "Nope")), "input.t: Nope is no object type of this schema");
    }
}
