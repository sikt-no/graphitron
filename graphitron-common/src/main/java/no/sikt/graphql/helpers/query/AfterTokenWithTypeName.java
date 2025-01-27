package no.sikt.graphql.helpers.query;

public class AfterTokenWithTypeName {

    private final String typeName;
    private final Object[] fields;

    public AfterTokenWithTypeName(String typeName, Object[] fields) {
        this.typeName = typeName;
        this.fields = fields;
    }

    public String getTypeName() {
        return typeName;
    }

    public Object[] getFields() {
        return fields;
    }

    public boolean matches(String otherTypeName) {
        return this.getTypeName().equals(otherTypeName);
    }

}