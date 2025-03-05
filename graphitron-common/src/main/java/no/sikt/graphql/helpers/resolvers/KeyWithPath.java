package no.sikt.graphql.helpers.resolvers;

public record KeyWithPath<K>(K key, String path) {
    @Override
    public String toString() {
        return path + "||" + key.toString();
    }
}
