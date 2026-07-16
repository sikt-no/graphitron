package no.sikt.graphitron.rewrite.generators.util;

import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import javax.lang.model.element.Modifier;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Emit-side pin for the {@code PolymorphicSelectionSet} helper class.
 *
 * <p>The helper is emitted once per code-generation run via
 * {@link PolymorphicSelectionSetClassGenerator#generate()} and lands at
 * {@code <outputPackage>.util.PolymorphicSelectionSet}, alongside {@code ConnectionHelper}.
 * Downstream the compilation tier (the {@code graphitron-sakila-example} module) compiles
 * the emitted source against the real graphql-java {@code DataFetchingFieldSelectionSet}
 * interface, so any drift between the wrapper's declared method set and the interface's
 * method set fails the build before this pin runs.
 *
 * <p>This pipeline-tier pin covers the public-API contract of the helper: a single public
 * static {@code restrictTo(DataFetchingFieldSelectionSet, String)} factory returning a
 * {@code DataFetchingFieldSelectionSet}. The factory is the one symbol consumers (the
 * generators package) reference, and the {@link no.sikt.graphitron.rewrite.generators.PolymorphicProjectionFilterPinTest}
 * source-level pin counts on the symbol's name being stable.
 */
@PipelineTier
class PolymorphicSelectionSetClassEmitTest {

    @Test
    void generatesOnePublicFinalClassNamedPolymorphicSelectionSet() {
        List<TypeSpec> specs = PolymorphicSelectionSetClassGenerator.generate();
        assertThat(specs).hasSize(1);

        TypeSpec spec = specs.get(0);
        assertThat(spec.name()).isEqualTo("PolymorphicSelectionSet");
        assertThat(spec.modifiers())
            .as("emitted as public final to mirror the ConnectionHelper / OrderByResult shape and "
                + "to keep external code from sub-classing the wrapper")
            .contains(Modifier.PUBLIC, Modifier.FINAL);
    }

    @Test
    void carriesRestrictToFactoryWithExpectedSignature() {
        TypeSpec spec = PolymorphicSelectionSetClassGenerator.generate().get(0);

        Optional<MethodSpec> restrictTo = spec.methodSpecs().stream()
            .filter(m -> m.name().equals("restrictTo"))
            .findFirst();
        assertThat(restrictTo).as("public static restrictTo factory").isPresent();

        MethodSpec m = restrictTo.get();
        assertThat(m.modifiers()).contains(Modifier.PUBLIC, Modifier.STATIC);
        assertThat(m.returnType().toString())
            .as("returns the wire-format interface so the wrapped view drops into the existing "
                + "$fields contract without widening any caller signature")
            .isEqualTo("graphql.schema.DataFetchingFieldSelectionSet");
        assertThat(m.parameters()).hasSize(2);
        assertThat(m.parameters().get(0).type().toString())
            .isEqualTo("graphql.schema.DataFetchingFieldSelectionSet");
        assertThat(m.parameters().get(0).name()).isEqualTo("source");
        assertThat(m.parameters().get(1).type().toString())
            .isEqualTo("java.lang.String");
        assertThat(m.parameters().get(1).name()).isEqualTo("concreteTypeName");
    }

    @Test
    void carriesPrivateConstructor() {
        TypeSpec spec = PolymorphicSelectionSetClassGenerator.generate().get(0);

        Optional<MethodSpec> ctor = spec.methodSpecs().stream()
            .filter(MethodSpec::isConstructor)
            .findFirst();
        assertThat(ctor).as("private no-arg constructor pins the static-factory shape").isPresent();
        assertThat(ctor.get().modifiers()).contains(Modifier.PRIVATE);
        assertThat(ctor.get().parameters()).isEmpty();
    }

    @Test
    void carriesFilteredNestedType() {
        TypeSpec spec = PolymorphicSelectionSetClassGenerator.generate().get(0);

        Optional<TypeSpec> filtered = spec.typeSpecs().stream()
            .filter(t -> t.name().equals("Filtered"))
            .findFirst();
        assertThat(filtered)
            .as("private static final nested class that implements DataFetchingFieldSelectionSet "
                + "and materially overrides getFieldsGroupedByResultKey()")
            .isPresent();
        assertThat(filtered.get().modifiers())
            .contains(Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL);
        assertThat(filtered.get().superinterfaces())
            .anyMatch(t -> t.toString().equals("graphql.schema.DataFetchingFieldSelectionSet"));
    }
}
