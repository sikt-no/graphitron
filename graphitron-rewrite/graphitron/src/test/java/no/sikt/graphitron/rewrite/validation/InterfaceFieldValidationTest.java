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

/**
 * Validates {@link InterfaceField} (R36 Track B3's child case): same field-level PK-presence
 * and PK-arity rules as the root case, plus the joinPath component which is structurally
 * exercised here without enforcing path-shape (the classifier already rejects path failures
 * upstream, so the validator focuses on participant-set integrity).
 */
@UnitTier
class InterfaceFieldValidationTest {

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
}
