package no.sikt.graphitron.rewrite.validation;

import no.sikt.graphitron.rewrite.RejectionKind;
import no.sikt.graphitron.rewrite.model.ChildField.UnionField;
import no.sikt.graphitron.rewrite.model.ColumnRef;
import no.sikt.graphitron.rewrite.model.FieldWrapper;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.ParticipantRef;
import no.sikt.graphitron.rewrite.model.ReturnTypeRef;
import no.sikt.graphitron.rewrite.model.SourceKey;
import no.sikt.graphitron.rewrite.model.TableRef;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static no.sikt.graphitron.rewrite.validation.FieldValidationTestHelper.assertHasKind;
import static no.sikt.graphitron.rewrite.validation.FieldValidationTestHelper.validate;
import static org.assertj.core.api.Assertions.assertThat;
import no.sikt.graphitron.rewrite.TestFixtures;

/**
 * Validates {@link UnionField} (the multi-table polymorphic child case for unions). Mirrors
 * {@link InterfaceFieldValidationTest}; same PK-presence and PK-arity rules apply.
 */
@UnitTier
class UnionFieldValidationTest {

    private static final TableRef CUSTOMER = TestFixtures.tableRef("customer", "CUSTOMER", "Customer",
        List.of(new ColumnRef("customer_id", "CUSTOMER_ID", "java.lang.Integer")));
    private static final TableRef STAFF = TestFixtures.tableRef("staff", "STAFF", "Staff",
        List.of(new ColumnRef("staff_id", "STAFF_ID", "java.lang.Integer")));
    private static final TableRef ADDRESS = TestFixtures.tableRef("address", "ADDRESS", "Address",
        List.of(new ColumnRef("address_id", "ADDRESS_ID", "java.lang.Integer")));
    private static final TableRef BAR = TestFixtures.tableRef("bar", "BAR", "Bar",
        List.of(
            new ColumnRef("id_1", "ID_1", "java.lang.Integer"),
            new ColumnRef("id_2", "ID_2", "java.lang.Integer")));

    private static SourceKey rowKeyedFor(TableRef table) {
        return TestFixtures.polymorphicRowParentSourceKey(table.primaryKeyColumns());
    }

    private static GraphitronType.ResultType resultTypeFor(TableRef table) {
        return new GraphitronType.JooqTableRecordType(table.tableName(), null, null, table);
    }

    @Test
    void wellFormed_twoSameArityParticipants_noErrors() {
        var participants = List.<ParticipantRef>of(
            new ParticipantRef.TableBound("Customer", CUSTOMER, null),
            new ParticipantRef.TableBound("Staff", STAFF, null));
        var field = new UnionField("Address", "activities", null,
            new ReturnTypeRef.PolymorphicReturnType("AddressActivity", new FieldWrapper.List(false, false)),
            participants, Map.of("Customer", List.of(), "Staff", List.of()), rowKeyedFor(ADDRESS), ADDRESS, resultTypeFor(ADDRESS));
        assertThat(validate(field)).isEmpty();
    }

    @Test
    void rejects_unionMemberWithoutPrimaryKey() {
        var noPk = TestFixtures.tableRef("kpis", "KPIS", "Kpis", List.of());
        var participants = List.<ParticipantRef>of(
            new ParticipantRef.TableBound("Customer", CUSTOMER, null),
            new ParticipantRef.TableBound("Kpis", noPk, null));
        var field = new UnionField("Address", "activities", null,
            new ReturnTypeRef.PolymorphicReturnType("AddressActivity", new FieldWrapper.List(false, false)),
            participants, Map.of("Customer", List.of(), "Kpis", List.of()), rowKeyedFor(ADDRESS), ADDRESS, resultTypeFor(ADDRESS));
        assertHasKind(validate(field), RejectionKind.AUTHOR_ERROR,
            "Field 'Address.activities': participant 'Kpis' has no primary key");
    }

