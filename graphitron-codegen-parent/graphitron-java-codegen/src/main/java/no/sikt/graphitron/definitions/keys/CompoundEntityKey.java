package no.sikt.graphitron.definitions.keys;

import no.sikt.graphitron.definitions.interfaces.EntityKey;

import java.util.List;
import java.util.stream.Collectors;

public class CompoundEntityKey implements EntityKey {
    private final List<EntityKey> keys;

    public CompoundEntityKey(List<EntityKey> keys) {
        this.keys = keys;
    }

    @Override
    public List<String> getKeys() {  // A compound key can not contain another compound key directly, so all instances here will have exactly 1 key.
        return keys.stream().filter(it -> it.getNestedKeys().isEmpty()).map(it -> it.getKeys().get(0)).collect(Collectors.toList());
    }

    @Override
    public List<EntityKey> getNestedKeys() {  // A compound key can not contain another compound key directly, so only nested entity keys will be returned here.
        return keys.stream().filter(it -> !it.getNestedKeys().isEmpty()).collect(Collectors.toList());
    }
}
