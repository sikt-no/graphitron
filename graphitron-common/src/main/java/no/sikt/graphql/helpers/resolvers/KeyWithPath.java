package no.sikt.graphql.helpers.resolvers;

public class KeyWithPath<K> {
    private final K key;
    private final String path;

    public KeyWithPath(K key, String path) {
        this.key = key;
        this.path = path;
    }

    public K getKey() {
        return key;
    }

    public String getPath() {
        return path;
    }

    @Override
    public String toString() {
        return path + "||" + key.toString();
    }
}
