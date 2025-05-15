package no.sikt.graphql.helpers.query;

public record AfterTokenWithTypeName(String typeName, Object[] fields) {
    public boolean matches(String otherTypeName) {
        return this.typeName().equals(otherTypeName);
    }
}
