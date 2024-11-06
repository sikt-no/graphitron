package no.sikt.graphitron.definitions.keys;

import no.sikt.graphitron.definitions.interfaces.EntityKey;

import java.util.List;

public class SimpleEntityKey implements EntityKey {
    private final String key;

    public SimpleEntityKey(String key) {
        this.key = key;
    }

    public String getKey() {
        return key;
    }

    @Override
    public List<String> getKeys() {
        return List.of(key);
    }

    @Override
    public List<EntityKey> getNestedKeys() {
        return List.of();
    }
}
