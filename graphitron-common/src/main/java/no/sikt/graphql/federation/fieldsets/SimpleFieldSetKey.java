package no.sikt.graphql.federation.fieldsets;

import no.sikt.graphql.federation.FieldSetKey;

import java.util.List;

public record SimpleFieldSetKey(String key) implements FieldSetKey {
    @Override
    public List<String> getKeys() {
        return List.of(key);
    }

    @Override
    public List<FieldSetKey> getNestedKeys() {
        return List.of();
    }
}
