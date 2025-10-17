package no.sikt.graphitron.queries.fetch;

import no.sikt.graphitron.common.configuration.SchemaComponent;
import no.sikt.graphitron.generators.abstractions.ClassGenerator;
import no.sikt.graphitron.reducedgenerators.UnionOnlyFetchDBClassGenerator;
import no.sikt.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static no.sikt.graphitron.common.configuration.SchemaComponent.PERSON_WITH_EMAIL;

@DisplayName("Multi-table references - Queries")
public class MultitableReferenceTest extends ReferenceTest {

    @Override
    protected String getSubpath() {
        return super.getSubpath() + "/multitable";
    }

    @Override
    protected List<ClassGenerator> makeGenerators(ProcessedSchema schema) {
        return List.of(new UnionOnlyFetchDBClassGenerator(schema));
    }

    @Override
    protected Set<SchemaComponent> getComponents() {
        return makeComponents(PERSON_WITH_EMAIL);
    }

    @Test
    @DisplayName("On multitable interface with all implicit references")
    void multitableInterface() {
        assertGeneratedContentContains("multitableInterface",
                ".from(_a_payment_1831371789_customer).orderBy",
                ".from(_a_payment_1831371789_staff).orderBy"
        );
    }

    @Test
    @DisplayName("On union with all implicit references")
    void union() {
        assertGeneratedContentContains("union",
                ".from(_a_payment_1831371789_customer).orderBy",
                ".from(_a_payment_1831371789_staff).orderBy"
        );
    }

    @Test
    @DisplayName("All implementations have their reference path specified")
    void allSpecified() {
        assertGeneratedContentContains("allSpecified",
                ".from(_a_payment_1831371789_customer).orderBy",
                ".from(_a_payment_1831371789_staff).orderBy"
        );
    }

    @Test
    @DisplayName("One implicit reference in multitable reference directive")
    void oneImplicit() {
        assertGeneratedContentContains("oneImplicit",
                ".from(_a_payment_1831371789_customer).orderBy",
                ".from(_a_payment_1831371789_staff).orderBy"
        );
    }

    @Test
    @DisplayName("Reference path starting with condition and no key")
    void conditionReference() {
        assertGeneratedContentContains("conditionReference",
                "payment = PAYMENT.as(\"p", // Outer alias
                "payment_for_staff = PAYMENT.as(\"p", // Inner alias (in sort fields method)
                ".from(_a_payment_for_staff).join(_a_payment_for_staff_paymentstaff_staff).on(no",
                "paymentStaff(_a_payment_for_staff, _a_payment_for_staff_paymentstaff_staff))" +
                        ".where(_a_payment.PAYMENT_ID.eq(_a_payment_for_staff.PAYMENT_ID))"
        );
    }

    @Test
    @DisplayName("Reference path starting with key and condition")
    void conditionReferenceWithKey() {
        assertGeneratedContentContains("conditionReferenceWithKey",
                ".from(_a_payment_1831371789_staff).where(no.",
                "paymentStaff(_a_payment, _a_payment_1831371789_staff)"
        );
    }

    @Test
    @DisplayName("With condition reference after key")
    void conditionReferenceAfterKey() {
        assertGeneratedContentContains("conditionReferenceAfterKey",
                "rental_370786941_payment_paymentstaff_staff = STAFF",
                ".from(_a_rental_370786941_payment).join(_a_rental_370786941_payment_paymentstaff_staff).on(no.",
                "paymentStaff(_a_rental_370786941_payment, _a_rental_370786941_payment_paymentstaff_staff)"
        );
    }
}
