package no.sikt.graphitron.rewrite.generators.util;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.FieldSpec;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.TypeSpec;

import javax.lang.model.element.Modifier;
import java.util.List;

/**
 * Generates the {@code GraphitronValues} utility class, emitted once per code-generation run
 * alongside other rewrite output.
 *
 * <p>The class contains a single constant:
 * <pre>{@code
 * public static final Field<Integer> GRAPHITRON_INPUT_IDX =
 *     DSL.field("graphitron_input_idx", Integer.class);
 * }</pre>
 *
 * <p>This field is the synthetic row-number column included in every derived VALUES table produced
 * by the lookup pipeline. Using a shared constant ensures that the field reference used when
 * constructing the derived table and the reference used in the join condition are identical —
 * no string aliases, no implicit unnamed dependencies.
 *
 * <p>Generated as a source file rather than shipped as a library dependency so that consuming
 * projects have no runtime dependency on Graphitron itself.
 */
public class GraphitronValuesClassGenerator {

    static final String CLASS_NAME = "GraphitronValues";

    private static final ClassName DSL = ClassName.get("org.jooq.impl", "DSL");
    private static final ClassName FIELD = ClassName.get("org.jooq", "Field");

    public static List<TypeSpec> generate() {
        var fieldType = ParameterizedTypeName.get(FIELD, ClassName.get(Integer.class));
        var idxField = FieldSpec.builder(fieldType, "GRAPHITRON_INPUT_IDX", Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
            .initializer("$T.field($S, $T.class)", DSL, "graphitron_input_idx", Integer.class)
            .build();

        return List.of(TypeSpec.classBuilder(CLASS_NAME)
            .addModifiers(Modifier.PUBLIC)
            .addField(idxField)
            .build());
    }
}
