package no.fellesstudentsystem.graphitron.codereferences.conditions;

import no.fellesstudentsystem.graphitron.codereferences.records.StaffInput1JavaRecord;
import no.fellesstudentsystem.graphitron.codereferences.records.StaffNameJavaRecord;
import no.sikt.graphitron.jooq.generated.testdata.public_.tables.Staff;
import org.jooq.Condition;

import java.util.List;

public class RecordStaffCondition {
    public static Condition firstname(Staff staff, String firstname) {
        return null;
    }

    public static Condition lastname(Staff staff, String lastname) {
        return null;
    }

    public static Condition name(Staff staff, StaffNameJavaRecord name) {
        return null;
    }

    public static Condition nameList(Staff staff, List<StaffNameJavaRecord> names) {
        return null;
    }

    public static Condition fieldWithListInput(Staff staff, List<StaffNameJavaRecord> names, Boolean active) {
        return null;
    }

    public static Condition input1(Staff staff, List<StaffInput1JavaRecord> input1) {
        return null;
    }
}
