package no.sikt.graphitron.example.service.records;

public class NestedRecordMiddleInput {
    private String middleField;
    private NestedRecordDeepInput deepNested;

    public NestedRecordMiddleInput() {
    }

    public String getMiddleField() {
        return middleField;
    }

    public void setMiddleField(String middleField) {
        this.middleField = middleField;
    }

    public NestedRecordDeepInput getDeepNested() {
        return deepNested;
    }

    public void setDeepNested(NestedRecordDeepInput deepNested) {
        this.deepNested = deepNested;
    }
}
