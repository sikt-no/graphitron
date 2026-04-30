package no.sikt.graphitron.rewrite;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit coverage for {@link ArgBindingMap#parseArgMapping} (the mini-DSL parser) and
 * {@link ArgBindingMap#of} (the axis-agnostic factory). The reflect-side typo guard and
 * Table&lt;?&gt;-slot rejection live in {@link ServiceCatalogTest}; the SDL-driven cases are in
 * {@link GraphitronSchemaBuilderTest}.
 */
class ArgBindingMapTest {

    // ===== parseArgMapping =====

    @Test
    void parseArgMapping_emptyOrBlank_returnsEmptyMap() {
        assertThat(ArgBindingMap.parseArgMapping(null))
            .isEqualTo(new ArgBindingMap.ParsedArgMapping.Ok(Map.of()));
        assertThat(ArgBindingMap.parseArgMapping(""))
            .isEqualTo(new ArgBindingMap.ParsedArgMapping.Ok(Map.of()));
        assertThat(ArgBindingMap.parseArgMapping("   \n  "))
            .isEqualTo(new ArgBindingMap.ParsedArgMapping.Ok(Map.of()));
    }

    @Test
    void parseArgMapping_singleEntry_parsesJavaParamAndGraphqlArg() {
        var result = ArgBindingMap.parseArgMapping("inputs: input");
        assertThat(result).isInstanceOf(ArgBindingMap.ParsedArgMapping.Ok.class);
        assertThat(((ArgBindingMap.ParsedArgMapping.Ok) result).overrides())
            .containsExactly(Map.entry("inputs", "input"));
    }

    @Test
    void parseArgMapping_multipleEntries_preservesOrder() {
        var result = ArgBindingMap.parseArgMapping("city: cityNames, country: countryId");
        assertThat(((ArgBindingMap.ParsedArgMapping.Ok) result).overrides())
            .containsExactly(Map.entry("city", "cityNames"), Map.entry("country", "countryId"));
    }

    @Test
    void parseArgMapping_textBlockWithNewlines_isAccepted() {
        var result = ArgBindingMap.parseArgMapping("""
            city: cityNames,
            country: countryId
            """);
        assertThat(((ArgBindingMap.ParsedArgMapping.Ok) result).overrides())
            .containsExactly(Map.entry("city", "cityNames"), Map.entry("country", "countryId"));
    }

    @Test
    void parseArgMapping_missingColon_isParseError() {
        var result = ArgBindingMap.parseArgMapping("inputs input");
        assertThat(result).isInstanceOf(ArgBindingMap.ParsedArgMapping.ParseError.class);
        assertThat(((ArgBindingMap.ParsedArgMapping.ParseError) result).message())
            .contains("missing ':'");
    }

    @Test
    void parseArgMapping_emptyJavaParam_isParseError() {
        var result = ArgBindingMap.parseArgMapping(": input");
        assertThat(result).isInstanceOf(ArgBindingMap.ParsedArgMapping.ParseError.class);
        assertThat(((ArgBindingMap.ParsedArgMapping.ParseError) result).message())
            .contains("empty Java-parameter name");
    }

    @Test
    void parseArgMapping_emptyGraphqlArg_isParseError() {
        var result = ArgBindingMap.parseArgMapping("inputs:");
        assertThat(result).isInstanceOf(ArgBindingMap.ParsedArgMapping.ParseError.class);
        assertThat(((ArgBindingMap.ParsedArgMapping.ParseError) result).message())
            .contains("empty GraphQL-argument name");
    }

    @Test
    void parseArgMapping_duplicateJavaTarget_isParseError() {
        var result = ArgBindingMap.parseArgMapping("inputs: input, inputs: extras");
        assertThat(result).isInstanceOf(ArgBindingMap.ParsedArgMapping.ParseError.class);
        assertThat(((ArgBindingMap.ParsedArgMapping.ParseError) result).message())
            .contains("duplicate entries for Java parameter 'inputs'");
    }

    @Test
    void parseArgMapping_emptyEntryBetweenCommas_isParseError() {
        var result = ArgBindingMap.parseArgMapping("inputs: input, , dryRun: dryRun");
        assertThat(result).isInstanceOf(ArgBindingMap.ParsedArgMapping.ParseError.class);
    }

    // ===== of(Set<String>, Map<String, String>) =====

    @Test
    void of_emptyOverrides_returnsIdentityForEveryArgName() {
        var result = ArgBindingMap.of(Set.of("a", "b"), Map.of());
        assertThat(result).isInstanceOf(ArgBindingMap.Result.Ok.class);
        var map = ((ArgBindingMap.Result.Ok) result).map().byJavaName();
        assertThat(map).containsEntry("a", "a").containsEntry("b", "b");
    }

    @Test
    void of_overrideClaimsSlot_dropsIdentityForThatSlot() {
        // graphqlArgNames = {input, dryRun}; argMapping "inputs: input"
        // Identity for input would conflict with the override's intent (Java param is "inputs",
        // not "input"), so the identity for `input` is dropped. dryRun stays as identity.
        var result = ArgBindingMap.of(
            new java.util.LinkedHashSet<>(java.util.List.of("input", "dryRun")),
            Map.of("inputs", "input"));
        var map = ((ArgBindingMap.Result.Ok) result).map().byJavaName();
        assertThat(map).containsExactlyInAnyOrderEntriesOf(Map.of(
            "dryRun", "dryRun",
            "inputs", "input"));
    }

    @Test
    void of_twoOverridesBindingToSameSlot_areBothPresent() {
        // R53 spec example: argMapping "a: x, b: x" against slot {x} produces {a: x, b: x}.
        // The Java method has parameters `a` and `b`, both receiving the value of GraphQL arg `x`.
        var result = ArgBindingMap.of(Set.of("x"),
            new java.util.LinkedHashMap<>(Map.of("a", "x", "b", "x")));
        var map = ((ArgBindingMap.Result.Ok) result).map().byJavaName();
        assertThat(map).containsEntry("a", "x").containsEntry("b", "x");
    }

    @Test
    void of_overrideValueNotInArgNames_returnsUnknownArgRef() {
        var result = ArgBindingMap.of(Set.of("input", "dryRun"), Map.of("inputs", "notAnArg"));
        assertThat(result).isInstanceOf(ArgBindingMap.Result.UnknownArgRef.class);
        assertThat(((ArgBindingMap.Result.UnknownArgRef) result).message())
            .contains("argMapping entry 'inputs: notAnArg'")
            .contains("references GraphQL argument 'notAnArg'")
            .contains("dryRun")
            .contains("input");
    }

    @Test
    void of_pathStepEmptySlots_emptyOverrides_isOk() {
        // Path-step @condition: no GraphQL arguments are in scope; with no argMapping the result
        // is the empty binding, identical to ArgBindingMap.empty().
        var result = ArgBindingMap.of(Set.of(), Map.of());
        assertThat(result).isInstanceOf(ArgBindingMap.Result.Ok.class);
        assertThat(((ArgBindingMap.Result.Ok) result).map().byJavaName()).isEmpty();
    }

    @Test
    void of_pathStepEmptySlots_anyOverride_isUnknownArgRef() {
        // Path-step @condition with argMapping: every override's GraphQL-source is unknown
        // because the slot set is empty.
        var result = ArgBindingMap.of(Set.of(), Map.of("javaParam", "anyArg"));
        assertThat(result).isInstanceOf(ArgBindingMap.Result.UnknownArgRef.class);
        assertThat(((ArgBindingMap.Result.UnknownArgRef) result).message())
            .contains("argMapping entry 'javaParam: anyArg'");
    }
}
