package no.sikt.graphitron.example.service.records;

public class NestedRecordResult {
    private String topField;
    private String middleField;
    private String deepField;
    private Integer deepNumber;

    public NestedRecordResult() {
    }

    public NestedRecordResult(String topField, String middleField, String deepField, Integer deepNumber) {
        this.topField = topField;
        this.middleField = middleField;
        this.deepField = deepField;
        this.deepNumber = deepNumber;
    }

    public String getTopField() {
        return topField;
    }

    public void setTopField(String topField) {
        this.topField = topField;
    }

    public String getMiddleField() {
        return middleField;
    }

    public void setMiddleField(String middleField) {
        this.middleField = middleField;
    }

    public String getDeepField() {
        return deepField;
    }

    public void setDeepField(String deepField) {
        this.deepField = deepField;
    }

    public Integer getDeepNumber() {
        return deepNumber;
    }

    public void setDeepNumber(Integer deepNumber) {
        this.deepNumber = deepNumber;
    }
}
