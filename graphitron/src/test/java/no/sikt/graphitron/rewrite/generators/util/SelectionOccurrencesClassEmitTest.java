package no.sikt.graphitron.rewrite.generators.util;

import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import javax.lang.model.element.Modifier;
import java.util.List;
import java.util.Optional;

import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_OUTPUT_PACKAGE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Emit-side pin for the {@code SelectionOccurrences} helper class.
 *
 * <p>The helper is emitted once per code-generation run via
 * {@link SelectionOccurrencesClassGenerator#generate(String)} and lands at
 * {@code <outputPackage>.util.SelectionOccurrences}, alongside {@code PolymorphicSelectionSet}.
 * Downstream the compilation tier (the {@code graphitron-sakila-example} module) compiles the
 * emitted source against the real graphql-java {@code SelectedField} interface, and the execution
 * tier drives the merge/guard semantics through real connection queries; this pipeline-tier pin
 * covers the public-API contract the generated {@code <Type>.$fields} loop leans on: the
 * occurrence-merge static and the two consistency guards, with stable names and signatures.
 */
@PipelineTier
class SelectionOccurrencesClassEmitTest {

    private static final String SELECTED_FIELD_LIST = "java.util.List<graphql.schema.SelectedField>";
    private static final String GROUPED_MAP =
        "java.util.Map<java.lang.String, java.util.List<graphql.schema.SelectedField>>";

    @Test
    void generatesOnePublicFinalClassNamedSelectionOccurrences() {
        List<TypeSpec> specs = SelectionOccurrencesClassGenerator.generate(DEFAULT_OUTPUT_PACKAGE);
        assertThat(specs).hasSize(1);

        TypeSpec spec = specs.get(0);
        assertThat(spec.name()).isEqualTo("SelectionOccurrences");
        assertThat(spec.modifiers())
            .as("emitted as public final to mirror the PolymorphicSelectionSet / ConnectionHelper "
                + "shape and to keep external code from sub-classing the scaffold")
            .contains(Modifier.PUBLIC, Modifier.FINAL);
    }

    @Test
    void carriesMergeByResultKeyWithExpectedSignature() {
        MethodSpec m = method("mergeByResultKey");
        assertThat(m.modifiers()).contains(Modifier.PUBLIC, Modifier.STATIC);
        assertThat(m.returnType().toString())
            .as("returns the same grouped-map shape getFieldsGroupedByResultKey() produces, so the "
                + "merged map drops into the $fields switch loop unchanged")
            .isEqualTo(GROUPED_MAP);
        assertThat(m.parameters()).hasSize(1);
        assertThat(m.parameters().get(0).type().toString()).isEqualTo(SELECTED_FIELD_LIST);
        assertThat(m.parameters().get(0).name()).isEqualTo("occurrences");
    }

    @Test
    void carriesCanonicalGuardWithExpectedSignature() {
        MethodSpec m = method("canonical");
        assertThat(m.modifiers()).contains(Modifier.PUBLIC, Modifier.STATIC);
        assertThat(m.returnType().toString())
            .as("returns the canonical SelectedField the switch dispatches on and arms read "
                + "arguments off")
            .isEqualTo("graphql.schema.SelectedField");
        assertThat(m.parameters()).extracting(p -> p.type().toString())
            .containsExactly("java.lang.String", SELECTED_FIELD_LIST);
        assertThat(m.parameters()).extracting(no.sikt.graphitron.javapoet.ParameterSpec::name)
            .containsExactly("resultKey", "occurrences");
    }

    @Test
    void carriesArgumentGuardWithExpectedSignature() {
        MethodSpec m = method("requireConsistentArguments");
        assertThat(m.modifiers()).contains(Modifier.PUBLIC, Modifier.STATIC);
        assertThat(m.returnType().toString()).isEqualTo("void");
        assertThat(m.parameters()).extracting(p -> p.type().toString())
            .containsExactly("java.lang.String", SELECTED_FIELD_LIST);
        assertThat(m.parameters()).extracting(no.sikt.graphitron.javapoet.ParameterSpec::name)
            .containsExactly("resultKey", "occurrences");
    }

    @Test
    void carriesPrivateConstructor() {
        TypeSpec spec = SelectionOccurrencesClassGenerator.generate(DEFAULT_OUTPUT_PACKAGE).get(0);

        Optional<MethodSpec> ctor = spec.methodSpecs().stream()
            .filter(MethodSpec::isConstructor)
            .findFirst();
        assertThat(ctor).as("private no-arg constructor pins the static-utility shape").isPresent();
        assertThat(ctor.get().modifiers()).contains(Modifier.PRIVATE);
        assertThat(ctor.get().parameters()).isEmpty();
    }

    private static MethodSpec method(String name) {
        return SelectionOccurrencesClassGenerator.generate(DEFAULT_OUTPUT_PACKAGE).get(0).methodSpecs().stream()
            .filter(m -> m.name().equals(name))
            .findFirst()
            .orElseThrow(() -> new AssertionError("method not found: " + name));
    }
}
