package no.sikt.graphitron.rewrite;

import com.palantir.javapoet.ArrayTypeName;
import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.TypeSpec;
import no.sikt.graphitron.rewrite.test.compile.EmittedCodeHarness;
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
 * component type comes from the scalar, not from SDL list-ness. The {@code fromMap} cases compile
 * the emitted classes with {@link EmittedCodeHarness} and drive them, so the read is pinned by
 * behaviour rather than by the emitted body's text.
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

    /**
     * A non-list SDL field whose scalar coerces to {@code List<String>} takes {@code fromMap}'s
     * list branch, which rebuilds the list element-wise. Drives the compiled emitted class: the
     * value graphql-java's coercing put in the map comes out of the accessor equal.
     */
    @Test
    void listScalarComponent_fromMapRoundTripsTheList() throws Exception {
        String sdl = SCALARS + """
            input TagListFilter { tags: TagList }
            type Query { dummy(in: TagListFilter): String }
            """;
        var tags = List.of("drama", "comedy");
        assertThat(readBack(sdl, "TagListFilter", "tags", tags)).isEqualTo(tags);
        assertThat(readBack(sdl, "TagListFilter", "tags", null)).isNull();
    }

    @Test
    void arrayScalarComponent_fromMapRoundTripsTheArray() throws Exception {
        String sdl = SCALARS + """
            input ArrayHolderFilter { tags: Tags }
            type Query { dummy(in: ArrayHolderFilter): String }
            """;
        var tags = new String[] {"drama", "comedy"};
        assertThat((String[]) readBack(sdl, "ArrayHolderFilter", "tags", tags)).containsExactly("drama", "comedy");
    }

    @Test
    void sdlListOfArrayScalar_fromMapRoundTripsEachArray() throws Exception {
        String sdl = SCALARS + """
            input TagsBatch { tags: [Tags] }
            type Query { dummy(in: TagsBatch): String }
            """;
        var batch = List.of(new String[] {"a"}, new String[] {"b", "c"});
        @SuppressWarnings("unchecked")
        var read = (List<String[]>) readBack(sdl, "TagsBatch", "tags", batch);
        assertThat(read).hasSize(2);
        assertThat(read.get(0)).containsExactly("a");
        assertThat(read.get(1)).containsExactly("b", "c");
    }

    /**
     * Compiles the emitted input records for {@code sdl}, calls {@code typeName.fromMap} with
     * {@code component -> value}, and returns what the {@code component} accessor reads back.
     */
    private static Object readBack(String sdl, String typeName, String component, Object value) throws Exception {
        var bundle = TestSchemaHelper.buildBundle(sdl);
        var units = new java.util.LinkedHashMap<String, TypeSpec>();
        for (TypeSpec spec : InputRecordRenderTestSupport.renderInputRecords(sdl, bundle.model(), DEFAULT_OUTPUT_PACKAGE)) {
            units.put(DEFAULT_OUTPUT_PACKAGE + ".inputs." + spec.name(), spec);
        }
        try (var harness = EmittedCodeHarness.compile(units)) {
            Class<?> cls = harness.load(DEFAULT_OUTPUT_PACKAGE + ".inputs." + typeName);
            var in = new java.util.HashMap<String, Object>();
            in.put(component, value);
            Object record = cls.getMethod("fromMap", java.util.Map.class).invoke(null, in);
            return cls.getMethod(component).invoke(record);
        }
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
