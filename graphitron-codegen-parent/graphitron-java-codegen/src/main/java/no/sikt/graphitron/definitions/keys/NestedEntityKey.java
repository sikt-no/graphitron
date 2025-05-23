package no.sikt.graphitron.definitions.keys;

import no.sikt.graphitron.definitions.interfaces.EntityKey;

import java.util.List;

public record NestedEntityKey(EntityKey key, String source) implements EntityKey {
    @Override
    public List<String> getKeys() {
        return List.of(source);
    }

    @Override
    public List<EntityKey> getNestedKeys() {
        return List.of(key);
    }
}
