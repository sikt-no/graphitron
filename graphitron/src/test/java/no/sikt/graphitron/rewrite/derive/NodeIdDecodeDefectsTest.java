package no.sikt.graphitron.rewrite.derive;

import no.sikt.graphitron.common.configuration.TestConfiguration;
import no.sikt.graphitron.rewrite.CapturedStore;
import no.sikt.graphitron.model.config.ClasspathEntry;
import no.sikt.graphitron.model.jooq.JooqCatalog;
import no.sikt.graphitron.model.diagnostics.ValidationError;
import no.sikt.graphitron.model.classpath.ClasspathScanner;
import no.sikt.graphitron.model.classpath.CompletionData;
import no.sikt.graphitron.model.diagnostics.Rejection;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import no.sikt.graphitron.model.derive.NodeIdDecodeDefects;

/**
 * The store-backed home of the {@code @nodeId} decode rules at a producer parameter: real SDL
 * captured into a fact store, and the violations {@link NodeIdDecodeDefects} projects from
 * {@code intent_node_id_decode_defect}. This is the tier that says an author's schema reaches that
 * relation in the shape the rules read, and that the report a consumer meets is minted from what it
 * finds.
 *
 * <p>What the view returns given rows is not asked here. That is the relation's own algebra, its two
 * verdicts and the three populations it excludes, and it lives in the module whose DDL declares it,
 * in {@code no.sikt.graphitron.model.intent.NodeIdDecodeDefectTest}, against a store seeded row by
 * row. What stands here is the decode: which {@link Rejection} arm each verdict becomes, the prose it
 * carries, the location it points at, and the population this consumer asks about.
 *
 * <p>Every fixture below spells a schema that used to compile. An author annotated an argument
 * {@code @nodeId}, the walk's type gate stood aside for the reason it documents, and the opaque wire
 * string then reached the parameter with nothing in the build saying a word. The cases that draw no
 * violation are here for the same reason: this family strictly adds refusals, so a decode that was
 * being carried out has to still be carried out.
 *
 * <p>Two capture inputs the SDL-only fixtures elsewhere do without are load-bearing here, because
 * the rules read both corpora. The jOOQ catalog is what types a key column, and the classpath census
 * is what types a parameter, so a fixture missing either would assert a stand-aside it did not mean
 * to construct.
 */
@PipelineTier
class NodeIdDecodeDefectsTest {

    private static final String GRAPH = CapturedStore.GRAPH;
    private static final String SERVICE_STUB = "no.sikt.graphitron.rewrite.PublicNodeIdServiceStub";

    /**
     * The catalog and the census, scanned once for the class. The census is the module's own test
     * classes rather than the reactor's main ones, that being where the stub these fixtures name
     * lives; scanning it is under a tenth of a second, so the reuse is tidiness and not a budget.
     */
    private static JooqCatalog jooq;
    private static List<CompletionData.ExternalReference> census;

    @BeforeAll
    static void scanTheClasspath() {
        var ctx = TestConfiguration.testContext();
        jooq = new JooqCatalog(ctx.jooqPackage(), ctx.codegenLoader());
        census = ClasspathScanner.scan(
            ClasspathEntry.projectRoots(List.of(Path.of("target/test-classes"))),
            ctx.jooqPackage());
    }

    @TempDir
    Path tmp;

    // ===== The two verdicts =====

    /**
     * A parameter typed as the wire format at a node type keyed on one column: the shape that used to
     * hand a service method the base64 string. The message names both types and the column, because
     * without both it would say a correctly named parameter is wrong.
     */
    @Test
    void aParameterThatCannotTakeTheSoleKeyColumnIsRejectedNamingBothTypes() {
        var violations = detect(schema("Film", "getFilmsByStringKey"));

        assertThat(messages(violations)).containsExactly(
            "Field 'Query.films': argument 'key' carries the @nodeId(typeName: \"Film\") and the"
            + " producer method declares a parameter 'key' of that name, so the decoded key lands"
            + " there, and its key column 'film_id' jOOQ binds as Integer, but 'key' takes String;"
            + " declare the parameter with the column's own type");
        assertThat(violations.getFirst().rejection())
            .isInstanceOf(Rejection.AuthorError.Structural.class);
        assertThat(violations.getFirst().location()).isNotNull();
        assertThat(violations.getFirst().location().getSourceName()).endsWith("fixture.graphqls");
    }

    /**
     * A composite key at a parameter holding one value. The count is the whole of what is wrong, so
     * the message quotes it and offers both remedies: the node type's own record takes the tuple, and
     * an {@code argMapping} entry takes one column of it, which is why the columns are named.
     */
    @Test
    void aCompositeKeyAtASingleValuedParameterIsRejectedNamingTheCountAndTheColumns() {
        assertThat(messages(detect(schema("Inventory", "getFilmsByStringKey")))).containsExactly(
            "Field 'Query.films': argument 'key' carries the @nodeId(typeName: \"Inventory\") and the"
            + " producer method declares a parameter 'key' of that name, so the decoded key lands"
            + " there, but that key is 2 columns and one parameter takes one value; declare 'key' as"
            + " the generated record of that node type's own table to receive the whole tuple, or"
            + " bind one of its key columns to a parameter with argMapping: inventory_id, store_id");
    }

