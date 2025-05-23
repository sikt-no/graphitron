package no.sikt.graphitron.codereferences.extensionmethods;

import no.sikt.graphitron.jooq.generated.testdata.public_.tables.Customer;
import org.jooq.Field;
import org.jooq.impl.DSL;

public class ClassWithExtensionMethod {
    public static Field<String> name(Customer customer) {
        return DSL.field("", String.class);
    }

    public static Field<String> duplicated(Customer customer) {
        return DSL.field("", String.class);
    }

    public static Field<Integer> wrongGenericReturnType(Customer customer) {
        return DSL.field("", Integer.class);
    }

    public static String wrongReturnType(Customer customer) {
        return "";
    }

}

