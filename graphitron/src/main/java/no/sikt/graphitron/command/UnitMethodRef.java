package no.sikt.graphitron.command;

/**
 * A reference to one method on a generated compilation unit: the owning {@link UnitRef} plus the
 * method's simple name. Commands that commit method-grained units (a condition row's glue method,
 * a facet fragment) carry these, and call-site emitters read the same ref, so the class-name and
 * method-name formulas have exactly one derivation. Like {@link UnitRef}, refs are minted by the
 * plan's naming vocabulary ({@code GeneratedUnits}) and never recomputed from a field name at an
 * emit site; the import-direction guard pins the minting site.
 */
public record UnitMethodRef(UnitRef owner, String methodName) {

    public UnitMethodRef {
        if (owner == null) {
            throw new IllegalArgumentException("a unit method reference requires an owning unit");
        }
        if (methodName == null || methodName.isBlank()) {
            throw new IllegalArgumentException("a unit method reference requires a non-blank method name");
        }
    }
}
