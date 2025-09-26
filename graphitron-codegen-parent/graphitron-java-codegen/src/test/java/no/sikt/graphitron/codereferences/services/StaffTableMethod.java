package no.sikt.graphitron.codereferences.services;

import no.sikt.graphitron.jooq.generated.testdata.public_.tables.Staff;

public class StaffTableMethod {
    public Staff staffTable(Staff staff, String firstName) {
        return staff.where(Staff.STAFF.FIRST_NAME.eq(firstName));
    }
    public Staff staffTable(Staff staff) {
        return staff;
    }
}