    @Test
    void wellFormed_connection_onCompositePkParent_noErrors() {
        // Union variant of InterfaceFieldValidationTest's composite-parent acceptance test.
        // Composite-PK parents are supported via RowN widening on the DataLoader VALUES table.
        var participants = List.<ParticipantRef>of(
            new ParticipantRef.TableBound("Customer", CUSTOMER, null),
            new ParticipantRef.TableBound("Staff", STAFF, null));
        var field = new UnionField("Bar", "activitiesConnection", null,
            new ReturnTypeRef.PolymorphicReturnType("AddressActivity", new FieldWrapper.Connection(false, 5)),
            participants, Map.of("Customer", List.of(), "Staff", List.of()), rowKeyedFor(BAR), BAR, resultTypeFor(BAR));
        var parentType = new GraphitronType.TableType("Bar", null, BAR);
        var errors = validate(FieldValidationTestHelper.schema(parentType, field.name(), field));
        assertThat(errors).isEmpty();
    }

    @Test
    void wellFormed_listArm_onSinglePkParent_noErrors() {
        // Union mirror of InterfaceFieldValidationTest.wellFormed_listArm_onSinglePkParent.
        var participants = List.<ParticipantRef>of(
            new ParticipantRef.TableBound("Customer", CUSTOMER, null),
            new ParticipantRef.TableBound("Staff", STAFF, null));
        var field = new UnionField("Address", "activities", null,
            new ReturnTypeRef.PolymorphicReturnType("AddressActivity", new FieldWrapper.List(false, false)),
            participants, Map.of("Customer", List.of(), "Staff", List.of()), rowKeyedFor(ADDRESS), ADDRESS, resultTypeFor(ADDRESS));
        var parentType = new GraphitronType.TableType("Address", null, ADDRESS);
        var errors = validate(FieldValidationTestHelper.schema(parentType, field.name(), field));
        assertThat(errors).isEmpty();
    }

    @Test
    void rejects_connection_onParentPkArityOver21() {
        assertHasKind(validateAgainstWidePkParent(new FieldWrapper.Connection(false, 5), 22),
            RejectionKind.AUTHOR_ERROR,
            "Field 'Wide.activities': multi-table interface/union child field whose parent type "
                + "'Wide' has a parent key with 22 columns exceeds jOOQ's typed Row22 cap "
                + "(parent key + idx must fit in Row<N+1>)");
    }

    @Test
    void rejects_listArm_onParentPkArityOver21() {
        assertHasKind(validateAgainstWidePkParent(new FieldWrapper.List(false, false), 22),
            RejectionKind.AUTHOR_ERROR,
            "Field 'Wide.activities': multi-table interface/union child field whose parent type "
                + "'Wide' has a parent key with 22 columns exceeds jOOQ's typed Row22 cap "
                + "(parent key + idx must fit in Row<N+1>)");
    }

    @Test
    void wellFormed_connection_onParentPkArity21_noErrors() {
        assertThat(validateAgainstWidePkParent(new FieldWrapper.Connection(false, 5), 21)).isEmpty();
    }

    @Test
    void wellFormed_listArm_onParentPkArity21_noErrors() {
        assertThat(validateAgainstWidePkParent(new FieldWrapper.List(false, false), 21)).isEmpty();
    }

    private static java.util.List<no.sikt.graphitron.rewrite.ValidationError> validateAgainstWidePkParent(
            FieldWrapper wrapper, int pkArity) {
        var pkCols = new java.util.ArrayList<ColumnRef>();
        for (int i = 0; i < pkArity; i++) {
            pkCols.add(new ColumnRef("k" + i, "K" + i, "java.lang.Integer"));
        }
        var wide = TestFixtures.tableRef("wide", "WIDE", "Wide", pkCols);
        var participants = List.<ParticipantRef>of(
            new ParticipantRef.TableBound("Customer", CUSTOMER, null),
            new ParticipantRef.TableBound("Staff", STAFF, null));
        var field = new UnionField("Wide", "activities", null,
            new ReturnTypeRef.PolymorphicReturnType("AddressActivity", wrapper),
            participants, Map.of("Customer", List.of(), "Staff", List.of()), rowKeyedFor(wide), wide, resultTypeFor(wide));
        var parentType = new GraphitronType.TableType("Wide", null, wide);
        return validate(FieldValidationTestHelper.schema(parentType, field.name(), field));
    }
}
