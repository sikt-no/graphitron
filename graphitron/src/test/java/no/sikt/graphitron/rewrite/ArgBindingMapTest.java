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
        // Standard GraphQL convention: commas are insignificant whitespace. The earlier
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

    // ===== of(Map<String, GraphQLInputType>, Map<String, List<String>>) =====

    /** Minimal scalar slot: the slot type doesn't matter for single-segment overrides. */
    private static java.util.Map<String, graphql.schema.GraphQLInputType> scalarSlots(String... names) {
        var out = new java.util.LinkedHashMap<String, graphql.schema.GraphQLInputType>();
        for (var n : names) out.put(n, graphql.Scalars.GraphQLString);
        return out;
    }

    /** Build a single-segment override map: java -> [graphqlArg] (no path tail). */
    private static java.util.Map<String, java.util.List<String>> headOverrides(java.util.Map<String, String> raw) {
        var out = new java.util.LinkedHashMap<String, java.util.List<String>>();
        raw.forEach((k, v) -> out.put(k, java.util.List.of(v)));
        return out;
    }

    @Test
    void of_emptyOverrides_returnsIdentityForEveryArgName() {
        var result = ArgBindingMap.of(scalarSlots("a", "b"), Map.of());
        assertThat(result).isInstanceOf(ArgBindingMap.Result.Ok.class);
        var map = ((ArgBindingMap.Result.Ok) result).map().byJavaName();
        assertThat(map)
            .containsEntry("a", PathExpr.head("a"))
            .containsEntry("b", PathExpr.head("b"));
    }

    @Test
    void of_overrideClaimsSlot_dropsIdentityForThatSlot() {
        // slots = {input, dryRun}; argMapping "inputs: input"
        // Identity for input would conflict with the override's intent (Java param is "inputs",
        // not "input"), so the identity for `input` is dropped. dryRun stays as identity.
        var result = ArgBindingMap.of(
            scalarSlots("input", "dryRun"),
            headOverrides(Map.of("inputs", "input")));
        var map = ((ArgBindingMap.Result.Ok) result).map().byJavaName();
        assertThat(map).containsExactlyInAnyOrderEntriesOf(Map.of(
            "dryRun", PathExpr.head("dryRun"),
            "inputs", PathExpr.head("input")));
    }

    @Test
    void of_twoOverridesBindingToSameSlot_areBothPresent() {
        // Spec example: argMapping "a: x, b: x" against slot {x} produces {a: x, b: x}.
        // The Java method has parameters `a` and `b`, both receiving the value of GraphQL arg `x`.
        var result = ArgBindingMap.of(scalarSlots("x"),
            headOverrides(new java.util.LinkedHashMap<>(Map.of("a", "x", "b", "x"))));
        var map = ((ArgBindingMap.Result.Ok) result).map().byJavaName();
        assertThat(map)
            .containsEntry("a", PathExpr.head("x"))
            .containsEntry("b", PathExpr.head("x"));
    }

    @Test
    void of_overrideValueNotInArgNames_returnsUnknownArgRef() {
        var result = ArgBindingMap.of(scalarSlots("input", "dryRun"),
            headOverrides(Map.of("inputs", "notAnArg")));
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
        var result = ArgBindingMap.of(java.util.Map.of(), Map.of());
        assertThat(result).isInstanceOf(ArgBindingMap.Result.Ok.class);
        assertThat(((ArgBindingMap.Result.Ok) result).map().byJavaName()).isEmpty();
    }

    @Test
    void of_pathStepEmptySlots_anyOverride_isUnknownArgRef() {
        // Path-step @condition with argMapping: every override's GraphQL-source is unknown
        // because the slot set is empty.
        var result = ArgBindingMap.of(java.util.Map.of(),
            headOverrides(Map.of("javaParam", "anyArg")));
        assertThat(result).isInstanceOf(ArgBindingMap.Result.UnknownArgRef.class);
        assertThat(((ArgBindingMap.Result.UnknownArgRef) result).message())
            .contains("argMapping entry 'javaParam: anyArg'");
    }

    // ===== of: path-expression resolution =====

    @Test
    void of_singleSegmentPath_buildsHead() {
        // {kvotesporsmal: input}: head-only path, like a single-name override.
        var slot = new java.util.LinkedHashMap<String, graphql.schema.GraphQLInputType>();
        slot.put("input", graphql.schema.GraphQLInputObjectType.newInputObject()
            .name("InputT").field(graphql.schema.GraphQLInputObjectField.newInputObjectField()
                .name("foo").type(graphql.Scalars.GraphQLString).build()).build());
        var result = ArgBindingMap.of(slot, Map.of("kvotesporsmal", java.util.List.of("input")));
        var map = ((ArgBindingMap.Result.Ok) result).map().byJavaName();
        assertThat(map.get("kvotesporsmal")).isEqualTo(PathExpr.head("input"));
    }

    @Test
    void of_twoSegmentPath_buildsStepChainWithLiftsListFalse() {
        // input: InputT { foo: String }; argMapping kv: input.foo
        // Step "foo" walks from InputT into a scalar field; liftsList=false.
        var inputType = graphql.schema.GraphQLInputObjectType.newInputObject()
            .name("InputT").field(graphql.schema.GraphQLInputObjectField.newInputObjectField()
                .name("foo").type(graphql.Scalars.GraphQLString).build()).build();
        var slot = new java.util.LinkedHashMap<String, graphql.schema.GraphQLInputType>();
        slot.put("input", inputType);
        var result = ArgBindingMap.of(slot, Map.of("kv", java.util.List.of("input", "foo")));
        var map = ((ArgBindingMap.Result.Ok) result).map().byJavaName();
        assertThat(map.get("kv"))
            .isEqualTo(PathExpr.step(PathExpr.head("input"), "foo", false));
    }

    @Test
    void of_listShapedFieldType_setsLiftsListTrue() {
        // input: InputT { bs: [B] }; argMapping kv: input.bs
        // Step "bs" walks into a list-shaped field; liftsList=true.
        var bType = graphql.schema.GraphQLInputObjectType.newInputObject()
            .name("BT").field(graphql.schema.GraphQLInputObjectField.newInputObjectField()
                .name("c").type(graphql.Scalars.GraphQLString).build()).build();
        var inputType = graphql.schema.GraphQLInputObjectType.newInputObject()
            .name("InputT").field(graphql.schema.GraphQLInputObjectField.newInputObjectField()
                .name("bs").type(graphql.schema.GraphQLList.list(bType)).build()).build();
        var slot = new java.util.LinkedHashMap<String, graphql.schema.GraphQLInputType>();
        slot.put("input", inputType);
        var result = ArgBindingMap.of(slot, Map.of("kv", java.util.List.of("input", "bs")));
        var map = ((ArgBindingMap.Result.Ok) result).map().byJavaName();
        assertThat(map.get("kv"))
            .isEqualTo(PathExpr.step(PathExpr.head("input"), "bs", true));
    }

    @Test
    void of_walkThroughScalar_returnsPathRejected() {
        // input: InputT { foo: String }; argMapping kv: input.foo.bar
        // A String has nothing to open, so the dot rejects. The rule the widening below is a case
        // of, not an exception to.
        var inputType = graphql.schema.GraphQLInputObjectType.newInputObject()
            .name("InputT").field(graphql.schema.GraphQLInputObjectField.newInputObjectField()
                .name("foo").type(graphql.Scalars.GraphQLString).build()).build();
        var slot = new java.util.LinkedHashMap<String, graphql.schema.GraphQLInputType>();
        slot.put("input", inputType);
        var result = ArgBindingMap.of(slot, Map.of("kv", java.util.List.of("input", "foo", "bar")));
        assertThat(result).isInstanceOf(ArgBindingMap.Result.PathRejected.class);
        assertThat(((ArgBindingMap.Result.PathRejected) result).message())
            .contains("opens scalar 'String'")
            .contains("at segment 'foo'")
            .contains("has nothing to open")
            .as("the message states both openable kinds, so an author reading it learns the rule")
            .contains("an ID carrying @nodeId");
    }

    // ===== The key-column segment: one dot past an ID opens the node type's key =====

    /**
     * The widening, at its narrowest useful shape: one segment past an {@code ID}-typed input field
     * resolves to an ordinary trailing {@link PathExpr.Step}. Admitted and carried, never
     * interpreted: which key column it names is the store's resolution and the plan's read, and the
     * walk runs before either exists.
     */
    @Test
    void of_oneSegmentPastAnId_resolvesToATrailingStep() {
        var inputType = graphql.schema.GraphQLInputObjectType.newInputObject()
            .name("InputT").field(graphql.schema.GraphQLInputObjectField.newInputObjectField()
                .name("inventoryId").type(graphql.Scalars.GraphQLID).build()).build();
        var slot = new java.util.LinkedHashMap<String, graphql.schema.GraphQLInputType>();
        slot.put("input", inputType);
        var result = ArgBindingMap.of(slot,
            Map.of("kv", java.util.List.of("input", "inventoryId", "inventory_id")));
        assertThat(((ArgBindingMap.Result.Ok) result).map().byJavaName().get("kv"))
            .isEqualTo(PathExpr.step(
                PathExpr.step(PathExpr.head("input"), "inventoryId", false),
                "inventory_id", false));
    }

    /**
     * An {@code ID} argument opened directly, with no input object above it. The head's own type is
     * what opens here, and the rule reads it through the same slot map every other position does,
     * which is why the widening asks about the type rather than about the directive: a slot map
     * carries no directives to ask.
     */
    @Test
    void of_oneSegmentPastAnIdArgumentHead_resolvesToATrailingStep() {
        var slot = new java.util.LinkedHashMap<String, graphql.schema.GraphQLInputType>();
        slot.put("inventoryId", graphql.Scalars.GraphQLID);
        var result = ArgBindingMap.of(slot,
            Map.of("kv", java.util.List.of("inventoryId", "inventory_id")));
        assertThat(((ArgBindingMap.Result.Ok) result).map().byJavaName().get("kv"))
            .isEqualTo(PathExpr.step(PathExpr.head("inventoryId"), "inventory_id", false));
    }

    /**
     * Two segments past an {@code ID} stays rejected, and that boundary is load-bearing: the store's
     * defect view deliberately has no arm for it, on the stated ground that the walk keeps rejecting
     * it, so an arm there would double-report. If this ever passed, that silence would become a
     * hole.
     */
    @Test
    void of_twoSegmentsPastAnId_returnsPathRejected() {
        var inputType = graphql.schema.GraphQLInputObjectType.newInputObject()
            .name("InputT").field(graphql.schema.GraphQLInputObjectField.newInputObjectField()
                .name("inventoryId").type(graphql.Scalars.GraphQLID).build()).build();
        var slot = new java.util.LinkedHashMap<String, graphql.schema.GraphQLInputType>();
        slot.put("input", inputType);
        var result = ArgBindingMap.of(slot,
            Map.of("kv", java.util.List.of("input", "inventoryId", "inventory_id", "nope")));
        assertThat(result).isInstanceOf(ArgBindingMap.Result.PathRejected.class);
        assertThat(((ArgBindingMap.Result.PathRejected) result).message())
            .contains("opens the ID at segment 'inventoryId' with 'inventory_id'")
            .contains("exactly one key column");
    }

    /**
     * A list of node ids has no single key to project a column out of, so the dot rejects there
     * rather than admitting a shape whose emitted read would be a list where a routine parameter
     * wants a value. Structural and locally decidable, which is what makes it the walk's to state
     * rather than the store's.
     */
    @Test
    void of_oneSegmentPastAListOfIds_returnsPathRejected() {
        var inputType = graphql.schema.GraphQLInputObjectType.newInputObject()
            .name("InputT").field(graphql.schema.GraphQLInputObjectField.newInputObjectField()
                .name("inventoryIds")
                .type(graphql.schema.GraphQLList.list(graphql.Scalars.GraphQLID)).build()).build();
        var slot = new java.util.LinkedHashMap<String, graphql.schema.GraphQLInputType>();
        slot.put("input", inputType);
        var result = ArgBindingMap.of(slot,
            Map.of("kv", java.util.List.of("input", "inventoryIds", "inventory_id")));
        assertThat(result).isInstanceOf(ArgBindingMap.Result.PathRejected.class);
        assertThat(((ArgBindingMap.Result.PathRejected) result).message())
            .contains("opens a list of ID at segment 'inventoryIds'")
            .contains("no single key to project");
    }

    @Test
    void of_unknownPathSegment_returnsPathRejectedWithCandidate() {
        // input: InputT { fooId: String }; argMapping kv: input.fooid (typo)
        // Should reject with "did you mean fooId" candidate hint.
        var inputType = graphql.schema.GraphQLInputObjectType.newInputObject()
            .name("InputT").field(graphql.schema.GraphQLInputObjectField.newInputObjectField()
                .name("fooId").type(graphql.Scalars.GraphQLString).build()).build();
        var slot = new java.util.LinkedHashMap<String, graphql.schema.GraphQLInputType>();
        slot.put("input", inputType);
        var result = ArgBindingMap.of(slot, Map.of("kv", java.util.List.of("input", "fooid")));
        assertThat(result).isInstanceOf(ArgBindingMap.Result.PathRejected.class);
        assertThat(((ArgBindingMap.Result.PathRejected) result).message())
            .contains("segment 'fooid' does not exist on input type 'InputT'")
            .contains("fooId");
    }

    // ===== Result.Failure: the shared read the directive sites lift their wording through =====

    @Test
    void of_bothFailureArms_readThroughFailureWithoutLosingTheirIdentity() {
        // The site that prefixes both arms identically matches Result.Failure and writes the
        // wording once. The arms stay distinct records underneath, which is what lets
        // BuildContext.resolveConditionRef qualify the unknown-slot arm alone: it resolves
        // against an empty slot map, so the shared message renders the available-argument list
        // as [] and its extra clause is the only prose that says why. A Failure that carried a
        // fused string instead of two records would drop that.
        var inputType = graphql.schema.GraphQLInputObjectType.newInputObject()
            .name("InputT").field(graphql.schema.GraphQLInputObjectField.newInputObjectField()
                .name("fooId").type(graphql.Scalars.GraphQLString).build()).build();
        var slot = new java.util.LinkedHashMap<String, graphql.schema.GraphQLInputType>();
        slot.put("input", inputType);

        var unknownHead = ArgBindingMap.of(slot, Map.of("kv", java.util.List.of("nope")));
        var badTail = ArgBindingMap.of(slot, Map.of("kv", java.util.List.of("input", "fooid")));

        assertThat(unknownHead).isInstanceOf(ArgBindingMap.Result.Failure.class);
        assertThat(badTail).isInstanceOf(ArgBindingMap.Result.Failure.class);
        // One accessor for the site that treats them alike...
        assertThat(((ArgBindingMap.Result.Failure) unknownHead).message())
            .isEqualTo(((ArgBindingMap.Result.UnknownArgRef) unknownHead).message());
        assertThat(((ArgBindingMap.Result.Failure) badTail).message())
            .isEqualTo(((ArgBindingMap.Result.PathRejected) badTail).message());
        // ...and two arms for the site that does not.
        assertThat(unknownHead).isInstanceOf(ArgBindingMap.Result.UnknownArgRef.class);
        assertThat(badTail).isInstanceOf(ArgBindingMap.Result.PathRejected.class);
        assertThat(unknownHead).isNotInstanceOf(ArgBindingMap.Result.PathRejected.class);
        assertThat(badTail).isNotInstanceOf(ArgBindingMap.Result.UnknownArgRef.class);
    }

    @Test
    void of_emptySlotMap_rendersTheAvailableArgumentListAsEmptyBrackets() {
        // The rendering that makes the path-step @condition clause load-bearing: with no slots
        // in scope there is nothing to list, so the shared message alone reads as a puzzle.
        var result = ArgBindingMap.of(Map.of(), Map.of("kv", java.util.List.of("anything")));
        assertThat(((ArgBindingMap.Result.Failure) result).message())
            .contains("available arguments are []");
    }
}
