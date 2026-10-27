package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.model.config.RunContext;
import no.sikt.graphitron.model.diagnostics.ValidationError;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.OperationMember;
import no.sikt.graphitron.rewrite.model.OrderBySpec.SortDirection;
import no.sikt.graphitron.rewrite.model.PolymorphicOrdering;
import no.sikt.graphitron.rewrite.model.PolymorphicOrdering.SlotEntry;
import no.sikt.graphitron.rewrite.model.PolymorphicOrdering.SlotOrder;
import no.sikt.graphitron.rewrite.model.PolymorphicOrderingField;
import no.sikt.graphitron.rewrite.model.QueryField;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_JOOQ_PACKAGE;

/**
 * An ordering on a root multitable interface / union query field is resolved per participant,
 * each against the participant's own table, and folded into one field-level
 * {@link PolymorphicOrdering}: the declaration's decisions stated once, and one
 * {@link PolymorphicOrdering.Slot} per projected order column carrying each participant's column.
 * The classified shape rather than the SQL; the execution tier owns what the rows come back as.
 *
 * <p>Sibling of {@link MultiTableFilterLoweringTest}, which pins the same per-participant lowering
 * on the filter axis.
 */
@PipelineTier
class MultiTableOrderingLoweringTest {

    // customer and staff both carry last_name / first_name varchar(45) and store_id int.
    private static final String CUSTOMER_STAFF = """
        type Customer @table(name: "customer") { lastName: String @field(name: "last_name") }
        type Staff @table(name: "staff") { lastName: String @field(name: "last_name") }
        """;

    private static final String OCCUPANT_UNION = CUSTOMER_STAFF + """
        union Occupant = Customer | Staff
        """;

    private static final String OCCUPANT_INTERFACE = """
        interface Occupant { lastName: String }
        type Customer implements Occupant @table(name: "customer") { lastName: String @field(name: "last_name") }
        type Staff implements Occupant @table(name: "staff") { lastName: String @field(name: "last_name") }
        """;

    private static final String SORT = """
        enum OccupantSort {
            OCCUPANT_ID @order(primaryKey: true)
            LAST_NAME @order(fields: [{name: "last_name"}])
            NAME_DESC @order(fields: [{name: "last_name", direction: DESC}])
            STORE_THEN_NAME @order(fields: [{name: "store_id"}, {name: "last_name"}])
        }
        input OccupantOrderBy { field: OccupantSort!  direction: SortDirection }
        """;

    // ===== The fold =====

    @Test
    void unionField_defaultOrder_foldsIntoOneSlotTotalOverTheParticipants() {
        var field = rootField(OCCUPANT_UNION + """
            type Query { occupants: [Occupant!]! @defaultOrder(fields: [{name: "last_name"}]) }
            """, "occupants");
        assertThat(field).isInstanceOf(QueryField.QueryUnionField.class);
        var ordering = orderingOf(field);

        assertThat(ordering.surface()).isInstanceOf(PolymorphicOrdering.Surface.Fixed.class);
        assertThat(ordering.namedOrders()).isEmpty();
        assertThat(ordering.slots()).singleElement().satisfies(slot -> {
            assertThat(slot.ordinal()).isZero();
            assertThat(slot.slotClass()).isEqualTo("java.lang.String");
            assertThat(slot.columnByParticipant().keySet())
                .extracting(p -> p.typeName())
                .as("total over the participant set, in participant order")
                .containsExactly("Customer", "Staff");
            assertThat(slot.columnByParticipant().values())
                .allSatisfy(c -> assertThat(c.sqlName()).isEqualTo("last_name"));
        });
        assertThat(ordering.base()).isEqualTo(
            new SlotOrder.OnSlots(null, List.of(new SlotEntry(0, SortDirection.ASC, null)), true));
    }

