package no.sikt.graphitron.example.service.records;

public class NestedRecordOuterInput {
    private String topField;
    private NestedRecordMiddleInput nested;

    public NestedRecordOuterInput() {
    }

    public String getTopField() {
        return topField;
    }

    public void setTopField(String topField) {
        this.topField = topField;
    }

    public NestedRecordMiddleInput getNested() {
        return nested;
    }

    public void setNested(NestedRecordMiddleInput nested) {
        this.nested = nested;
    }
}
