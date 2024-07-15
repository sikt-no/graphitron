package no.fellesstudentsystem.graphitron_newtestorder.codereferences.records;

public class QueryCustomerJavaRecord {
    private String someID, name;
    private QueryCustomerJavaRecord inner;

    public String getSomeID() {
        return someID;
    }

    public String getName() {
        return name;
    }

    public void setSomeID(String someID) {
        this.someID = someID;
    }

    public void setName(String name) {
        this.name = name;
    }

    public QueryCustomerJavaRecord getInner() {
        return inner;
    }

    public void setInner(QueryCustomerJavaRecord inner) {
        this.inner = inner;
    }
}