    @Test
    void interfaceField_orderByArgument_sharesOneSlotAcrossOrdersWithDifferentDirections() {
        var field = rootField(OCCUPANT_INTERFACE + SORT + """
            type Query {
                occupants(orderBy: OccupantOrderBy @orderBy): [Occupant!]!
                    @defaultOrder(fields: [{name: "last_name"}])
            }
            """, "occupants");
        assertThat(field).isInstanceOf(QueryField.QueryInterfaceField.class);
        var ordering = orderingOf(field);

        assertThat(ordering.surface()).isEqualTo(new PolymorphicOrdering.Surface.Argument(
            "orderBy", "OccupantOrderBy", false, false, "field", "direction"));
        assertThat(ordering.slots())
            .as("last_name serves LAST_NAME, NAME_DESC, the base and STORE_THEN_NAME from one slot;"
                + " store_id is the second")
            .extracting(slot -> slot.columnByParticipant().values().iterator().next().sqlName())
            .containsExactly("last_name", "store_id");
        assertThat(ordering.namedOrders()).extracting(SlotOrder::name)
            .containsExactly("OCCUPANT_ID", "LAST_NAME", "NAME_DESC", "STORE_THEN_NAME");
        assertThat(ordering.namedOrders().get(1)).isEqualTo(
            new SlotOrder.OnSlots("LAST_NAME", List.of(new SlotEntry(0, SortDirection.ASC, null)), true));
        assertThat(ordering.namedOrders().get(2)).isEqualTo(
            new SlotOrder.OnSlots("NAME_DESC", List.of(new SlotEntry(0, SortDirection.DESC, null)), false));
        assertThat(ordering.namedOrders().get(3)).isEqualTo(new SlotOrder.OnSlots("STORE_THEN_NAME",
            List.of(new SlotEntry(1, SortDirection.ASC, null), new SlotEntry(0, SortDirection.ASC, null)), true));
        assertThat(ordering.base()).isEqualTo(
            new SlotOrder.OnSlots(null, List.of(new SlotEntry(0, SortDirection.ASC, null)), true));
    }

    /**
     * A slot's identity is its whole per-participant column map. An {@code index:} order that
     * resolves {@code event_id} on one participant and {@code note_id} on the other agrees with a
     * {@code fields:} order over {@code event_id} on the first participant only, so the two are two
     * slots, not one.
     */
    @Test
    void entriesAgreeingOnOneParticipantOnly_areTwoSlots() {
        var field = multiSchemaRootField("""
            type NoteA @table(name: "multischema_a.note") { body: String }
            type NoteB @table(name: "multischema_b.note") { body: String }
            union AnyNote = NoteA | NoteB
            enum NoteSort {
                BY_EVENT @order(fields: [{name: "event_id"}])
                BY_INDEX @order(index: "note_order_idx")
            }
            input NoteOrderBy { field: NoteSort!  direction: SortDirection }
            type Query { notes(orderBy: NoteOrderBy @orderBy): [AnyNote!]! }
            """, "notes");
        var ordering = orderingOf(field);

        assertThat(ordering.slots()).hasSize(2);
        assertThat(ordering.slots().get(0).columnByParticipant().values())
            .extracting(c -> c.sqlName()).containsExactly("event_id", "event_id");
        assertThat(ordering.slots().get(1).columnByParticipant().values())
            .extracting(c -> c.sqlName()).containsExactly("event_id", "note_id");
    }

    // ===== Primary-key-shaped orders select the key slots =====

