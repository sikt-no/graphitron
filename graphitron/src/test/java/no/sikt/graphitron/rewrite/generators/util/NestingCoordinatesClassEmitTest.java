package no.sikt.graphitron.rewrite.generators.util;

import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.rewrite.NestingReach;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import javax.lang.model.element.Modifier;
import java.util.Map;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Emit-side pin for the {@code NestingCoordinates} helper class: the one public static the
 * spliced readers call, with a stable name and signature whatever the schema, and the schema's
 * coordinate set baked in behind it. The walk's behaviour is pinned at the execution tier, where
 * readers of one nesting type at different depths resolve their own spliced terms.
 */
@PipelineTier
class NestingCoordinatesClassEmitTest {

    private static TypeSpec generate(String... coordinates) {
        var reach = new NestingReach(Map.of(), new TreeSet<>(java.util.List.of(coordinates)));
        var specs = NestingCoordinatesClassGenerator.generate(reach);
        assertThat(specs).hasSize(1);
        return specs.get(0);
    }

    @Test
    void generatesOnePublicFinalClassWithTheRkPathStatic() {
        var spec = generate("Customer.location");
        assertThat(spec.name()).isEqualTo("NestingCoordinates");
        assertThat(spec.modifiers()).contains(Modifier.PUBLIC, Modifier.FINAL);
        MethodSpec rkPath = spec.methodSpecs().stream()
            .filter(m -> m.name().equals("rkPath")).findFirst().orElseThrow();
        assertThat(rkPath.modifiers()).contains(Modifier.PUBLIC, Modifier.STATIC);
        assertThat(rkPath.returnType().toString()).isEqualTo("java.lang.String");
        assertThat(rkPath.parameters()).extracting(p -> p.type().toString())
            .containsExactly("graphql.schema.DataFetchingEnvironment");
    }

    @Test
    void theCoordinateSetIsPrivateAndCarriesEveryEdge() {
        var spec = generate("Store.location", "Customer.location", "OccupantLocation.inner");
        var field = spec.fieldSpecs().stream().filter(f -> f.name().equals("COORDINATES")).findFirst().orElseThrow();
        assertThat(field.modifiers())
            .as("the per-schema set sits behind the fixed public method, so the class's ABI is the"
                + " same for every schema")
            .contains(Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL);
        assertThat(field.initializer().toString())
            .contains("\"Customer.location\"", "\"OccupantLocation.inner\"", "\"Store.location\"");
    }

    @Test
    void anEmptySchemaStillEmitsTheWalk() {
        var spec = generate();
        assertThat(spec.methodSpecs()).extracting(MethodSpec::name).contains("rkPath");
    }
}
