package no.sikt.graphql.federation.fieldsets;

import no.sikt.graphql.federation.FieldSetKey;

import java.util.List;

public record NestedFieldSetKey(FieldSetKey key, String source) implements FieldSetKey {
    @Override
    public List<String> getKeys() {
        return List.of(source);
    }

    @Override
    public List<FieldSetKey> getNestedKeys() {
        return List.of(key);
    }
}
