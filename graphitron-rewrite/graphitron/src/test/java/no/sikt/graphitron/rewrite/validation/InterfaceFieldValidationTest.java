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
            participants, Map.of("Customer", List.of(), "Staff", List.of()), null, null);
        assertThat(validate(field)).isEmpty();
    }

    @Test
    void rejects_participantWithoutPrimaryKey() {
        var participants = List.<ParticipantRef>of(
            new ParticipantRef.TableBound("Customer", CUSTOMER, null),
            new ParticipantRef.TableBound("Kpis", NO_PK, null));
        var field = new InterfaceField("Address", "occupants", null,
            new ReturnTypeRef.PolymorphicReturnType("AddressOccupant", new FieldWrapper.List(false, false)),
            participants, Map.of("Customer", List.of(), "Kpis", List.of()), null, null);
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
            participants, Map.of("Customer", List.of(), "Bar", List.of()), null, null);
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
            participants, Map.of("Customer", List.of(), "Staff", List.of()), null, null);
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
            participants, Map.of("Customer", List.of(), "Staff", List.of()), null, null);
        var parentType = new GraphitronType.TableType("Kpis", null, NO_PK);
        var errors = validate(FieldValidationTestHelper.schema(parentType, field.name(), field));
        assertHasKind(errors, RejectionKind.AUTHOR_ERROR,
            "Field 'Kpis.occupantsConnection': multi-table interface/union child field "
                + "requires a non-empty primary key on the parent type 'Kpis', since the "
                + "DataLoader key tuple is built from the parent's PK columns");
    }

    @Test
    void rejects_listArm_onPkLessParent() {
        // R102: the list arm now also requires a non-empty parent PK (DataLoader-batched).
        // Mirrors the connection-arm rejection but on the list cardinality.
        var participants = List.<ParticipantRef>of(
            new ParticipantRef.TableBound("Customer", CUSTOMER, null),
            new ParticipantRef.TableBound("Staff", STAFF, null));
        var field = new InterfaceField("Kpis", "occupants", null,
            new ReturnTypeRef.PolymorphicReturnType("AddressOccupant", new FieldWrapper.List(false, false)),
            participants, Map.of("Customer", List.of(), "Staff", List.of()), null, null);
        var parentType = new GraphitronType.TableType("Kpis", null, NO_PK);
        var errors = validate(FieldValidationTestHelper.schema(parentType, field.name(), field));
        assertHasKind(errors, RejectionKind.AUTHOR_ERROR,
            "Field 'Kpis.occupants': multi-table interface/union child field "
                + "requires a non-empty primary key on the parent type 'Kpis'");
    }

    @Test
    void wellFormed_listArm_onSinglePkParent_noErrors() {
        // List arm equivalent of the connection-arm well-formed acceptance test.
        var participants = List.<ParticipantRef>of(
            new ParticipantRef.TableBound("Customer", CUSTOMER, null),
            new ParticipantRef.TableBound("Staff", STAFF, null));
        var field = new InterfaceField("Address", "occupants", null,
            new ReturnTypeRef.PolymorphicReturnType("AddressOccupant", new FieldWrapper.List(false, false)),
            participants, Map.of("Customer", List.of(), "Staff", List.of()), null, null);
        var addressTable = TestFixtures.tableRef("address", "ADDRESS", "Address",
            List.of(new ColumnRef("address_id", "ADDRESS_ID", "java.lang.Integer")));
        var parentType = new GraphitronType.TableType("Address", null, addressTable);
        var errors = validate(FieldValidationTestHelper.schema(parentType, field.name(), field));
        assertThat(errors).isEmpty();
    }

    @Test
    void wellFormed_connection_onSinglePkParent_noErrors() {
        // Single-column parent PK was the v1 shape and remains supported under the widened RowN form.
        var participants = List.<ParticipantRef>of(
            new ParticipantRef.TableBound("Customer", CUSTOMER, null),
            new ParticipantRef.TableBound("Staff", STAFF, null));
        var field = new InterfaceField("Address", "occupantsConnection", null,
            new ReturnTypeRef.PolymorphicReturnType("AddressOccupant", new FieldWrapper.Connection(false, 5)),
            participants, Map.of("Customer", List.of(), "Staff", List.of()), null, null);
        var addressTable = TestFixtures.tableRef("address", "ADDRESS", "Address",
            List.of(new ColumnRef("address_id", "ADDRESS_ID", "java.lang.Integer")));
        var parentType = new GraphitronType.TableType("Address", null, addressTable);
        var errors = validate(FieldValidationTestHelper.schema(parentType, field.name(), field));
        assertThat(errors).isEmpty();
    }

    @Test
    void rejects_connection_onParentPkArityOver21() {
        assertHasKind(validateAgainstWidePkParent(new FieldWrapper.Connection(false, 5), 22),
            RejectionKind.AUTHOR_ERROR,
            "Field 'Wide.occupants': multi-table interface/union child field whose parent type "
                + "'Wide' has a primary key with 22 columns exceeds jOOQ's typed Row22 cap "
                + "(parent PK + idx must fit in Row<N+1>)");
    }

    @Test
    void rejects_listArm_onParentPkArityOver21() {
        // R102 finding #5: list arm widens to Row<N+1> via the shared parentInput VALUES emitter
        // exactly like connection, so the arity cap is uniformly 21 across both arms (a 22-PK
        // parent would generate a reference to non-existent org.jooq.Row23 inside the rows
        // method). Validator rejects it before codegen.
        assertHasKind(validateAgainstWidePkParent(new FieldWrapper.List(false, false), 22),
            RejectionKind.AUTHOR_ERROR,
            "Field 'Wide.occupants': multi-table interface/union child field whose parent type "
                + "'Wide' has a primary key with 22 columns exceeds jOOQ's typed Row22 cap "
                + "(parent PK + idx must fit in Row<N+1>)");
    }

    @Test
    void wellFormed_connection_onParentPkArity21_noErrors() {
        // Boundary case: 21 PK columns + idx = Row22 fits. One under the cap is well-formed.
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
        var field = new InterfaceField("Wide", "occupants", null,
            new ReturnTypeRef.PolymorphicReturnType("AddressOccupant", wrapper),
            participants, Map.of("Customer", List.of(), "Staff", List.of()), null, null);
        var parentType = new GraphitronType.TableType("Wide", null, wide);
        return validate(FieldValidationTestHelper.schema(parentType, field.name(), field));
    }
}
