package no.sikt.graphitron.rewrite.validation;

import no.sikt.graphitron.rewrite.RejectionKind;
import no.sikt.graphitron.rewrite.model.ChildField.UnionField;
import no.sikt.graphitron.rewrite.model.ColumnRef;
import no.sikt.graphitron.rewrite.model.FieldWrapper;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.ParticipantRef;
import no.sikt.graphitron.rewrite.model.ReturnTypeRef;
import no.sikt.graphitron.rewrite.model.TableRef;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static no.sikt.graphitron.rewrite.validation.FieldValidationTestHelper.assertHasKind;
import static no.sikt.graphitron.rewrite.validation.FieldValidationTestHelper.validate;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Validates {@link UnionField} (the multi-table polymorphic child case for unions). Mirrors
 * {@link InterfaceFieldValidationTest}; same PK-presence and PK-arity rules apply.
 */
@UnitTier
class UnionFieldValidationTest {

    private static final TableRef CUSTOMER = new TableRef("customer", "CUSTOMER", "Customer",
        List.of(new ColumnRef("customer_id", "CUSTOMER_ID", "java.lang.Integer")));
    private static final TableRef STAFF = new TableRef("staff", "STAFF", "Staff",
        List.of(new ColumnRef("staff_id", "STAFF_ID", "java.lang.Integer")));
    private static final TableRef NO_PK = new TableRef("kpis", "KPIS", "Kpis", List.of());
    private static final TableRef BAR = new TableRef("bar", "BAR", "Bar",
        List.of(
            new ColumnRef("id_1", "ID_1", "java.lang.Integer"),
            new ColumnRef("id_2", "ID_2", "java.lang.Integer")));

    @Test
    void wellFormed_twoSameArityParticipants_noErrors() {
        var participants = List.<ParticipantRef>of(
            new ParticipantRef.TableBound("Customer", CUSTOMER, null),
            new ParticipantRef.TableBound("Staff", STAFF, null));
        var field = new UnionField("Address", "activities", null,
            new ReturnTypeRef.PolymorphicReturnType("AddressActivity", new FieldWrapper.List(false, false)),
            participants, Map.of("Customer", List.of(), "Staff", List.of()));
        assertThat(validate(field)).isEmpty();
    }

    @Test
    void rejects_unionMemberWithoutPrimaryKey() {
        var participants = List.<ParticipantRef>of(
            new ParticipantRef.TableBound("Customer", CUSTOMER, null),
            new ParticipantRef.TableBound("Kpis", NO_PK, null));
        var field = new UnionField("Address", "activities", null,
            new ReturnTypeRef.PolymorphicReturnType("AddressActivity", new FieldWrapper.List(false, false)),
            participants, Map.of("Customer", List.of(), "Kpis", List.of()));
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
            participants, Map.of("Customer", List.of(), "Staff", List.of()));
        var parentType = new GraphitronType.TableType("Bar", null, BAR);
        var errors = validate(FieldValidationTestHelper.schema(parentType, field.name(), field));
        assertThat(errors).isEmpty();
    }
}
