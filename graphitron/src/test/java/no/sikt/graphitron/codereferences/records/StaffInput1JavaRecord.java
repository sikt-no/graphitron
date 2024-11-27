package no.sikt.graphitron.codereferences.records;

import java.util.List;

public class StaffInput1JavaRecord {
    private List<StaffNameJavaRecord> names;
    private String email;
    private Boolean active;

    public List<StaffNameJavaRecord> getNamesRecord() {
        return this.names;
    }

    public void setNamesRecord(List<StaffNameJavaRecord> names) {
        this.names = names;
    }

    public String getEmail() {
        return this.email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public Boolean getActive() {
        return this.active;
    }

    public void setActive(Boolean active) {
        this.active = active;
    }
}
