package no.sikt.graphitron.rewrite;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;

/**
 * Unit coverage for {@link ArgBindingMap#parseArgMapping} (the mini-DSL parser) and
 * {@link ArgBindingMap#of} (the axis-agnostic factory). The reflect-side typo guard and
 * Table&lt;?&gt;-slot rejection live in {@link ServiceCatalogTest}; the SDL-driven cases are in
 * {@link GraphitronSchemaBuilderTest}.
 */
@UnitTier
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
    void parseArgMapping_singleEntry_parsesAsOneSegmentChain() {
        var result = ArgBindingMap.parseArgMapping("inputs: input");
        assertThat(result).isInstanceOf(ArgBindingMap.ParsedArgMapping.Ok.class);
        assertThat(((ArgBindingMap.ParsedArgMapping.Ok) result).overrides())
            .containsExactly(Map.entry("inputs", List.of("input")));
    }

    @Test
    void parseArgMapping_multipleEntries_preservesOrder() {
        var result = ArgBindingMap.parseArgMapping("city: cityNames, country: countryId");
        assertThat(((ArgBindingMap.ParsedArgMapping.Ok) result).overrides())
            .containsExactly(
                Map.entry("city", List.of("cityNames")),
                Map.entry("country", List.of("countryId")));
    }

    @Test
    void parseArgMapping_textBlockWithNewlines_isAccepted() {
        var result = ArgBindingMap.parseArgMapping("""
            city: cityNames,
            country: countryId
            """);
        assertThat(((ArgBindingMap.ParsedArgMapping.Ok) result).overrides())
            .containsExactly(
                Map.entry("city", List.of("cityNames")),
                Map.entry("country", List.of("countryId")));
    }

    @Test
    void parseArgMapping_dottedRhs_parsesAsSegmentChain() {
        var result = ArgBindingMap.parseArgMapping(
            "kvotesporsmal: input.kvotesporsmalId, algoritme: input.kvotesporsmalAlgoritmeId");
        assertThat(((ArgBindingMap.ParsedArgMapping.Ok) result).overrides())
            .containsExactly(
                Map.entry("kvotesporsmal", List.of("input", "kvotesporsmalId")),
                Map.entry("algoritme", List.of("input", "kvotesporsmalAlgoritmeId")));
    }

    @Test
    void parseArgMapping_emptyJavaParam_isParseError() {
        var result = ArgBindingMap.parseArgMapping(": input");
        assertThat(result).isInstanceOf(ArgBindingMap.ParsedArgMapping.ParseError.class);
        assertThat(((ArgBindingMap.ParsedArgMapping.ParseError) result).message())
            .contains("argMapping syntax error")
            .contains("expected an entry key");
    }

    @Test
    void parseArgMapping_redundantCommas_areInsignificantWhitespace() {
        // Standard GraphQL convention: commas are insignificant whitespace. The R53-era
        // string-split parser rejected "a: b, , c: d" as an empty entry; the lexer-based parser
        // treats redundant commas as just more whitespace and accepts the input. This is a
        // small UX relaxation that matches Apollo Connectors' parsing behavior.
        var result = ArgBindingMap.parseArgMapping("inputs: input, , dryRun: dryRun");
        assertThat(result).isInstanceOf(ArgBindingMap.ParsedArgMapping.Ok.class);
        assertThat(((ArgBindingMap.ParsedArgMapping.Ok) result).overrides())
            .containsExactly(
                Map.entry("inputs", List.of("input")),
                Map.entry("dryRun", List.of("dryRun")));
    }

    @Test
    void parseArgMapping_missingColon_isParseError() {
        var result = ArgBindingMap.parseArgMapping("inputs input");
        assertThat(result).isInstanceOf(ArgBindingMap.ParsedArgMapping.ParseError.class);
        assertThat(((ArgBindingMap.ParsedArgMapping.ParseError) result).message())
            .contains("argMapping syntax error")
            .contains("expected ':'");
    }

    @Test
    void parseArgMapping_missingValueAfterColon_isParseError() {
        var result = ArgBindingMap.parseArgMapping("inputs:");
        assertThat(result).isInstanceOf(ArgBindingMap.ParsedArgMapping.ParseError.class);
        assertThat(((ArgBindingMap.ParsedArgMapping.ParseError) result).message())
            .contains("argMapping syntax error")
            .contains("expected a value name after ':'");
    }

    @Test
    void parseArgMapping_emptyPathSegment_isParseError() {
        var result = ArgBindingMap.parseArgMapping("k: input..foo");
        assertThat(result).isInstanceOf(ArgBindingMap.ParsedArgMapping.ParseError.class);
        assertThat(((ArgBindingMap.ParsedArgMapping.ParseError) result).message())
            .contains("argMapping syntax error")
            .contains("empty path segment");
    }

    @Test
    void parseArgMapping_duplicateJavaTarget_isParseError() {
        var result = ArgBindingMap.parseArgMapping("inputs: input, inputs: extras");
        assertThat(result).isInstanceOf(ArgBindingMap.ParsedArgMapping.ParseError.class);
        assertThat(((ArgBindingMap.ParsedArgMapping.ParseError) result).message())
            .contains("duplicate entries for Java parameter 'inputs'");
    }

    // ===== of(Set<String>, Map<String, String>) =====

    @Test
    void of_emptyOverrides_returnsIdentityForEveryArgName() {
        var result = ArgBindingMap.of(Set.of("a", "b"), Map.of());
        assertThat(result).isInstanceOf(ArgBindingMap.Result.Ok.class);
        var map = ((ArgBindingMap.Result.Ok) result).map().byJavaName();
        assertThat(map)
            .containsEntry("a", PathExpr.head("a"))
            .containsEntry("b", PathExpr.head("b"));
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
            "dryRun", PathExpr.head("dryRun"),
            "inputs", PathExpr.head("input")));
    }

    @Test
    void of_twoOverridesBindingToSameSlot_areBothPresent() {
        // R53 spec example: argMapping "a: x, b: x" against slot {x} produces {a: x, b: x}.
        // The Java method has parameters `a` and `b`, both receiving the value of GraphQL arg `x`.
        var result = ArgBindingMap.of(Set.of("x"),
            new java.util.LinkedHashMap<>(Map.of("a", "x", "b", "x")));
        var map = ((ArgBindingMap.Result.Ok) result).map().byJavaName();
        assertThat(map)
            .containsEntry("a", PathExpr.head("x"))
            .containsEntry("b", PathExpr.head("x"));
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
