package no.sikt.graphitron.rewrite.validation;

import no.sikt.graphitron.rewrite.GraphitronSchema;
import no.sikt.graphitron.rewrite.RejectionKind;
import no.sikt.graphitron.rewrite.model.ChildField.InterfaceField;
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
import no.sikt.graphitron.rewrite.TestFixtures;

/**
 * Validates {@link InterfaceField} (the multi-table polymorphic child case): same field-level PK-presence
 * and PK-arity rules as the root case, plus the joinPath component which is structurally
 * exercised here without enforcing path-shape (the classifier already rejects path failures
 * upstream, so the validator focuses on participant-set integrity).
 */
@UnitTier
class InterfaceFieldValidationTest {

    private static final TableRef CUSTOMER = TestFixtures.tableRef("customer", "CUSTOMER", "Customer",
        List.of(new ColumnRef("customer_id", "CUSTOMER_ID", "java.lang.Integer")));
    private static final TableRef STAFF = TestFixtures.tableRef("staff", "STAFF", "Staff",
        List.of(new ColumnRef("staff_id", "STAFF_ID", "java.lang.Integer")));
    private static final TableRef NO_PK = TestFixtures.tableRef("kpis", "KPIS", "Kpis", List.of());
    private static final TableRef BAR = TestFixtures.tableRef("bar", "BAR", "Bar",
        List.of(
            new ColumnRef("id_1", "ID_1", "java.lang.Integer"),
            new ColumnRef("id_2", "ID_2", "java.lang.Integer")));

    @Test
    void wellFormed_twoSameArityParticipants_noErrors() {
        var participants = List.<ParticipantRef>of(
            new ParticipantRef.TableBound("Customer", CUSTOMER, null),
            new ParticipantRef.TableBound("Staff", STAFF, null));
        var field = new InterfaceField("Address", "occupants", null,
            new ReturnTypeRef.PolymorphicReturnType("AddressOccupant", new FieldWrapper.List(false, false)),
            participants, Map.of("Customer", List.of(), "Staff", List.of()));
        assertThat(validate(field)).isEmpty();
    }

    @Test
    void rejects_participantWithoutPrimaryKey() {
        var participants = List.<ParticipantRef>of(
            new ParticipantRef.TableBound("Customer", CUSTOMER, null),
            new ParticipantRef.TableBound("Kpis", NO_PK, null));
        var field = new InterfaceField("Address", "occupants", null,
            new ReturnTypeRef.PolymorphicReturnType("AddressOccupant", new FieldWrapper.List(false, false)),
            participants, Map.of("Customer", List.of(), "Kpis", List.of()));
        assertHasKind(validate(field), RejectionKind.AUTHOR_ERROR,
            "Field 'Address.occupants': participant 'Kpis' has no primary key");
    }

    @Test
    void rejects_mismatchedPkArityAcrossParticipants() {
        var participants = List.<ParticipantRef>of(
            new ParticipantRef.TableBound("Customer", CUSTOMER, null),
            new ParticipantRef.TableBound("Bar", BAR, null));
        var field = new InterfaceField("Address", "occupants", null,
            new ReturnTypeRef.PolymorphicReturnType("AddressOccupant", new FieldWrapper.List(false, false)),
            participants, Map.of("Customer", List.of(), "Bar", List.of()));
        assertHasKind(validate(field), RejectionKind.AUTHOR_ERROR,
            "Field 'Address.occupants': primary-key arity mismatch");
    }

    @Test
    void wellFormed_connection_onCompositePkParent_noErrors() {
        // Composite-PK parent is supported: the DataLoader key type widens from Row1<...> to
        // RowN<...>.
        var participants = List.<ParticipantRef>of(
            new ParticipantRef.TableBound("Customer", CUSTOMER, null),
            new ParticipantRef.TableBound("Staff", STAFF, null));
        var field = new InterfaceField("Bar", "occupantsConnection", null,
            new ReturnTypeRef.PolymorphicReturnType("AddressOccupant", new FieldWrapper.Connection(false, 5)),
            participants, Map.of("Customer", List.of(), "Staff", List.of()));
        var parentType = new GraphitronType.TableType("Bar", null, BAR);
        var errors = validate(FieldValidationTestHelper.schema(parentType, field.name(), field));
        assertThat(errors).isEmpty();
    }

    @Test
    void rejects_connection_onPkLessParent() {
        // Empty-PK parent has no key for the DataLoader VALUES table. Validator rejects with a
        // clean AUTHOR_ERROR instead of letting codegen trip the emitter's defensive arity-0
        // tripwire.
        var participants = List.<ParticipantRef>of(
            new ParticipantRef.TableBound("Customer", CUSTOMER, null),
            new ParticipantRef.TableBound("Staff", STAFF, null));
        var field = new InterfaceField("Kpis", "occupantsConnection", null,
            new ReturnTypeRef.PolymorphicReturnType("AddressOccupant", new FieldWrapper.Connection(false, 5)),
            participants, Map.of("Customer", List.of(), "Staff", List.of()));
        var parentType = new GraphitronType.TableType("Kpis", null, NO_PK);
        var errors = validate(FieldValidationTestHelper.schema(parentType, field.name(), field));
        assertHasKind(errors, RejectionKind.AUTHOR_ERROR,
            "Field 'Kpis.occupantsConnection': @asConnection on a multi-table interface/union "
                + "child field requires the parent type 'Kpis' to have a primary key");
    }

    @Test
    void wellFormed_connection_onSinglePkParent_noErrors() {
        // Single-column parent PK was the v1 shape and remains supported under the widened RowN form.
        var participants = List.<ParticipantRef>of(
            new ParticipantRef.TableBound("Customer", CUSTOMER, null),
            new ParticipantRef.TableBound("Staff", STAFF, null));
        var field = new InterfaceField("Address", "occupantsConnection", null,
            new ReturnTypeRef.PolymorphicReturnType("AddressOccupant", new FieldWrapper.Connection(false, 5)),
            participants, Map.of("Customer", List.of(), "Staff", List.of()));
        var addressTable = TestFixtures.tableRef("address", "ADDRESS", "Address",
            List.of(new ColumnRef("address_id", "ADDRESS_ID", "java.lang.Integer")));
        var parentType = new GraphitronType.TableType("Address", null, addressTable);
        var errors = validate(FieldValidationTestHelper.schema(parentType, field.name(), field));
        assertThat(errors).isEmpty();
    }
}
