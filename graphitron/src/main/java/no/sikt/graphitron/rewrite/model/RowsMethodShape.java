package no.sikt.graphitron.rewrite.model;

import com.palantir.javapoet.ClassName;

/**
 * The binary-name split {@link DomainReturnType#claimForBacking} mints its backing-class claim
 * through ({@link #fromBinaryName}).
 */
public final class RowsMethodShape {

    private RowsMethodShape() {}

    /**
     * A {@link ClassName} for a binary class name, splitting nested classes on {@code $} so a nested
     * backing class resolves to the JLS-legal {@code Outer.Nested} rather than to one simple name that
     * contains a {@code $}. {@code ClassName.bestGuess} splits only on {@code .}, so it keeps the
     * binary form, which javac rejects wherever the name is written into generated source.
     *
     * <p>Package-private rather than private so {@link DomainReturnType#claimForBacking} mints its
     * backing-class claim through this same splitting: a second spelling of it would put two
     * producers of one nested class on two unequal {@link ClassName}s.
     */
    static ClassName fromBinaryName(String binaryName) {
        int lastDot = binaryName.lastIndexOf('.');
        String packageName = lastDot < 0 ? "" : binaryName.substring(0, lastDot);
        String[] names = binaryName.substring(lastDot + 1).split("\\$");
        return ClassName.get(packageName, names[0],
            java.util.Arrays.copyOfRange(names, 1, names.length));
    }
}
