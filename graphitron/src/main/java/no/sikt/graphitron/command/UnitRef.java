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

    /**
     * {@link #simpleName()} case-folded: what a case-insensitive filesystem (APFS, NTFS) collapses
     * <em>within one package directory</em>, since a unit's simple name is the stem of the file it
     * is written to. The grain for a census that has no output package to key an address on, which
     * is the position a schema-only validator mirror is in. Package-independent, so it is
     * well-defined on a ref whose package is a placeholder.
     *
     * <p>The filesystem rationale is the same one
     * {@link no.sikt.graphitron.model.diagnostics.Rejection.InvalidSchema.CaseFoldCollision} carries for
     * authored type names. It is the only rationale that licenses folding a generated name: a fold
     * is honest exactly where a Java identifier becomes a path segment. Minted <em>method</em> names
     * never cross that boundary, so there is no accessor here for them and none should be added.
     *
     * @see #foldedAddress()
     */
    public String foldedStem() {
        return simpleName.toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * {@link #fqcn()} case-folded: the whole address as a case-insensitive filesystem sees it, every
     * segment folded. The grain for a census keyed on real, fully-packaged addresses, where two
     * units sharing a simple name in different packages genuinely do not collide.
     *
     * <p>Equal to the folded {@link #packageName()} joined to {@link #foldedStem()}, which is the one
     * place the two grains are tied together; the rationale for folding at all is on
     * {@link #foldedStem()}. This closes the set: two grains are the honest count of the boundaries a
     * generated name crosses, so do not grow a third.
     *
     * @see #foldedStem()
     */
    public String foldedAddress() {
        return fqcn().toLowerCase(java.util.Locale.ROOT);
    }
}