    /**
     * The key is a list of columns, one slot per key position mapping every participant to its own
     * key column there. That is the structural pin on the stage-1 order being dialect-independent:
     * an ordering whose key is a column list has no place to carry a computed sort value.
     */
    @Test
    void everyPrimaryKeyShapedOrder_isOnPrimaryKeyAndMintsNoSlot() {
        var named = orderingOf(rootField(OCCUPANT_UNION + SORT + """
            type Query { occupants(orderBy: OccupantOrderBy @orderBy): [Occupant!]! }
            """, "occupants"));
        assertThat(named.namedOrders().getFirst())
            .as("@order(primaryKey: true)")
            .isEqualTo(new SlotOrder.OnPrimaryKey("OCCUPANT_ID", SortDirection.ASC, true));
        assertThat(named.base())
            .as("the fallback base of a field with @orderBy and no @defaultOrder")
            .isEqualTo(new SlotOrder.OnPrimaryKey(null, SortDirection.ASC, true));
        assertKey(named, List.of(List.of("customer_id", "staff_id")));

        var fixed = orderingOf(rootField(OCCUPANT_UNION + """
            type Query { occupants: [Occupant!]! @defaultOrder(primaryKey: true, direction: DESC) }
            """, "occupants"));
        assertThat(fixed.base())
            .as("@defaultOrder(primaryKey: true)")
            .isEqualTo(new SlotOrder.OnPrimaryKey(null, SortDirection.DESC, false));
        assertThat(fixed.slots()).isEmpty();
        assertKey(fixed, List.of(List.of("customer_id", "staff_id")));

        var undeclared = orderingOf(rootField(OCCUPANT_UNION + """
            type Query { occupants: [Occupant!]! }
            """, "occupants"));
        assertThat(undeclared.base())
            .as("the undeclared list read, whose resolved ordering is the implicit fallback")
            .isEqualTo(new SlotOrder.OnPrimaryKey(null, SortDirection.ASC, true));
        assertThat(undeclared.slots()).isEmpty();
        assertKey(undeclared, List.of(List.of("customer_id", "staff_id")));

        var composite = orderingOf(rootField("""
            interface PagedItem { name: String! }
            type PagedA implements PagedItem @table(name: "paged_a") { name: String! }
            type PagedB implements PagedItem @table(name: "paged_b") { name: String! }
            type Query { pagedItems: [PagedItem!]! @asConnection(defaultFirstValue: 3) }
            """, "pagedItems"));
        assertThat(composite.base())
            .as("the composite-key connection's implicit fallback")
            .isEqualTo(new SlotOrder.OnPrimaryKey(null, SortDirection.ASC, true));
        assertThat(composite.slots()).as("no declared slot minted").isEmpty();
        assertThat(composite.key())
            .as("one key slot per key position, PagedA then PagedB")
            .allSatisfy(slot -> assertThat(slot.columnByParticipant().keySet())
                .extracting(p -> p.typeName()).containsExactly("PagedA", "PagedB"));
        assertKey(composite, List.of(List.of("k1", "k1"), List.of("k2", "k2")));
    }

    @Test
    void singleValuedRoot_resolvesNoOrdering() {
        var field = rootField(OCCUPANT_UNION + """
            type Query { occupant: Occupant @defaultOrder(fields: [{name: "last_name"}]) }
            """, "occupant");
        assertThat(field).isInstanceOf(QueryField.QueryUnionField.class);
        assertThat(((PolymorphicOrderingField) field).ordering())
            .as("nothing to lower; the view's fan-out row keeps rejecting the declaration")
            .isEmpty();
    }

    // ===== The agreement rules =====

    @Test
    void anOrderColumnAbsentOnOneParticipant_rejectsAtThatParticipant() {
        // username is staff-only.
        var schema = TestSchemaHelper.buildSchema(OCCUPANT_UNION + """
            type Query { occupants: [Occupant!]! @defaultOrder(fields: [{name: "username"}]) }
            """);
        var field = schema.field("Query", "occupants");
        assertThat(field).isInstanceOf(GraphitronField.UnclassifiedField.class);
        assertThat(((GraphitronField.UnclassifiedField) field).reason())
            .contains("1 participant could not be lowered (Customer)");
        assertThat(participantDiagnostic(schema, "Query.occupants/Customer").message())
            .contains("@defaultOrder");
    }

    @Test
    void unequalIndexArity_rejectsNamingBothSidesColumns() {
        var schema = TestSchemaHelper.buildSchema("""
            type EvA @table(name: "multischema_a.event") { name: String }
            type EvB @table(name: "multischema_b.event") { code: String }
            union AnyEvent = EvA | EvB
            type Query { events: [AnyEvent!]! @defaultOrder(index: "event_order_idx") }
            """, multiSchemaContext());
        assertThat(schema.field("Query", "events")).isInstanceOf(GraphitronField.UnclassifiedField.class);
        assertThat(participantDiagnostic(schema, "Query.events/EvB").message())
            .contains("resolves 2 columns (code, event_id) here but 1 (name) on participant 'EvA'");
    }

    @Test
    void intAgainstBigint_rejectsOnTheTypePair() {
        var schema = TestSchemaHelper.buildSchema(SITES + """
            type Query { sites: [Site!]! @defaultOrder(fields: [{name: "campus_count"}]) }
            """);
        assertThat(schema.field("Query", "sites")).isInstanceOf(GraphitronField.UnclassifiedField.class);
        assertThat(participantDiagnostic(schema, "Query.sites/PlainSite").message())
            .contains("column 'campus_count' is")
            .contains("java.lang.Integer")
            .contains("java.lang.Long")
            .contains("on participant 'ConverterSite'");
    }

