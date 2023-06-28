package no.fellesstudentsystem.graphitron.services;

import no.fellesstudentsystem.kjerneapi.tables.records.PersonRecord;
import no.fellesstudentsystem.kjerneapi.tables.records.StudentRecord;
import org.jooq.DSLContext;

import java.util.List;

/**
 * Fake service for mutation tests. Does not need to return meaningful values as only the generated result is tested.
 */
public class TestPersonService {
    private DSLContext context;

    public TestPersonService(DSLContext context) {
        this.context = context;
    }

    public String endrePerson0(String id) {
        return null;
    }

    public List<String> endrePerson1(List<String> id) {
        return null;
    }

    public PersonRecord endrePersonRecord0(String id) {
        return null;
    }

    public List<PersonRecord> endrePersonRecord1(List<String> id) {
        return null;
    }

    public List<PersonRecord> endrePersonRecord2(List<String> id) {
        return null;
    }

    public String endrePersonSimple(String id) {
        return null;
    }

    public String endrePersonInput(PersonRecord pr) {
        return null;
    }

    public String endrePersonInput(PersonRecord pr, String s) {
        return null;
    } // It's a trap, shouldn't pick this one.

    public String endrePerson2Params(PersonRecord pr, String s) {
        return null;
    }

    public EndrePersonResponse endrePersonResponse(String id) {
        return null;
    }

    public EndrePersonResponse endrePersonInputAndResponse(PersonRecord pr) {
        return null;
    }

    public PersonRecord endrePersonWithProfil(String id) {
        return null;
    }

    public EndrePersonResponse endrePersonWithProfilResponse(String id) {
        return null;
    }

    public List<String> endrePersonListSimple(List<String> id) {
        return null;
    }

    public List<String> endrePersonListInput(List<PersonRecord> pr) {
        return null;
    }

    public List<String> endrePersonList2Params(List<PersonRecord> pr, List<String> s) {
        return null;
    }

    public List<EndrePersonResponse> endrePersonListResponse(List<String> id) {
        return null;
    }

    public List<EndrePersonResponse> endrePersonListInputAndResponse(List<PersonRecord> pr) {
        return null;
    }

    public EndrePersonResponse endrePersonNested(PersonRecord pr0, PersonRecord pr1, PersonRecord pr2, PersonRecord pr3, List<PersonRecord> pr4, List<PersonRecord> pr5) {
        return new EndrePersonResponse();
    }

    public EndrePersonResponse endreError(PersonRecord pr0, PersonRecord pr1) {
        return new EndrePersonResponse();
    }

    public EndrePersonResponse endreErrorUnion1(String s) {
        return new EndrePersonResponse();
    }

    public EndrePersonResponse endreErrorUnion2(PersonRecord pr0, PersonRecord pr1) {
        return new EndrePersonResponse();
    }

    public static class EndrePersonResponse {
        public String getId() {
            return "";
        }

        public EndrePersonResponse2 getEndreResponse2() {
            return new EndrePersonResponse2();
        }

        public List<EndrePersonResponse3> getEndreResponse3() {
            return List.of(new EndrePersonResponse3());
        }
    }

    public static class EndrePersonResponse2 {
        public String getId2() {
            return "";
        }

        public PersonRecord getPersonProfil() {
            return new PersonRecord();
        }
    }

    public static class EndrePersonResponse3 {
        public String getId3() {
            return "";
        }

        public PersonRecord getPers3() {
            return new PersonRecord();
        }

        public List<EndrePersonResponse4> getEndre4() {
            return List.of(new EndrePersonResponse4());
        }
    }

    public static class EndrePersonResponse4 {
        public String getId4() {
            return "";
        }

        public StudentRecord getPostAdr4() {
            return new StudentRecord();
        }
    }
}
