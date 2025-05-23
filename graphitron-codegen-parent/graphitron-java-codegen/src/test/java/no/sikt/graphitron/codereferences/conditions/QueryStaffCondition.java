package no.sikt.graphitron.codereferences.conditions;

import no.sikt.graphitron.jooq.generated.testdata.public_.tables.Staff;
import org.jooq.Condition;

public class QueryStaffCondition {
    public static Condition firstname(Staff staff, String firstname) {
        return null;
    }

    public static Condition lastname(Staff staff, String lastname) {
        return null;
    }

    public static Condition name(Staff staff, String firstname, String lastname) {
        return null;
    }

    public static Condition email(Staff staff, String email) {
        return null;
    }

    public static Condition field(Staff staff, String firstname, String lastname, Boolean active) {
        return null;
    }

    public static Condition staff(Staff staff, String firstname, String lastname, String email, Boolean active) {
        return null;
    }

    public static Condition staffMin(Staff staff, String firstname, String lastname, Boolean active) {
        return null;
    }
}
