package no.sikt.graphitron.definitions.keys;

import no.sikt.graphitron.definitions.interfaces.EntityKey;

import java.util.List;

public record SimpleEntityKey(String key) implements EntityKey {
    @Override
    public List<String> getKeys() {
        return List.of(key);
    }

    @Override
    public List<EntityKey> getNestedKeys() {
        return List.of();
    }
}
