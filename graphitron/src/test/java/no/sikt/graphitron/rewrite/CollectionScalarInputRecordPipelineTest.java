package no.sikt.graphitron.rewrite;

import com.palantir.javapoet.ArrayTypeName;
import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.TypeSpec;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import java.util.List;

import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_OUTPUT_PACKAGE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Input records over a custom scalar whose {@code Coercing} produces an array or a
 * parameterized list: the emitted component keeps the scalar's full Java type
 * ({@code String[]}, {@code List<String>}) rather than narrowing it. The list case is a
 * non-list SDL field whose component is nonetheless a {@code List}, so it also pins that the
 * component type comes from the scalar, not from SDL list-ness.
 */
@PipelineTier
class CollectionScalarInputRecordPipelineTest {

    private static final String SCALARS = """
        scalar Tags @scalarType(scalar: "no.sikt.graphitron.rewrite.scalarfixture.ScalarConstants.TAGS")
        scalar TagList @scalarType(scalar: "no.sikt.graphitron.rewrite.scalarfixture.ScalarConstants.TAG_LIST")
        """;

    @Test
    void arrayScalarComponent_isStringArray() {
        var spec = onlySpec(SCALARS + """
            input ArrayHolderFilter { tags: Tags }
            type Query { dummy(in: ArrayHolderFilter): String }
            """, "ArrayHolderFilter");

        assertThat(accessorType(spec, "tags")).isEqualTo(ArrayTypeName.of(ClassName.get(String.class)));
    }

    @Test
    void listScalarComponent_isListOfString() {
        var spec = onlySpec(SCALARS + """
            input TagListFilter { tags: TagList }
            type Query { dummy(in: TagListFilter): String }
            """, "TagListFilter");

        assertThat(accessorType(spec, "tags")).isEqualTo(
            ParameterizedTypeName.get(ClassName.get(List.class), ClassName.get(String.class)));
    }

    @Test
    void sdlListOfArrayScalar_isListOfStringArray() {
        var spec = onlySpec(SCALARS + """
            input TagsBatch { tags: [Tags] }
            type Query { dummy(in: TagsBatch): String }
            """, "TagsBatch");

        assertThat(accessorType(spec, "tags")).isEqualTo(
            ParameterizedTypeName.get(ClassName.get(List.class), ArrayTypeName.of(ClassName.get(String.class))));
    }

    private static TypeSpec onlySpec(String sdl, String typeName) {
        var bundle = TestSchemaHelper.buildBundle(sdl);
        List<TypeSpec> specs = InputRecordRenderTestSupport.renderInputRecords(sdl, bundle.model(), DEFAULT_OUTPUT_PACKAGE);
        return specs.stream()
            .filter(t -> t.name().equals(typeName))
            .findFirst()
            .orElseThrow(() -> new AssertionError(
                "Expected input class '" + typeName + "' but got: " + specs.stream().map(TypeSpec::name).toList()));
    }

    private static TypeName accessorType(TypeSpec spec, String component) {
        return spec.methodSpecs().stream()
            .filter(m -> m.name().equals(component) && m.parameters().isEmpty())
            .map(MethodSpec::returnType)
            .findFirst()
            .orElseThrow(() -> new AssertionError("No accessor '" + component + "' on " + spec.name()));
    }
}
