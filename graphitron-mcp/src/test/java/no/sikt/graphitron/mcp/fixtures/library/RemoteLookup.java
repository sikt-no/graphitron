package no.sikt.graphitron.mcp.fixtures.library;

/**
 * A class the classpath census reaches and no walked source root does, which is every class a
 * consumer gets from a dependency jar. It sits in a package of its own for exactly that reason: the
 * {@code code} fixtures walk one source root, this file is outside it, and the tool's answer for this
 * class is the un-indexed arm rather than a location.
 */
public class RemoteLookup {

    /** Reachable on the classpath; its source is not in any walked root. */
    public String lookup() {
        return "";
    }
}
