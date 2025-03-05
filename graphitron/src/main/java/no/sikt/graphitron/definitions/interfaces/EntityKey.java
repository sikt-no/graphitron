package no.sikt.graphitron.definitions.interfaces;

import java.util.List;

public interface EntityKey {
    List<String> getKeys();
    List<EntityKey> getNestedKeys();
}
