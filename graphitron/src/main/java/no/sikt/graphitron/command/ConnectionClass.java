package no.sikt.graphitron.command;

import java.util.Objects;

/**
 * The generated connection class a connection producer builds: the connection type's name, which
 * names its class in the generated {@code Connections} unit, and the two closures that class's
 * constructor takes beyond the page, the count where the type declares {@code totalCount} and
 * the facets where it carries facets. A producer reads this to build the class, so the
 * constructor it calls and the one rendered agree.
 */
public record ConnectionClass(String typeName, boolean counts, boolean faceted) {
    public ConnectionClass {
        Objects.requireNonNull(typeName, "typeName");
    }
}
