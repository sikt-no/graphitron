package no.sikt.graphitron.definitions.keys;

import no.sikt.graphitron.definitions.interfaces.EntityKey;

import java.util.List;

public class NestedEntityKey implements EntityKey {
    private final EntityKey key;
    private final String source;

    public NestedEntityKey(EntityKey key, String source) {
        this.key = key;
        this.source = source;
    }

    public EntityKey getKey() {
        return key;
    }

    public String getSource() {
        return source;
    }

    @Override
    public List<String> getKeys() {
        return List.of(source);
    }

    @Override
    public List<EntityKey> getNestedKeys() {
        return List.of(key);
    }
}
