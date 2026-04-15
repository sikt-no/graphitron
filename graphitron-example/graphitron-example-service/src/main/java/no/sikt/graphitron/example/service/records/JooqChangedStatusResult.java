package no.sikt.graphitron.example.service.records;

public class JooqChangedStatusResult {
    private String firstNameStatus;

    public JooqChangedStatusResult() {
    }

    public JooqChangedStatusResult(String firstNameStatus) {
        this.firstNameStatus = firstNameStatus;
    }

    public String getFirstNameStatus() {
        return firstNameStatus;
    }

    public void setFirstNameStatus(String firstNameStatus) {
        this.firstNameStatus = firstNameStatus;
    }
}