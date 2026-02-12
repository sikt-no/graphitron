package no.sikt.graphql.federation.fieldsets;

import no.sikt.graphql.federation.FieldSetKey;

import java.util.ArrayList;
import java.util.List;

/**
 * The top level of entity keys for a type. One entry here corresponds to one key directive in the schema.
 */
public record FederationFieldSet(List<FieldSetKey> keys) {
    public static FederationFieldSet fromString(List<String> rawKeys) {
        return new FederationFieldSet(rawKeys.stream().map(FederationFieldSet::parseKeys).toList());
    }

    private static FieldSetKey parseKeys(String rawKey) {
        var compound = new ArrayList<FieldSetKey>();
        var elementList = new ArrayList<>(List.of(rawKey.split("\\s+")));
        var nestingCount = 0;
        var nestedContent = new ArrayList<String>();
        for (var element : elementList) {
            if (element.equals("{")) {  // Nesting begins.
                nestingCount++;
            } else if (element.equals("}")) {
                nestingCount--;
                if (nestingCount == 0) {  // Reached the end of the outermost nesting.
                    var source = (SimpleFieldSetKey) compound.remove(compound.size() - 1);
                    compound.add(new NestedFieldSetKey(parseKeys(String.join(" ", nestedContent)), source.key()));
                    nestedContent.clear();
                }
            } else if (nestingCount == 0) {  // Key on this level is found.
                var key = new SimpleFieldSetKey(element);
                compound.add(key);
            } else {  // Delegate nested elements to the next recursion level.
                nestedContent.add(element);
            }
        }

        return new CompoundFieldSetKey(compound);
    }
}
