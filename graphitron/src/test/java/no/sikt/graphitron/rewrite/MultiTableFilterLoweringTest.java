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
import no.sikt.graphitron.model.diagnostics.ReflectionError;
import no.sikt.graphitron.model.diagnostics.Rejection;
import no.sikt.graphitron.model.diagnostics.RejectionKind;
import no.sikt.graphitron.model.diagnostics.ValidationError;

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

    /**
     * The same two participants, each a node type over its own table. A <em>bare</em>
     * {@code @nodeId} on a field returning their union therefore infers a different node type per
     * participant, which is the divergence the per-branch dispatch exists for.
     */
    private static final String NODE_BACKED_CUSTOMER_STAFF =
        """
        type Customer implements Node @table(name: "customer") @node {
            id: ID! @nodeId
            firstName: String @field(name: "first_name")
        }
        type Staff implements Node @table(name: "staff") @node {
            id: ID! @nodeId
            firstName: String @field(name: "first_name")
        }
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
     * Each participant carries its own {@link GeneratedConditionFilter}: a
     * {@code first_name IN (...)} body param lowered against the participant's own table (the
     * condition producer mints one participant-named glue method per row, so the two
     * participants cannot collide; the filter itself carries no method identity).
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
            assertThat(gcf.tableRef().tableName())
                .as("the filter is lowered against the participant's own table, not the union's")
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
        // The cause is a fact of one participant and mints at that participant's own coordinate; the
        // consuming field states only the consequence, the shape a compiler uses for "cannot
        // instantiate" plus its member errors.
        assertThat(unc.reason())
            .contains("1 participant could not be lowered")
            .contains("Customer");
        assertThat(schema.diagnostics())
            .as("the missing column is named at the participant that misses it")
            .anySatisfy(e -> {
                assertThat(e.coordinate()).isEqualTo("Query.occupants/Customer");
                assertThat(e.message()).contains("username");
            });
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
            type Address implements Node @table(name: "address") @node { id: ID! @nodeId }
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
        assertThat(union.nodeIdArgDispatches())
            .as("an explicit @nodeId(typeName:) pins one node type on every branch, so there is"
                + " nothing to dispatch and the field carries no dispatch fact")
            .isEmpty();
    }

    @Test
    void bareNodeIdOverDivergentParticipants_prunesPerBranchAndCarriesTheDispatchFact() {
        // A bare @nodeId argument on a root returning Customer | Staff: each participant infers its
        // own node type, so the argument means a different id per branch. Every branch keeps only
        // the ids it can decode (PruneOnMismatch), and the field carries the per-participant
        // decoders the fetcher's matches-none guard checks a supplied id against.
        var schema = TestSchemaHelper.buildSchema(NODE_BACKED_CUSTOMER_STAFF + """
            union Occupant = Customer | Staff
            type Query { occupantById(id: ID! @nodeId): Occupant }
            """);
        var field = schema.field("Query", "occupantById");
        assertThat(field).isInstanceOf(QueryField.QueryUnionField.class);
        var union = (QueryField.QueryUnionField) field;
        assertThat(union.participantFilters()).hasSize(2);
        for (var pf : union.participantFilters()) {
            var gcf = pf.filters().stream()
                .filter(f -> f instanceof GeneratedConditionFilter)
                .map(f -> (GeneratedConditionFilter) f)
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                    "participant '" + pf.participant().typeName() + "' carries no GeneratedConditionFilter: "
                        + pf.filters()));
            var callParam = gcf.callParams().stream()
                .filter(p -> p.name().equals("id"))
                .findFirst()
                .orElseThrow();
            assertThat(callParam.extraction())
                .as("participant '" + pf.participant().typeName() + "' prunes rather than throws")
                .isInstanceOf(CallSiteExtraction.PruneOnMismatch.class);
            assertThat(((CallSiteExtraction.PruneOnMismatch) callParam.extraction()).decodeMethod().nodeTypeName())
                .as("and it decodes its own node type, not a sibling's")
                .isEqualTo(pf.participant().typeName());
        }
        assertThat(union.nodeIdArgDispatches())
            .singleElement()
            .satisfies(dispatch -> {
                assertThat(dispatch.argName()).isEqualTo("id");
                assertThat(dispatch.list()).isFalse();
                assertThat(dispatch.decodeByParticipant().keySet())
                    .as("one decoder per participant, in participant order")
                    .containsExactly("Customer", "Staff");
                assertThat(dispatch.candidateNodeTypeNames()).containsExactly("Customer", "Staff");
            });
    }

    @Test
    void bareNodeIdListOverDivergentParticipants_prunesPerBranch() {
        // The list form of the same divergence. Nothing about the verdict is arity-sensitive; the
        // shape rides the dispatch fact so the generated guard can name the offending element.
        var schema = TestSchemaHelper.buildSchema(NODE_BACKED_CUSTOMER_STAFF + """
            union Occupant = Customer | Staff
            type Query { occupantsByIds(ids: [ID!] @nodeId): [Occupant!]! }
            """);
        var union = (QueryField.QueryUnionField) schema.field("Query", "occupantsByIds");
        for (var pf : union.participantFilters()) {
            var gcf = (GeneratedConditionFilter) pf.filters().stream()
                .filter(f -> f instanceof GeneratedConditionFilter)
                .findFirst()
                .orElseThrow();
            assertThat(gcf.callParams().stream()
                    .filter(p -> p.name().equals("ids"))
                    .findFirst()
                    .orElseThrow()
                    .extraction())
                .isInstanceOf(CallSiteExtraction.PruneOnMismatch.class);
        }
        assertThat(union.nodeIdArgDispatches())
            .singleElement()
            .satisfies(dispatch -> assertThat(dispatch.list()).isTrue());
    }

    @Test
    void bareNodeIdOnDivergentNestedInputLeaf_rejects() {
        // Same defect, different plumbing: a nested-input bare @nodeId leaf would also mean a
        // different id per branch. Dispatch covers top-level arguments, so this shape rejects at
        // classification time with an author error naming the leaf and the participants' answers
        // rather than misbinding silently.
        var schema = TestSchemaHelper.buildSchema(NODE_BACKED_CUSTOMER_STAFF + """
            input OccupantIdFilter { occupantId: ID @nodeId }
            union Occupant = Customer | Staff
            type Query { occupants(filter: OccupantIdFilter): [Occupant!]! }
            """);
        var field = schema.field("Query", "occupants");
        assertThat(field).isInstanceOf(GraphitronField.UnclassifiedField.class);
        var unc = (GraphitronField.UnclassifiedField) field;
        assertThat(unc.kind()).isEqualTo(RejectionKind.AUTHOR_ERROR);
        assertThat(unc.reason())
            .contains("filter.occupantId")
            .contains("Customer")
            .contains("Staff")
            .contains("@nodeId(typeName:");
    }

    @Test
    void bareNodeIdOnSharedTargetNestedInputLeaf_lowersUnchanged() {
        // The shared-target nested leaf keeps working: both participants FK to `address`, the
        // explicit typeName pins one node type, and the leaf lowers as it did before.
        var schema = TestSchemaHelper.buildSchema("""
            type Address implements Node @table(name: "address") @node { id: ID! @nodeId }
            type Customer @table(name: "customer") { firstName: String @field(name: "first_name") }
            type Staff @table(name: "staff") { firstName: String @field(name: "first_name") }
            input OccupantAddressFilter { addressId: [ID!] @nodeId(typeName: "Address") }
            union Occupant = Customer | Staff
            type Query { occupants(filter: OccupantAddressFilter): [Occupant!]! }
            """);
        var field = schema.field("Query", "occupants");
        assertThat(field).isInstanceOf(QueryField.QueryUnionField.class);
        var union = (QueryField.QueryUnionField) field;
        assertThat(union.participantFilters()).hasSize(2);
        assertThat(union.nodeIdArgDispatches()).isEmpty();
        for (var pf : union.participantFilters()) {
            var gcf = (GeneratedConditionFilter) pf.filters().stream()
                .filter(f -> f instanceof GeneratedConditionFilter)
                .findFirst()
                .orElseThrow();
            var nif = (CallSiteExtraction.NestedInputField) gcf.callParams().stream()
                .filter(p -> p.name().equals("addressId"))
                .findFirst()
                .orElseThrow()
                .extraction();
            assertThat(nif.leaf())
                .as("a shared-target nested @nodeId leaf still throws on a wrong-type id")
                .isInstanceOf(CallSiteExtraction.ThrowOnMismatch.class);
        }
    }

    @Test
    void bareNodeIdOverPartiallyNodeBackedParticipants_rejects() {
        // The mixed shape between the two verdicts above: one participant's table backs a node type
        // and its sibling's backs none, so the leaf resolves on one branch and cannot on the other.
        // There is no shared target to fall back on and no divergence to dispatch between; the
        // sibling's own classification fails, which is what keeps a leaf that only half the branches
        // can decode from lowering as though every branch had agreed.
        //
        // The message is deliberately not asserted. The single-table wording names the participant's
        // own table, which identifies the participant, and offers the remedy that actually fixes it;
        // that is enough at this coordinate, and leaving the prose unpinned keeps it revisable.
        var schema = TestSchemaHelper.buildSchema("""
            type Customer implements Node @table(name: "customer") @node {
                id: ID! @nodeId
                firstName: String @field(name: "first_name")
            }
            type Staff @table(name: "staff") { firstName: String @field(name: "first_name") }
            union Occupant = Customer | Staff
            type Query { occupantById(id: ID! @nodeId): Occupant }
            """);
        var field = schema.field("Query", "occupantById");
        assertThat(field).isInstanceOf(GraphitronField.UnclassifiedField.class);
        assertThat(((GraphitronField.UnclassifiedField) field).kind())
            .isEqualTo(RejectionKind.AUTHOR_ERROR);
    }

    @Test
    void singleBaseTableInterface_ambiguousBareNodeId_rejects() {
        // The scope cut for the single-base-table polymorphic arms, made a test rather than a
        // claim. Those arms never dispatch because their participants share one table, hence one id
        // space; what keeps that true is that a bare @nodeId over a table backing more than one node
        // type is rejected as ambiguous instead of resolved to one of them. If this ever resolves
        // instead of rejecting, those arms can misbind exactly the way a multitable root could.
        var schema = TestSchemaHelper.buildSchema("""
            interface MediaItem @table(name: "film") @discriminate(on: "text_rating") { title: String }
            type Film implements MediaItem & Node @table(name: "film") @discriminator(value: "film") @node {
                id: ID! @nodeId
                title: String
            }
            type FilmPrint implements Node @table(name: "film") @node(typeId: "FilmPrint") { id: ID! @nodeId }
            type Query {
                media(filmId: ID! @nodeId): [MediaItem!]!
                print: FilmPrint
            }
            """);
        var field = schema.field("Query", "media");
        assertThat(field).isInstanceOf(GraphitronField.UnclassifiedField.class);
        assertThat(((GraphitronField.UnclassifiedField) field).reason())
            .as("two node types over the base table make the bare inference ambiguous")
            .contains("ambiguous");
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

    @Test
    void perParticipantOverloadSet_argLevel_lowersPerParticipant() {
        // The reporter's shape: one declaration per participant table, agreeing on the binding shape
        // and differing only in their table slots. The set is one @condition target; each branch's
        // glue passes that participant's concretely-typed alias and the consumer's javac picks the
        // declaration. Nothing here has to choose between the declarations, which is the whole point.
        var schema = TestSchemaHelper.buildSchema(CUSTOMER_STAFF + """
            union Occupant = Customer | Staff
            type Query {
                occupants(firstName: String @field(name: "first_name") @condition(condition: {
                    className: "no.sikt.graphitron.rewrite.TestConditionStub", method: "occupantNameOverload"})): [Occupant!]!
            }
            """);
        assertLowersConditionFilterPerParticipant(
            schema.field("Query", "occupants"), "occupantNameOverload");
    }

    @Test
    void perParticipantOverloadSet_inputField_lowersPerParticipant() {
        // The same set at the input-field coordinate, which resolves with no table in scope. The
        // shape judgement is table-blind, so admission is the same answer here as at the argument
        // coordinate: resolution stays coordinate-invariant.
        var schema = TestSchemaHelper.buildSchema(CUSTOMER_STAFF + """
            input OccupantFilter {
                firstName: String @field(name: "first_name") @condition(condition: {
                    className: "no.sikt.graphitron.rewrite.TestConditionStub", method: "occupantNameOverload"})
            }
            union Occupant = Customer | Staff
            type Query {
                occupants(filter: OccupantFilter): [Occupant!]!
            }
            """);
        assertLowersConditionFilterPerParticipant(
            schema.field("Query", "occupants"), "occupantNameOverload");
    }

    @Test
    void shapeDisagreeingOverloadSet_fieldLevel_surfacesAsTypedAmbiguousMethod() {
        // Two declarations sharing position 1's name and differing in its declared type: the shared
        // name denotes two call shapes, which is what AmbiguousMethod now means. Asserted as the
        // typed arm and its axis rather than as message prose, since the same delta rewrites the
        // message. The field-level coordinate carries the rejection on the field it unclassifies.
        var schema = TestSchemaHelper.buildSchema(CUSTOMER_STAFF + """
            union Occupant = Customer | Staff
            type Query {
                occupants: [Occupant!]! @condition(condition: {
                    className: "no.sikt.graphitron.rewrite.TestConditionStub", method: "occupantNameDisagreeing"})
            }
            """);
        assertThat(schema.field("Query", "occupants"))
            .isInstanceOf(GraphitronField.UnclassifiedField.class);
        assertAmbiguousAtParameterPosition(firstAmbiguousMethod(schema), 1);
    }

    @Test
    void shapeDisagreeingOverloadSet_inputField_surfacesAsTypedAmbiguousMethod() {
        // The parity half of the pair. The Backlog report claimed the query-field coordinate accepts
        // overloads while the input-field coordinate rejects; both route through the one @condition
        // resolution entry, so a set that disagrees on the shape rejects identically at each. The
        // input-field coordinate reports at the input field's own coordinate, as it does today.
        var schema = TestSchemaHelper.buildSchema(CUSTOMER_STAFF + """
            input OccupantFilter {
                firstName: String @field(name: "first_name") @condition(condition: {
                    className: "no.sikt.graphitron.rewrite.TestConditionStub", method: "occupantNameDisagreeing"})
            }
            union Occupant = Customer | Staff
            type Query {
                occupants(filter: OccupantFilter): [Occupant!]!
            }
            """);
        assertThat(schema.field("Query", "occupants"))
            .isInstanceOf(GraphitronField.UnclassifiedField.class);
        assertAmbiguousAtParameterPosition(firstAmbiguousMethod(schema), 1);
    }

    /**
     * The typed rejection in the schema's diagnostics. A per-participant lowering failure reaches the
     * field as the consequence ("2 participants could not be lowered") and its cause as a diagnostic
     * at the participant's own coordinate, so the typed arm is read from there at both coordinates.
     */
    private static no.sikt.graphitron.model.diagnostics.Rejection firstAmbiguousMethod(
            GraphitronSchema schema) {
        return schema.diagnostics().stream()
            .map(no.sikt.graphitron.model.diagnostics.ValidationError::rejection)
            .filter(r -> r instanceof no.sikt.graphitron.model.diagnostics.ReflectionError.AmbiguousMethod)
            .findFirst()
            .orElseThrow(() -> new AssertionError(
                "no typed AmbiguousMethod in " + schema.diagnostics()));
    }

    /**
     * The rejection is a typed {@link no.sikt.graphitron.model.diagnostics.ReflectionError.AmbiguousMethod}
     * naming the parameter position the declarations disagree on. The axis is the assertion, not the
     * rendered sentence: the message is data-driven from exactly this value.
     */
    private static void assertAmbiguousAtParameterPosition(
            no.sikt.graphitron.model.diagnostics.Rejection rejection, int position) {
        assertThat(rejection)
            .as("a shape-disagreeing @condition overload set must reject as a typed AmbiguousMethod")
            .isInstanceOf(no.sikt.graphitron.model.diagnostics.ReflectionError.AmbiguousMethod.class);
        var ambiguous =
            (no.sikt.graphitron.model.diagnostics.ReflectionError.AmbiguousMethod) rejection;
        assertThat(ambiguous.ambiguity())
            .isEqualTo(new no.sikt.graphitron.model.diagnostics.ReflectionError.AmbiguousMethod
                .Ambiguity.ParameterPosition(position));
        assertThat(ambiguous.candidateSignatures())
            .as("the overload set the author wrote arrives as data, not as prose")
            .hasSize(2);
        assertThat(ambiguous.lspCode()).isEqualTo("graphitron.reflect.ambiguous-method");
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
                    assertThat(((no.sikt.graphitron.rewrite.model.ConditionFilter) f).methodName())
                        .isEqualTo(methodName);
                });
        }
    }
}