    /**
     * The case a class-only rule admits: the converted {@code org_code} (an {@code org_code_domain}
     * bigint bound to {@code String} through a converter) against a plain {@code varchar} that also
     * binds {@code String}. Their {@code UNION ALL} fails in the database, so the build rejects it on
     * the SQL type.
     */
    @Test
    void convertedAgainstPlainOfOneBoundClass_rejectsOnTheSqlType() {
        var schema = TestSchemaHelper.buildSchema(SITES + """
            type Query { sites: [Site!]! @defaultOrder(fields: [{name: "org_code"}]) }
            """);
        assertThat(schema.field("Query", "sites")).isInstanceOf(GraphitronField.UnclassifiedField.class);
        var message = participantDiagnostic(schema, "Query.sites/PlainSite").message();
        assertThat(message)
            .contains("bound to java.lang.String, but column 'org_code' on participant 'ConverterSite'")
            .contains("bigint")
            .contains("varchar");
    }

    /**
     * Key slots compare what their typing has to be right about. An {@code integer} key against a
     * {@code varchar} one binds to different Java types, which the typed {@code UNION ALL} cannot
     * compile.
     */
    @Test
    void keysOfDifferentBoundTypes_rejectAtThePositionNamingBothColumns() {
        var schema = TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") { title: String }
            type XlatParent @table(name: "xlat_parent") { name: String }
            union Keyed = Film | XlatParent
            type Query { keyed: [Keyed!]! }
            """);
        assertThat(schema.field("Query", "keyed")).isInstanceOf(GraphitronField.UnclassifiedField.class);
        assertThat(participantDiagnostic(schema, "Query.keyed/XlatParent").message())
            .contains("the primary key position 1: column 'pk_id' is java.lang.String,"
                + " but column 'film_id' on participant 'Film' is java.lang.Integer");
    }

    /**
     * The converter case the bound type alone misses: {@code converter_org}'s key is a bigint domain
     * bound to {@code String} through a converter, {@code xlat_parent}'s an unconverted
     * {@code varchar} also bound to {@code String}. Their {@code UNION ALL} fails in the database.
     */
    @Test
    void keysBindingAlikeOverDifferentDatabaseTypes_rejectAtThePosition() {
        var schema = TestSchemaHelper.buildSchema("""
            type Org @table(name: "converter_org") { orgName: String @field(name: "org_name") }
            type XlatParent @table(name: "xlat_parent") { name: String }
            union Keyed = Org | XlatParent
            type Query { keyed: [Keyed!]! }
            """);
        assertThat(schema.field("Query", "keyed")).isInstanceOf(GraphitronField.UnclassifiedField.class);
        assertThat(participantDiagnostic(schema, "Query.keyed/XlatParent").message())
            .contains("the primary key position 1: column 'pk_id' is java.lang.String,"
                + " but column 'org_code' on participant 'Org' is java.lang.String converted from java.lang.Long");
    }

    // ===== The census =====

    /**
     * The operation-member census reads the same capability the emitter does: a lowered root
     * carries one {@code ORDER_BY} member whose payload is the field's own ordering, and a
     * single-valued root, which lowers nothing, carries none. The mint pin compares the census with
     * the leaf-local crosswalk and would agree with both left unchanged, so this is the case that
     * notices an under-report.
     */
    @Test
    void theCensusReportsTheLoweredOrderingAsOneOrderByMember() {
        var schema = TestSchemaHelper.buildSchema(OCCUPANT_UNION + SORT + """
            type Query {
                occupants(orderBy: OccupantOrderBy @orderBy): [Occupant!]!
                    @defaultOrder(fields: [{name: "last_name"}])
                occupant: Occupant
            }
            """);
        var ordering = orderingOf(schema.field("Query", "occupants"));

        assertThat(schema.operationMembersOf("Query", "occupants"))
            .filteredOn(m -> m.kind() == OperationMember.Kind.ORDER_BY)
            .containsExactly(new OperationMember.PolymorphicOrderBy(ordering));
        assertThat(schema.operationMembersOf("Query", "occupant"))
            .noneMatch(m -> m.kind() == OperationMember.Kind.ORDER_BY);
    }

    // ===== Slot nullability =====

    @Test
    void declaredSlot_isNullableWhenEveryParticipantsColumnIs_andNotWhenNoneIs() {
        var field = rootField(OCCUPANT_UNION + """
            type Query { byEmail: [Occupant!]! @defaultOrder(fields: [{name: "email"}]) }
            """, "byEmail");
        assertThat(orderingOf(field).slots()).singleElement()
            .satisfies(slot -> assertThat(slot.nullable()).as("email admits NULL on both").isTrue());

        var byName = orderingOf(rootField(OCCUPANT_UNION + """
            type Query { byName: [Occupant!]! @asConnection @defaultOrder(fields: [{name: "last_name"}]) }
            """, "byName"));
        assertThat(byName.slots()).singleElement()
            .satisfies(slot -> assertThat(slot.nullable()).as("last_name is NOT NULL on both").isFalse());
        assertThat(byName.key()).isNotEmpty()
            .allSatisfy(slot -> assertThat(slot.nullable()).as("a key slot is never nullable").isFalse());
    }

    @Test
    void declaredSlot_isNullableWhenOneParticipantsColumnIs() {
        // label is NOT NULL on the table and nullable on the view over it, as jOOQ reports every
        // view column; the union column is NULL wherever one participant's is.
        var field = rootField("""
            type ValidityPeriod @table(name: "validity_period") { label: String }
            type ValidityPeriodView @table(name: "validity_period_view") { label: String }
            union Period = ValidityPeriod | ValidityPeriodView
            type Query { periods: [Period!]! @defaultOrder(fields: [{name: "label"}]) }
            """, "periods");
        assertThat(orderingOf(field).slots()).singleElement()
            .satisfies(slot -> assertThat(slot.nullable()).isTrue());
    }

    // ===== Helpers =====

    /**
     * {@code converter_site.org_code} is the converter-backed domain and {@code campus_count} a
     * bigint; {@code plain_org_site} carries the same names as a plain varchar and an int.
     */
    private static final String SITES = """
        type ConverterSite @table(name: "converter_site") { siteName: String @field(name: "site_name") }
        type PlainSite @table(name: "plain_org_site") { orgCode: String @field(name: "org_code") }
        union Site = ConverterSite | PlainSite
        """;

    /** Each key slot's columns' SQL names, in participant order, one list per key position. */
    private static void assertKey(PolymorphicOrdering ordering, List<List<String>> expected) {
        assertThat(ordering.key()).extracting(PolymorphicOrdering.Slot::ordinal)
            .as("a key slot's ordinal is its key position")
            .containsExactlyElementsOf(java.util.stream.IntStream.range(0, expected.size()).boxed().toList());
        assertThat(ordering.key())
            .extracting(slot -> slot.columnByParticipant().values().stream().map(c -> c.sqlName()).toList())
            .containsExactlyElementsOf(expected);
    }

    private static GraphitronField rootField(String sdl, String name) {
        return TestSchemaHelper.buildSchema(sdl).field("Query", name);
    }

    private static PolymorphicOrdering orderingOf(GraphitronField field) {
        assertThat(field).isInstanceOf(PolymorphicOrderingField.class);
        return ((PolymorphicOrderingField) field).ordering().orElseThrow();
    }

    private static ValidationError participantDiagnostic(GraphitronSchema schema, String coordinate) {
        return schema.diagnostics().stream()
            .filter(e -> e.coordinate().equals(coordinate))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no diagnostic at '" + coordinate + "' in "
                + TestSchemaHelper.diagnosticMessages(schema)));
    }

    private static GraphitronField multiSchemaRootField(String sdl, String name) {
        return TestSchemaHelper.buildSchema(sdl, multiSchemaContext()).field("Query", name);
    }

    private static RunContext multiSchemaContext() {
        return new RunContext(
            List.of(),
            Path.of(""), "MultiTableOrderingLoweringTest",
            Path.of(""),
            "fake.code.generated.multischema",
            DEFAULT_JOOQ_PACKAGE
        );
    }
}
