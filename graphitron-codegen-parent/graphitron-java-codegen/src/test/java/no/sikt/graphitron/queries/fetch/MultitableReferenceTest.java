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
    @DisplayName("Default case on interface with all implicit references")
    void defaultCase() {
        assertGeneratedContentContains("default",
                ".from(payment_425747824_customer).orderBy",
                ".from(payment_425747824_staff).orderBy"
        );
    }

    @Test
    @DisplayName("On union with all implicit references")
    void union() {
        assertGeneratedContentContains("union",
                ".from(payment_425747824_customer).orderBy",
                ".from(payment_425747824_staff).orderBy"
        );
    }

    @Test
    @DisplayName("All implementations have their reference path specified")
    void allSpecified() {
        assertGeneratedContentContains("allSpecified",
                ".from(payment_425747824_customer).orderBy",
                ".from(payment_425747824_staff).orderBy"
        );
    }

    @Test
    @DisplayName("One implicit reference in multitable reference directive")
    void oneImplicit() {
        assertGeneratedContentContains("oneImplicit",
                ".from(payment_425747824_customer).orderBy",
                ".from(payment_425747824_staff).orderBy"
        );
    }

    @Test
    @DisplayName("Reference path starting with condition and no key")
    void conditionReference() {
        assertGeneratedContentContains("conditionReference",
                "_payment = PAYMENT.as(\"p", // Outer alias
                "payment_for_staff = PAYMENT.as(\"S", // Inner alias (in sort fields method)
                ".from(payment_for_staff).join(payment_for_staff_paymentstaff_staff).on(no",
                "paymentStaff(payment_for_staff, payment_for_staff_paymentstaff_staff))" +
                        ".where(_payment.PAYMENT_ID.eq(payment_for_staff.PAYMENT_ID))"
        );
    }

    @Test
    @DisplayName("Reference path starting with key and condition")
    void conditionReferenceWithKey() {
        assertGeneratedContentContains("conditionReferenceWithKey",
                ".from(payment_425747824_staff).where(no.",
                "paymentStaff(_payment, payment_425747824_staff)"
        );
    }

    @Test
    @DisplayName("With condition reference after key")
    void conditionReferenceAfterKey() {
        assertGeneratedContentContains("conditionReferenceAfterKey",
                "rental_4012516862_payment_paymentstaff_staff = STAFF",
                ".from(rental_4012516862_payment).join(rental_4012516862_payment_paymentstaff_staff).on(no.",
                "paymentStaff(rental_4012516862_payment, rental_4012516862_payment_paymentstaff_staff)"
        );
    }
}
