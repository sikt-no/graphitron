package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.model.BodyParam;
import no.sikt.graphitron.rewrite.model.CallSiteExtraction;
import no.sikt.graphitron.rewrite.model.GeneratedConditionFilter;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.ParticipantFilters;
import no.sikt.graphitron.rewrite.model.QueryField;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A filter input on a root multitable interface / union query field is lowered
 * <em>per participant</em>, each against the participant's own table, and the model carries the
 * resolved filters in a field-local {@link ParticipantFilters} list. Column filters (plain, enum,
 * jOOQ-converted, {@code @nodeId}-decoded; top-level or nested-input) and developer
 * {@code @condition} filters all lower; a column absent from one participant fails classification.
 */
@PipelineTier
class MultiTableFilterLoweringTest {

    // customer and staff both carry a `first_name varchar` column; `username` is staff-only.
    private static final String CUSTOMER_STAFF =
        """
        type Customer @table(name: "customer") { firstName: String @field(name: "first_name") }
        type Staff @table(name: "staff") { firstName: String @field(name: "first_name") }
        """;

    @Test
    void unionField_fieldFilter_lowersPerParticipant() {
        var schema = TestSchemaHelper.buildSchema(CUSTOMER_STAFF + """
            union Occupant = Customer | Staff
            type Query {
                occupants(firstName: [String!] @field(name: "first_name")): [Occupant!]!
            }
            """);
        var field = schema.field("Query", "occupants");
        assertThat(field).isInstanceOf(QueryField.QueryUnionField.class);
        var union = (QueryField.QueryUnionField) field;
        assertPerParticipantFirstNameFilter(union.participantFilters());
    }

    @Test
    void interfaceField_fieldFilter_lowersPerParticipant() {
        var schema = TestSchemaHelper.buildSchema("""
            interface Occupant { firstName: String }
            type Customer implements Occupant @table(name: "customer") { firstName: String @field(name: "first_name") }
            type Staff implements Occupant @table(name: "staff") { firstName: String @field(name: "first_name") }
            type Query {
                occupants(firstName: [String!] @field(name: "first_name")): [Occupant!]!
            }
            """);
        var field = schema.field("Query", "occupants");
        assertThat(field).isInstanceOf(QueryField.QueryInterfaceField.class);
        var iface = (QueryField.QueryInterfaceField) field;
        assertPerParticipantFirstNameFilter(iface.participantFilters());
    }

