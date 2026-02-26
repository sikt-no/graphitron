package no.sikt.graphql.federation;

import java.util.List;

public interface FieldSetKey {
    List<String> getKeys();
    List<FieldSetKey> getNestedKeys();
}
