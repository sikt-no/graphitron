package no.sikt.graphql.federation.fieldsets;

import no.sikt.graphql.federation.FieldSetKey;

import java.util.List;
import java.util.stream.Collectors;

public record CompoundFieldSetKey(List<FieldSetKey> keys) implements FieldSetKey {
    @Override
    public List<String> getKeys() {  // A compound key can not contain another compound key directly, so all instances here will have exactly 1 key.
        return keys.stream().filter(it -> it.getNestedKeys().isEmpty()).map(it -> it.getKeys().get(0)).collect(Collectors.toList());
    }

    @Override
    public List<FieldSetKey> getNestedKeys() {  // A compound key can not contain another compound key directly, so only nested entity keys will be returned here.
        return keys.stream().filter(it -> !it.getNestedKeys().isEmpty()).collect(Collectors.toList());
    }
}
