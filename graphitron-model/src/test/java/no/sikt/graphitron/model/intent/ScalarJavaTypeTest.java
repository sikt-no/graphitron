package no.sikt.graphitron.model.intent;

import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Consumer;

import static no.sikt.graphitron.model.Tables.INTENT_SCALAR_JAVA_TYPE;
import static no.sikt.graphitron.model.test.SeededStore.derive;
import static no.sikt.graphitron.model.test.SeededStore.seedGraphSource;
import static no.sikt.graphitron.model.test.SeededStore.seedScalarConstant;
import static no.sikt.graphitron.model.test.SeededStore.seedScalarType;
import static no.sikt.graphitron.model.test.SeededStore.seedSource;
import static no.sikt.graphitron.model.test.SeededStore.seedType;
import static no.sikt.graphitron.model.test.SeededStore.withSeededStore;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * What {@code intent_scalar_java_type} returns: the Java type a value of one of a graph's scalars
 * arrives as.
 *
 * <p>Two populations answer, and a reader wants one relation over both. The engine's scalars are a
 * closed list, the five the specification defines and the seven the federation link renames, all
 * of which coerce to a known type. A consumer's scalar names a constant, and what that constant
 * coerces to is a classpath reading rather than a name.
 */
class ScalarJavaTypeTest {

    // ===== The engine's own scalars =====


    /** A federation-namespace scalar deserialises to a string, and is a row for saying so. */
    @Test
    void aFederationNamespaceScalarCarriesString() {
        withGraph(dsl -> {
            seedType(dsl, GRAPH, "federation__FieldSet", "SCALAR");

            assertThat(types(dsl)).containsExactly("federation__FieldSet java.lang.String");
        });
    }

    /** The engine arm reads the kind and not the name alone. */
    @Test
    void aTypeSharingABuiltInsNameButNotItsKindDrawsNothing() {
        withGraph(dsl -> {
            seedType(dsl, GRAPH, "ID", "INPUT_OBJECT");

            assertThat(types(dsl)).isEmpty();
        });
    }


    // Five cases moved to facts/scalar-java-type.graphqls, where a document states the scalars and
    // the reading resolves them: the spec built-ins, a @scalarType reaching a real constant, a
    // reference matching none, a scalar left unbound, and the graph partition, which the fact
    // corpus checks structurally by holding every document's graph in one store.
    //
    // What is left is what a document cannot say yet, and each is a different reason:
    //
    //   - the federation-namespace scalar needs the federation link in the document, which is a
    //     document to write rather than a limit;
    //   - a type sharing a built-in's name but not its kind may be unwritable at all, graphql-java
    //     refusing to redefine ID, in which case the arm defends against input no reading produces;
    //   - a constant whose coercing could not be read needs a constant that behaves that way, and
    //     the artifacts on the corpus classpath may not carry one;
    //   - a constant outside the graph's sources needs a graph that claims fewer entries than its
    //     neighbours, and the fact runner captures every document over the same roots.
    //
    // The last is the only one that is a gap in the corpus rather than in this file.

    // ===== The consumer's own scalars =====


    /** A constant whose coercing the census could not read one off answers nothing, not null. */
    @Test
    void aConstantWithNoInputTypeDrawsNothing() {
        withGraph(dsl -> {
            seedScalarType(dsl, GRAPH, "DateTime", "com.example.Scalars.DATE_TIME");
            seedCensusConstant(dsl, "com.example.Scalars", "DATE_TIME", null);

            assertThat(types(dsl)).isEmpty();
        });
    }


    /** A constant on an entry this graph does not read is not this graph's to resolve against. */
    @Test
    void aConstantOutsideTheGraphsSourcesDrawsNothing() {
        withGraph(dsl -> {
            seedScalarType(dsl, GRAPH, "DateTime", "com.example.Scalars.DATE_TIME");
            seedSource(dsl, "elsewhere.jar", "JAR");
            seedScalarConstant(dsl, "elsewhere.jar", "com.example.Scalars", "DATE_TIME",
                "java.time.OffsetDateTime");

            assertThat(types(dsl)).isEmpty();
        });
    }


    // ===== The partition =====


    // ===== Helpers =====

    private static final String GRAPH = "g";
    private static final String CENSUS_SOURCE = "target/classes";

    private static void withGraph(Consumer<DSLContext> body) {
        withSeededStore(GRAPH, body);
    }

    /** A constant on an entry this graph reads, which is the ordinary shape. */
    private static void seedCensusConstant(DSLContext dsl, String className, String fieldName,
                                           String inputType) {
        seedSource(dsl, CENSUS_SOURCE, "DIRECTORY");
        seedGraphSource(dsl, GRAPH, CENSUS_SOURCE);
        seedScalarConstant(dsl, CENSUS_SOURCE, className, fieldName, inputType);
    }

    private static List<String> types(DSLContext dsl) {
        return typesIn(dsl, GRAPH);
    }

    private static List<String> typesIn(DSLContext dsl, String graphName) {
        derive(dsl);
        var t = INTENT_SCALAR_JAVA_TYPE;
        return dsl.select(t.TYPE_NAME, t.JAVA_TYPE)
            .from(t)
            .where(t.GRAPH_NAME.eq(graphName))
            .orderBy(t.TYPE_NAME)
            .fetch(row -> row.get(t.TYPE_NAME) + " " + row.get(t.JAVA_TYPE));
    }
}
