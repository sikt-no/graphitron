package no.fellesstudentsystem.graphitron.services;

import no.fellesstudentsystem.kjerneapi.tables.records.PermisjonRecord;
import org.jooq.DSLContext;

/**
 * Fake service for mutation tests. Does not need to return meaningful values as only the generated result is tested.
 */
public class TestPermisjonService {
    private DSLContext context;

    public TestPermisjonService(DSLContext context) {
        this.context = context;
    }

    public EndreResponse endrePermisjon(PermisjonRecord r) {
        return null;
    }

    public static class EndreResponse {
        public String getId() {
            return "";
        }
        public String getStudentId() {return "";}
    }
}
