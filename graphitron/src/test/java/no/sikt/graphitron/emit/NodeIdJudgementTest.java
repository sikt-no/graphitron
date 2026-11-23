package no.sikt.graphitron.emit;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.TypeSpec;
import no.sikt.graphitron.model.test.FactGraphs;
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
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link NodeIdJudgement}'s {@code typenameOf}, compiled strictly with the generated {@code NodeIdEncoder}
 * over the {@code scenarios/node-served-by-id} fact document and run: an id of a node served by id
 * answers its typename, under its declared type id where it has one; an id that cannot be read as a
 * node id, and one of a served node whose key does not decode, are the client error; an id of a stub,
 * of a node whose id reads a column, or of a type the graph does not declare is {@code null}.
 */
@PipelineTier
class NodeIdJudgementTest {

    @RegisterExtension
    static final FactGraphs.Reader FACTS = FactGraphs.reader();

    private static final String PACKAGE = "nodejudgement";
    private static final ClassName ENCODER = ClassName.get(PACKAGE + ".util", NodeIdEncoderClassGenerator.CLASS_NAME);

    private static EmittedCodeHarness harness;
    private static Method judge;
    private static Method encode;

    @BeforeAll
    static void compile() throws Exception {
        var served = FACTS.read("scenarios/node-served-by-id", graph -> NodeQuery.read(graph.handle()));
        var encoded = FACTS.read("scenarios/node-served-by-id", graph -> NodeIdEncoderQuery.read(graph.handle()));
        var host = TypeSpec.classBuilder("Probes").addModifiers(Modifier.PUBLIC)
            .addMethod(NodeIdJudgement.typenameOf(served, new NodeIdDecodeEmitter.Runtime(ENCODER, PACKAGE)))
            // The judgement is private; a public entry calls it so the test can reach it.
            .addMethod(MethodSpec.methodBuilder("probe").addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .returns(String.class)
                .addParameter(String.class, "id")
                .addStatement("return $L(id)", NodeIdJudgement.METHOD)
                .build());
        var units = new HashMap<String, TypeSpec>();
        units.put(PACKAGE + ".Probes", host.build());
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
        judge = harness.load(PACKAGE + ".Probes").getMethod("probe", String.class);
        encode = harness.load(ENCODER.reflectionName()).getMethod("encode", String.class, Object[].class);
    }

    @AfterAll
    static void close() {
        if (harness != null) harness.close();
    }

    private static String id(String typeId, Object... values) throws Exception {
        return (String) encode.invoke(null, typeId, values);
    }

    private static String judged(String id) throws Throwable {
        try {
            return (String) judge.invoke(null, id);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    @Test
    void anIdOfANodeServedByIdAnswersItsTypename() throws Throwable {
        assertThat(judged(id("Customer", 1))).isEqualTo("Customer");
    }

    @Test
    void aDeclaredTypeIdAnswersTheTypeItNames() throws Throwable {
        assertThat(judged(id("F", 7))).isEqualTo("Film");
    }

    @Test
    void anIdThatIsNotBase64IsTheUndecodableError() {
        assertThatThrownBy(() -> judged("not-a-valid-base64-id"))
            .satisfies(e -> assertThat(e.getClass().getSimpleName()).isEqualTo("GraphitronClientException"))
            .hasMessage("Invalid node id \"not-a-valid-base64-id\" for this argument: not a valid node id");
    }

    @Test
    void anIdWithAnEmptyTypeIdIsTheUndecodableError() {
        String empty = Base64.getUrlEncoder().withoutPadding().encodeToString(":1".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> judged(empty))
            .hasMessage("Invalid node id \"" + empty + "\" for this argument: not a valid node id");
    }

    @Test
    void aServedNodesIdWhoseKeyDoesNotDecodeIsThatTypesMalformedError() throws Exception {
        String text = id("Customer", "abc");
        assertThatThrownBy(() -> judged(text))
            .hasMessage("Invalid node id \"" + text + "\" for this argument: not a valid Customer id");
        String overArity = id("F", 1, 2);
        assertThatThrownBy(() -> judged(overArity))
            .hasMessage("Invalid node id \"" + overArity + "\" for this argument: not a valid Film id");
    }

    @Test
    void anIdOfATypeNotServedByIdIsNull_wellFormedOrNot() throws Throwable {
        // A stub another subgraph owns, its key whatever it is.
        assertThat(judged(id("StudyLevel", "BACHELOR"))).isNull();
        assertThat(judged(id("StudyLevel", "a", "b", "c"))).isNull();
        // A node whose id reads a column, so no id of it is fetched by.
        assertThat(judged(id("Address", 1))).isNull();
        // A type this graph does not declare.
        assertThat(judged(id("Elsewhere", 1))).isNull();
    }
}
