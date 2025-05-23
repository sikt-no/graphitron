package no.sikt.graphitron.codereferences.extensionmethods;

import no.sikt.graphitron.jooq.generated.testdata.public_.tables.Customer;
import org.jooq.Field;
import org.jooq.impl.DSL;

public class ClassWithDuplicatedExtensionMethod {
    public static Field<String> duplicated(Customer customer) {
        return DSL.field("", String.class);
    }
}
