package no.sikt.graphitron.command;

/**
 * A reference to one generated compilation unit: the package the writer lands it in and its
 * simple class name. Commands name the units they commit with these, and the write step takes
 * the reference as its landing address, so there is exactly one derivation of where a unit
 * lives. Refs are minted by the plan's naming vocabulary ({@code GeneratedUnits}), never parsed
 * back out of a string; the import-direction guard pins the minting site.
 */
public record UnitRef(String packageName, String simpleName) {

    public UnitRef {
        if (packageName == null) {
            throw new IllegalArgumentException("a unit reference requires a package name; the root package is the empty string");
        }
        if (simpleName == null || simpleName.isBlank()) {
            throw new IllegalArgumentException("a unit reference requires a non-blank simple class name");
        }
    }

    /** The fully-qualified class name, derived; the components are the source of truth. */
    public String fqcn() {
        return packageName.isEmpty() ? simpleName : packageName + "." + simpleName;
    }
}
