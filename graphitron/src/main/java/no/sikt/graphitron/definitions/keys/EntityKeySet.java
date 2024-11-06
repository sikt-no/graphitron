package no.sikt.graphitron.definitions.keys;

import no.sikt.graphitron.definitions.interfaces.EntityKey;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * The top level of entity keys for a type. One entry here corresponds to one key directive in the schema.
 */
public class EntityKeySet {
    private final List<EntityKey> keys;

    public EntityKeySet(List<String> rawKeys) {
        this.keys = rawKeys.stream().map(EntityKeySet::parseKeys).collect(Collectors.toList());
    }

    public List<EntityKey> getKeys() {
        return keys;
    }

    private static EntityKey parseKeys(String rawKey) {
        var compound = new ArrayList<EntityKey>();
        var elementList = new ArrayList<>(List.of(rawKey.split("\\s+")));
        var nestingCount = 0;
        var nestedContent = new ArrayList<String>();
        for (var element: elementList) {
            if (element.equals("{")) {  // Nesting begins.
                nestingCount++;
            } else if (element.equals("}")) {
                nestingCount--;
                if (nestingCount == 0) {  // Reached the end of the outermost nesting.
                    var source = (SimpleEntityKey) compound.remove(compound.size() - 1);
                    compound.add(new NestedEntityKey(parseKeys(String.join(" ", nestedContent)), source.getKey()));
                    nestedContent.clear();
                }
            } else if (nestingCount == 0) {  // Key on this level is found.
                var key = new SimpleEntityKey(element);
                compound.add(key);
            } else {  // Delegate nested elements to the next recursion level.
                nestedContent.add(element);
            }
        }

        return new CompoundEntityKey(compound);
    }
}