    // ===== What the two arms deliberately leave alone =====

    /**
     * The parameter typed as the key column jOOQ binds it as, which is the schema an author writes
     * after reading either message above. Nothing to report, and asserted as an empty report rather
     * than as an absent verdict, because a family that adds refusals must add none here.
     */
    @Test
    void aParameterOfTheKeyColumnsOwnTypeIsNoDefect() {
        assertThat(detect(schema("Film", "getFilmsByIntegerKey")))
            .as("the parameter takes exactly what the sole key column binds as")
            .isEmpty();
    }

    /**
     * The composite key's other remedy: a parameter typed as the node type's own generated record
     * takes the whole tuple, so the arity that refuses a single-valued parameter refuses nothing
     * here. The same two key columns as the arity case above, which is what makes this the remedy for
     * it rather than a different schema.
     */
    @Test
    void aParameterTypedAsTheNodeTypesRecordTakesACompositeKey() {
        assertThat(detect(schema("Inventory", "getFilmsByInventoryKey")))
            .as("a record holds the tuple whatever the key's arity")
            .isEmpty();
    }

    /**
     * A primitive parameter at a one-column key: the type verdict needs both operands and the census
     * reads no class at that position, so nothing is refused and the decode is carried out on arity
     * alone with javac as the backstop. Refusing here would open a second silence rather than close
     * the one this family exists for.
     */
    @Test
    void aParameterNoCensusCanTypeDrawsNoTypeVerdict() {
        assertThat(detect(schema("Film", "getFilmsByPrimitiveKey")))
            .as("a refusal names its operands, and one of these two is unreadable")
            .isEmpty();
    }

    /**
     * The same untypeable parameter at a composite key still refuses, which is the one place the
     * stand-aside rule had to be read rather than copied. What the arity verdict needs is whether the
     * parameter is the tuple's own row type, and a position naming no class is a primitive or a type
     * variable, neither of which is a generated record. So the absence answers the question instead
     * of leaving it unanswered.
     */
    @Test
    void anUntypeableParameterAtACompositeKeyIsStillRefusedOnTheArity() {
        assertThat(messages(detect(schema("Inventory", "getFilmsByPrimitiveKey"))))
            .singleElement(InstanceOfAssertFactories.STRING)
            .contains("that key is 2 columns and one parameter takes one value");
    }

    // ===== The population this consumer asks about =====

    /**
     * The build-error population is the classification domain, so the same refusal at a coordinate
     * no root operation reaches mints nothing: only an emitted coordinate can fail a build. The view
     * itself states no such filter and the editor's diagnostic arm reads it ungated, a coordinate
     * nothing reaches being where an author most needs to be told.
     */
    @Test
    void aRefusalOutsideTheClassificationDomainFailsNoBuild() {
        assertThat(detect("""
            interface Node { id: ID! }
            type Film implements Node @table(name: "film") @node(keyColumns: ["film_id"]) {
                id: ID! @nodeId
                title: String
            }
            type Query { film: Film }
            type Orphan {
                films(key: ID! @nodeId(typeName: "Film")): [Film!]!
                    @service(service: {className: "%s", method: "getFilmsByStringKey"})
            }
            """.formatted(SERVICE_STUB)))
            .as("no root operation reaches Orphan, so no emitted source carries this decode")
            .isEmpty();
    }

    // ===== Helpers =====

    /**
     * One schema shape parameterised by the two things the fixtures vary: which node type the
     * argument decodes against, which fixes the key's arity, and which stub method receives it, which
     * fixes the parameter's type. Held as one template because the cases are a matrix over those two
     * and a case that differed in a third thing would not be comparing what it claims to.
     */
    private static String schema(String nodeType, String method) {
        return """
            interface Node { id: ID! }
            type Inventory implements Node @table(name: "inventory")
                    @node(keyColumns: ["inventory_id", "store_id"]) {
                id: ID! @nodeId
            }
            type Film implements Node @table(name: "film") @node(keyColumns: ["film_id"]) {
                id: ID! @nodeId
                title: String
            }
            type Query {
                inventory: Inventory
                film: Film
                films(key: ID! @nodeId(typeName: "%s")): [Film!]!
                    @service(service: {className: "%s", method: "%s"})
            }
            """.formatted(nodeType, SERVICE_STUB, method);
    }

    /** Captures {@code sdl} against both corpora and runs the detection over what capture wrote. */
    private List<ValidationError> detect(String sdl) {
        try (var store = CapturedStore.ofCatalog(tmp, GRAPH, sdl, jooq, census)) {
            return NodeIdDecodeDefects.detect(store.dsl(), GRAPH).violations();
        }
    }

    /** The violations' messages, the surface an author actually meets. */
    private static List<String> messages(List<ValidationError> violations) {
        return violations.stream().map(ValidationError::message).toList();
    }
}
