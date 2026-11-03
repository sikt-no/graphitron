package no.sikt.graphitron.rewrite.generators.util;

import com.palantir.javapoet.ArrayTypeName;
import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.TypeSpec;
import no.sikt.graphitron.model.test.CapturedStore;
import no.sikt.graphitron.model.test.CatalogInput;
import no.sikt.graphitron.plan.GeneratedUnits;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.lang.model.element.Modifier;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Collectors;

import static no.sikt.graphitron.common.configuration.TestConfiguration.testContext;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_RUNTIME_CLASS;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_RUNTIME_MEMBER;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The stated {@code graphitron_runtime_class} and {@code graphitron_runtime_member} rows name what
 * {@link ConnectionResultClassGenerator} and {@link ConnectionHelperClassGenerator} emit: each class
 * at its binary name below the output package, and each member with its staticness and descriptor.
 * The rows are the file's, so this is what keeps them from drifting off the template.
 */
@PipelineTier
class RuntimeClassVocabularyTest {

    private static final String PACKAGE = "no.sikt.example";

    @TempDir
    Path tmp;

    @Test
    void theStatedClassesAndMembersAreTheOnesTheGeneratorEmits() {
        var result = only(ConnectionResultClassGenerator.generate(PACKAGE), ConnectionResultClassGenerator.CLASS_NAME);
        var helper = only(ConnectionHelperClassGenerator.generate(PACKAGE), ConnectionHelperClassGenerator.CLASS_NAME);
        var edge = only(helper.typeSpecs(), "Edge");
        Map<String, TypeSpec> byKind = Map.of(
            "CONNECTION_RESULT", result,
            "CONNECTION_HELPER", helper,
            "CONNECTION_EDGE", edge);
        Map<String, String> relativeName = Map.of(
            "CONNECTION_RESULT", GeneratedUnits.SUB_UTIL + "." + result.name(),
            "CONNECTION_HELPER", GeneratedUnits.SUB_UTIL + "." + helper.name(),
            "CONNECTION_EDGE", GeneratedUnits.SUB_UTIL + "." + helper.name() + "$" + edge.name());

        try (var store = CapturedStore.ofCatalog(tmp, "type Query { x: Int }", CatalogInput.of(testContext()))) {
            var c = GRAPHITRON_RUNTIME_CLASS;
            var classes = store.dsl().select(c.KIND, c.RELATIVE_NAME).from(c).fetchMap(c.KIND, c.RELATIVE_NAME);
            assertThat(classes).isEqualTo(relativeName);

            var members = store.dsl().selectFrom(GRAPHITRON_RUNTIME_MEMBER).fetch();
            assertThat(members).isNotEmpty();
            for (var row : members) {
                var declaring = byKind.get(row.getKind());
                var method = declaring.methodSpecs().stream()
                    .filter(s -> s.name().equals(row.getMemberName()))
                    .filter(s -> descriptor(s).equals(row.getDescriptor()))
                    .findFirst();
                assertThat(method).as("%s.%s%s", row.getKind(), row.getMemberName(), row.getDescriptor()).isPresent();
                assertThat(method.get().modifiers().contains(Modifier.STATIC))
                    .as("%s.%s is static", row.getKind(), row.getMemberName())
                    .isEqualTo(row.getIsStatic());
            }
        }
    }

    private static TypeSpec only(java.util.List<TypeSpec> specs, String name) {
        var named = specs.stream().filter(s -> s.name().equals(name)).collect(Collectors.toList());
        assertThat(named).as(name).hasSize(1);
        return named.get(0);
    }

    private static String descriptor(MethodSpec method) {
        var parameters = method.parameters().stream().map(p -> descriptor(p.type())).collect(Collectors.joining());
        return "(" + parameters + ")" + descriptor(method.returnType());
    }

    private static String descriptor(TypeName type) {
        if (type instanceof ParameterizedTypeName parameterized) return descriptor(parameterized.rawType());
        if (type instanceof ArrayTypeName array) return "[" + descriptor(array.componentType());
        if (type instanceof ClassName name) return "L" + name.reflectionName().replace('.', '/') + ";";
        if (type.equals(TypeName.BOOLEAN)) return "Z";
        if (type.equals(TypeName.INT)) return "I";
        if (type.equals(TypeName.LONG)) return "J";
        if (type.equals(TypeName.VOID)) return "V";
        throw new IllegalArgumentException("no descriptor for " + type);
    }
}