    /**
     * Each participant carries its own {@link GeneratedConditionFilter}: a table-specific
     * {@code <Participant>Conditions} class (so the two participants do not collide), a
     * {@code first_name IN (...)} body param, and the participant's own table.
     */
    private static void assertPerParticipantFirstNameFilter(List<ParticipantFilters> participantFilters) {
        assertThat(participantFilters)
            .as("one filter carrier per table-bound participant")
            .hasSize(2);
        for (var pf : participantFilters) {
            var gcf = pf.filters().stream()
                .filter(f -> f instanceof GeneratedConditionFilter)
                .map(f -> (GeneratedConditionFilter) f)
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                    "participant '" + pf.participant().typeName() + "' carries no GeneratedConditionFilter: "
                        + pf.filters()));
            assertThat(gcf.className())
                .as("conditions class is named after the participant, not the interface/union, "
                    + "so the two participants do not collide on one class")
                .endsWith(pf.participant().typeName() + "Conditions");
            assertThat(gcf.methodName()).isEqualTo("occupantsCondition");
            assertThat(gcf.tableRef().tableName())
                .isEqualTo(pf.participant().table().tableName());
            assertThat(gcf.bodyParams())
                .anySatisfy(bp -> {
                    assertThat(bp).isInstanceOf(BodyParam.In.class);
                    assertThat(((BodyParam.In) bp).column().sqlName()).isEqualTo("first_name");
                });
        }
    }

    @Test
    void nestedInputFieldFilter_lowersPerParticipantWithNestedExtraction() {
        // The same first_name filter delivered through an input object (`filter`) rather than
        // as a top-level argument. The implicit column-equality predicate carries a
        // NestedInputField(filter -> firstNames) call-site extraction whose leaf is Direct, which the
        // polymorphic branch emitter handles registry-free; only the call site differs from the
        // top-level form, so the per-participant lowering is otherwise identical.
        var schema = TestSchemaHelper.buildSchema(CUSTOMER_STAFF + """
            input OccupantFilter { firstNames: [String!] @field(name: "first_name") }
            union Occupant = Customer | Staff
            type Query {
                occupants(filter: OccupantFilter): [Occupant!]!
            }
            """);
        var field = schema.field("Query", "occupants");
        assertThat(field).isInstanceOf(QueryField.QueryUnionField.class);
        var union = (QueryField.QueryUnionField) field;
        var participantFilters = union.participantFilters();
        assertThat(participantFilters)
            .as("one filter carrier per table-bound participant")
            .hasSize(2);
        for (var pf : participantFilters) {
            var gcf = pf.filters().stream()
                .filter(f -> f instanceof GeneratedConditionFilter)
                .map(f -> (GeneratedConditionFilter) f)
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                    "participant '" + pf.participant().typeName() + "' carries no GeneratedConditionFilter: "
                        + pf.filters()));
            assertThat(gcf.methodName()).isEqualTo("occupantsCondition");
            assertThat(gcf.bodyParams())
                .anySatisfy(bp -> {
                    assertThat(bp).isInstanceOf(BodyParam.In.class);
                    assertThat(((BodyParam.In) bp).column().sqlName()).isEqualTo("first_name");
                });
            var callParam = gcf.callParams().stream()
                .filter(p -> p.name().equals("firstNames"))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                    "no 'firstNames' call param on " + pf.participant().typeName() + ": " + gcf.callParams()));
            assertThat(callParam.extraction())
                .as("a nested-input filter arrives Map-traversed, not as a top-level argument")
                .isInstanceOf(CallSiteExtraction.NestedInputField.class);
            var nif = (CallSiteExtraction.NestedInputField) callParam.extraction();
            assertThat(nif.outerArgName()).isEqualTo("filter");
            assertThat(nif.path()).containsExactly("firstNames");
            assertThat(nif.leaf())
                .as("a plain @field nested column carries a Direct leaf, so the branch path needs no registry")
                .isInstanceOf(CallSiteExtraction.Direct.class);
        }
    }

    @Test
    void nestedInputFieldCondition_lowersPerParticipantAsRewrappedConditionFilter() {
        // A developer @condition on a nested input field lowers per participant as a
        // ConditionFilter whose arg param extracts via the Map traversal (rewrapForNested). The
        // field/arg-level guard never reached this shape; the relaxed extraction gate admits it.
        var schema = TestSchemaHelper.buildSchema(CUSTOMER_STAFF + """
            input OccupantFilter {
                firstName: String @field(name: "first_name") @condition(condition: {
                    className: "no.sikt.graphitron.rewrite.TestConditionStub", method: "occupantsFirstName"})
            }
            union Occupant = Customer | Staff
            type Query {
                occupants(filter: OccupantFilter): [Occupant!]!
            }
            """);
        var field = schema.field("Query", "occupants");
        assertThat(field).isInstanceOf(QueryField.QueryUnionField.class);
        var union = (QueryField.QueryUnionField) field;
        assertThat(union.participantFilters()).hasSize(2);
        for (var pf : union.participantFilters()) {
            var cf = pf.filters().stream()
                .filter(f -> f instanceof no.sikt.graphitron.rewrite.model.ConditionFilter)
                .map(f -> (no.sikt.graphitron.rewrite.model.ConditionFilter) f)
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                    "participant '" + pf.participant().typeName() + "' carries no ConditionFilter: "
                        + pf.filters()));
            assertThat(cf.methodName()).isEqualTo("occupantsFirstName");
            var callParam = cf.callParams().get(0);
            assertThat(callParam.extraction())
                .as("a nested-input @condition arg extracts via the Map traversal")
                .isInstanceOf(CallSiteExtraction.NestedInputField.class);
        }
    }

    @Test
    void filterColumnAbsentOnOneParticipant_failsClassification() {
        // `username` is a staff-only column; lowering it against the customer participant fails.
        var schema = TestSchemaHelper.buildSchema(CUSTOMER_STAFF + """
            union Occupant = Customer | Staff
            type Query {
                occupants(username: [String!] @field(name: "username")): [Occupant!]!
            }
            """);
        var field = schema.field("Query", "occupants");
        assertThat(field).isInstanceOf(GraphitronField.UnclassifiedField.class);
        var unc = (GraphitronField.UnclassifiedField) field;
        assertThat(unc.kind()).isEqualTo(RejectionKind.AUTHOR_ERROR);
        assertThat(unc.reason()).contains("username");
    }

    @Test
    void idTypedFilter_lowersPerParticipantWithJooqConvertExtraction() {
        // store_id is a shared int column on both participants; the ID-typed @field
        // arg lowers per participant with a JooqConvert call-site extraction (the wire String
        // coerces through the participant column's DataType), no longer rejected at the classify
        // gate now that the branch emitter carries the shared <name>Keys pre-lift and the arm
        // emits the non-deprecated DSL.val(...).getValue() coercion.
        var schema = TestSchemaHelper.buildSchema(CUSTOMER_STAFF + """
            union Occupant = Customer | Staff
            type Query {
                occupants(storeId: [ID!] @field(name: "store_id")): [Occupant!]!
            }
            """);
        var field = schema.field("Query", "occupants");
        assertThat(field).isInstanceOf(QueryField.QueryUnionField.class);
        var union = (QueryField.QueryUnionField) field;
        assertThat(union.participantFilters())
            .as("one filter carrier per table-bound participant")
            .hasSize(2);
        for (var pf : union.participantFilters()) {
            var gcf = pf.filters().stream()
                .filter(f -> f instanceof GeneratedConditionFilter)
                .map(f -> (GeneratedConditionFilter) f)
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                    "participant '" + pf.participant().typeName() + "' carries no GeneratedConditionFilter: "
                        + pf.filters()));
            assertThat(gcf.bodyParams())
                .anySatisfy(bp -> {
                    assertThat(bp).isInstanceOf(BodyParam.In.class);
                    assertThat(((BodyParam.In) bp).column().sqlName()).isEqualTo("store_id");
                });
            var callParam = gcf.callParams().stream()
                .filter(p -> p.name().equals("storeId"))
                .findFirst()
                .orElseThrow();
            assertThat(callParam.extraction())
                .as("an ID-typed @field filter coerces through the column's DataType")
                .isInstanceOf(CallSiteExtraction.JooqConvert.class);
            assertThat(callParam.list()).isTrue();
        }
    }

    @Test
    void nestedIdTypedFilter_lowersWithJooqConvertLeaf() {
        // The nested @field leaf is aligned with the top-level conversion semantics —
        // a nested [ID!] @field over a plain column routes through a JooqConvert leaf rather than
        // the formerly hardcoded Direct leaf, so the wire String coerces through the column's
        // DataType on the nested path exactly as it does top-level.
        var schema = TestSchemaHelper.buildSchema(CUSTOMER_STAFF + """
            input OccupantFilter { storeIds: [ID!] @field(name: "store_id") }
            union Occupant = Customer | Staff
            type Query {
                occupants(filter: OccupantFilter): [Occupant!]!
            }
            """);
        var field = schema.field("Query", "occupants");
        assertThat(field).isInstanceOf(QueryField.QueryUnionField.class);
        var union = (QueryField.QueryUnionField) field;
        assertThat(union.participantFilters()).hasSize(2);
        for (var pf : union.participantFilters()) {
            var gcf = pf.filters().stream()
                .filter(f -> f instanceof GeneratedConditionFilter)
                .map(f -> (GeneratedConditionFilter) f)
                .findFirst()
                .orElseThrow();
            var callParam = gcf.callParams().stream()
                .filter(p -> p.name().equals("storeIds"))
                .findFirst()
                .orElseThrow();
            assertThat(callParam.extraction()).isInstanceOf(CallSiteExtraction.NestedInputField.class);
            var nif = (CallSiteExtraction.NestedInputField) callParam.extraction();
            assertThat(nif.leaf())
                .as("a nested ID-typed @field column carries a JooqConvert leaf (top-level alignment)")
                .isInstanceOf(CallSiteExtraction.JooqConvert.class);
        }
    }

    @Test
    void nodeIdFilter_lowersPerParticipantWithNodeIdDecodeExtraction() {
        // An FK-target @nodeId filter arg on a multitable union. Both participant
        // tables carry an address_id FK to address (a @node type), so the decoded Address key
        // filters each branch by its own lifted FK column; the call-site extraction is the
        // NodeIdDecodeKeys decode chain, lifted through the fetcher class's registry. No rejection
        // test flips here: this is new coverage (the earlier suite carried no
        // NodeIdDecodeKeys rejection case).
        var schema = TestSchemaHelper.buildSchema("""
            type Address @table(name: "address") @node { id: ID! @nodeId }
            type Customer @table(name: "customer") { firstName: String @field(name: "first_name") }
            type Staff @table(name: "staff") { firstName: String @field(name: "first_name") }
            union Occupant = Customer | Staff
            type Query {
                occupants(addressId: [ID!] @nodeId(typeName: "Address")): [Occupant!]!
            }
            """);
        var field = schema.field("Query", "occupants");
        assertThat(field).isInstanceOf(QueryField.QueryUnionField.class);
        var union = (QueryField.QueryUnionField) field;
        assertThat(union.participantFilters())
            .as("one filter carrier per table-bound participant")
            .hasSize(2);
        for (var pf : union.participantFilters()) {
            var gcf = pf.filters().stream()
                .filter(f -> f instanceof GeneratedConditionFilter)
                .map(f -> (GeneratedConditionFilter) f)
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                    "participant '" + pf.participant().typeName() + "' carries no GeneratedConditionFilter: "
                        + pf.filters()));
            assertThat(gcf.bodyParams())
                .as("the decoded Address key filters the participant's own lifted FK column")
                .anySatisfy(bp -> {
                    assertThat(bp).isInstanceOf(BodyParam.In.class);
                    assertThat(((BodyParam.In) bp).column().sqlName()).isEqualTo("address_id");
                });
            var callParam = gcf.callParams().stream()
                .filter(p -> p.name().equals("addressId"))
                .findFirst()
                .orElseThrow();
            assertThat(callParam.extraction())
                .as("an authored @nodeId filter decodes with throw-on-mismatch semantics")
                .isInstanceOf(CallSiteExtraction.ThrowOnMismatch.class);
        }
    }

    @Test
    void fieldLevelCondition_lowersPerParticipant() {
        // A field-level developer @condition lowers per participant; the reflected
        // ConditionFilter is table-agnostic (Table<?> first parameter), so the same method serves
        // every branch against its own stage-1 alias.
        var schema = TestSchemaHelper.buildSchema(CUSTOMER_STAFF + """
            union Occupant = Customer | Staff
            type Query {
                occupants: [Occupant!]! @condition(condition: {
                    className: "no.sikt.graphitron.rewrite.TestConditionStub", method: "lifterFieldCondition"})
            }
            """);
        assertLowersConditionFilterPerParticipant(schema.field("Query", "occupants"), "lifterFieldCondition");
    }

    @Test
    void argLevelCondition_lowersPerParticipant() {
        var schema = TestSchemaHelper.buildSchema(CUSTOMER_STAFF + """
            union Occupant = Customer | Staff
            type Query {
                occupants(firstName: String @field(name: "first_name") @condition(condition: {
                    className: "no.sikt.graphitron.rewrite.TestConditionStub", method: "occupantsFirstName"})): [Occupant!]!
            }
            """);
        assertLowersConditionFilterPerParticipant(schema.field("Query", "occupants"), "occupantsFirstName");
    }

    /**
     * The field classifies as a {@link QueryField.QueryUnionField} and every
     * table-bound participant's filter list carries the developer
     * {@link no.sikt.graphitron.rewrite.model.ConditionFilter} with the expected method.
     */
    private static void assertLowersConditionFilterPerParticipant(GraphitronField field, String methodName) {
        assertThat(field).isInstanceOf(QueryField.QueryUnionField.class);
        var union = (QueryField.QueryUnionField) field;
        assertThat(union.participantFilters()).hasSize(2);
        for (var pf : union.participantFilters()) {
            assertThat(pf.filters())
                .as("participant '" + pf.participant().typeName() + "' carries the developer @condition")
                .anySatisfy(f -> {
                    assertThat(f).isInstanceOf(no.sikt.graphitron.rewrite.model.ConditionFilter.class);
                    assertThat(f.methodName()).isEqualTo(methodName);
                });
        }
    }
}
