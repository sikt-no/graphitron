package no.sikt.graphitron.emit;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.TypeSpec;
import no.sikt.graphitron.model.test.FactGraphs;
import no.sikt.graphitron.rewrite.generators.schema.GraphitronClientExceptionClassGenerator;
import no.sikt.graphitron.rewrite.generators.util.ColumnValuesClassGenerator;
import no.sikt.graphitron.rewrite.generators.util.NodeIdEncoderClassGenerator;
import no.sikt.graphitron.rewrite.test.compile.EmittedCodeHarness;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.jooq.Record;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The generated {@code NodeIdEncoder}'s strict {@code decode<TypeName>OrThrow}, compiled with the client
 * error over the {@code scenarios/node-served-by-id} fact document and run: an id of the node answers
 * its key record; one that is malformed, of the right type at the wrong arity, or of another type is
 * the client error every other decode of a {@code @nodeId} raises, never a {@code null} a caller could
 * read as no value.
 */
@PipelineTier
class NodeIdStrictDecodeTest {

    @RegisterExtension
    static final FactGraphs.Reader FACTS = FactGraphs.reader();

    private static final String PACKAGE = "nodestrict";
    private static final ClassName ENCODER = ClassName.get(PACKAGE + ".util", NodeIdEncoderClassGenerator.CLASS_NAME);

    private static EmittedCodeHarness harness;
    private static Method decodeCustomer;
    private static Method encode;

    @BeforeAll
    static void compile() throws Exception {
        var encoded = FACTS.read("scenarios/node-served-by-id", graph -> NodeIdEncoderQuery.read(graph.handle()));
        var units = new HashMap<String, TypeSpec>();
        for (TypeSpec spec : NodeIdEncoderClassGenerator.generate(encoded, PACKAGE, List.of())) {
            units.put(PACKAGE + ".util." + spec.name(), spec);
        }
        for (TypeSpec spec : GraphitronClientExceptionClassGenerator.generate()) {
            units.put(PACKAGE + ".schema." + spec.name(), spec);
        }
        for (TypeSpec spec : ColumnValuesClassGenerator.generate()) {
            units.put(PACKAGE + ".util." + spec.name(), spec);
        }
        harness = EmittedCodeHarness.compileStrictly(units);
        var encoder = harness.load(ENCODER.reflectionName());
        decodeCustomer = encoder.getMethod(NodeIdHelperNames.decodeOrThrow("Customer"), String.class);
        encode = encoder.getMethod("encode", String.class, Object[].class);
    }

    @AfterAll
    static void close() {
        if (harness != null) harness.close();
    }

    private static String id(String typeId, Object... values) throws Exception {
        return (String) encode.invoke(null, typeId, values);
    }

    private static Record decoded(String id) throws Throwable {
        try {
            return (Record) decodeCustomer.invoke(null, id);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    @Test
    void anIdOfTheNodeAnswersItsKeyRecord() throws Throwable {
        assertThat(decoded(id("Customer", 1)).get(0)).isEqualTo(1);
    }

    @Test
    void aMalformedIdIsTheNodesMalformedError() {
        assertThatThrownBy(() -> decoded("not-a-node-id"))
            .satisfies(e -> assertThat(e.getClass().getSimpleName()).isEqualTo("GraphitronClientException"))
            .hasMessage("Invalid node id \"not-a-node-id\" for this argument: not a valid Customer id");
    }

    @Test
    void anIdOfTheNodeWhoseKeyDoesNotDecodeIsTheNodesMalformedError() throws Exception {
        String text = id("Customer", "abc");
        assertThatThrownBy(() -> decoded(text))
            .hasMessage("Invalid node id \"" + text + "\" for this argument: not a valid Customer id");
        String overArity = id("Customer", 1, 2);
        assertThatThrownBy(() -> decoded(overArity))
            .hasMessage("Invalid node id \"" + overArity + "\" for this argument: not a valid Customer id");
    }

    @Test
    void anIdOfAnotherTypeNamesTheTypeItDecodesTo() throws Exception {
        String film = id("F", 7);
        assertThatThrownBy(() -> decoded(film))
            .hasMessage("Invalid node id \"" + film + "\" for this argument: decodes to type \"F\", expected a"
                + " Customer id");
    }
}
