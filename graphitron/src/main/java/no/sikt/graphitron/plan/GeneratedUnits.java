package no.sikt.graphitron.plan;

import no.sikt.graphitron.command.UnitRef;

import java.nio.file.Path;

/**
 * The plan's naming vocabulary: single source of truth for the names of graphitron's generated
 * compilation units, given the consumer's {@code outputPackage}. Every naming scheme here mirrors
 * what the generators actually emit; the
 * {@link no.sikt.graphitron.rewrite.compile.TypeSpecReferenceWalk} completeness
 * oracle pins these against the real emitted file set so a scheme drift is a test failure, not a
 * silent graph gap.
 *
 * <p>The scheme methods mint {@link UnitRef}s, and are meant to be the only minting site (the
 * import-direction guard pins this), so a command naming a unit outside these schemes is
 * unrepresentable in practice rather than merely discouraged.
 *
 * <p>An FQCN maps deterministically to the {@code .java} path the writer lands it at
 * ({@link #toRelativeJavaPath}); the incremental compile engine uses that to bridge the writer's
 * {@code Set<Path>} delta and the compile graph's node space.
 */
public final class GeneratedUnits {

    // Sub-packages the generators emit into (mirrors GraphQLRewriteGenerator.OWNED_SUBPACKAGES).
    public static final String SUB_UTIL = "util";
    public static final String SUB_SCHEMA = "schema";
    public static final String SUB_TYPES = "types";
    public static final String SUB_CONDITIONS = "conditions";
    public static final String SUB_FETCHERS = "fetchers";
    public static final String SUB_INPUTS = "inputs";

    public static final String FETCHERS_SUFFIX = "Fetchers";
    public static final String CONDITIONS_SUFFIX = "Conditions";
    public static final String SCHEMA_SHAPE_SUFFIX = "Type";

    private final String outputPackage;

    public GeneratedUnits(String outputPackage) {
        this.outputPackage = outputPackage;
    }

    /** {@code <pkg>.fetchers.<Type>Fetchers} — the per-type data-fetcher class. */
    public UnitRef fetchers(String typeName) {
        return unit(SUB_FETCHERS, typeName + FETCHERS_SUFFIX);
    }

    /** {@code <pkg>.types.<Type>} — the per-type jOOQ projection class (TableType / NodeType only). */
    public UnitRef typeClass(String typeName) {
        return unit(SUB_TYPES, typeName);
    }

    /** {@code <pkg>.conditions.<Type>Conditions} — the per-parent generated-condition class. */
    public UnitRef conditions(String parentTypeName) {
        return unit(SUB_CONDITIONS, parentTypeName + CONDITIONS_SUFFIX);
    }

    /** {@code <pkg>.inputs.<Input>} — the per-input-type record class. */
    public UnitRef inputRecord(String inputTypeName) {
        return unit(SUB_INPUTS, inputTypeName);
    }

    /** {@code <pkg>.schema.<Name>Type} — the graphql-java schema-shape class (object/input/enum/etc). */
    public UnitRef schemaShape(String typeName) {
        return unit(SUB_SCHEMA, typeName + SCHEMA_SHAPE_SUFFIX);
    }

    /** A fixed-name singleton in the given sub-package (e.g. {@code util.NodeIdEncoder}). */
    public UnitRef singleton(String subPackage, String className) {
        return unit(subPackage, className);
    }

    /** A fixed-name unit in the root output package (the {@code Graphitron} facade). */
    public UnitRef rootUnit(String className) {
        return unit("", className);
    }

    private UnitRef unit(String subPackage, String className) {
        return new UnitRef(packageName(subPackage), className);
    }

    /** The package a sub-package's units land in; the empty sub-package is the root output package. */
    public String packageName(String subPackage) {
        return subPackage.isEmpty() ? outputPackage : outputPackage + "." + subPackage;
    }

    /** The FQCN a scheme produces, as a string; prefix probes pass an empty {@code className}. */
    public String fqcn(String subPackage, String className) {
        String pkg = packageName(subPackage);
        return pkg.isEmpty() ? className : pkg + "." + className;
    }

    /** Maps an FQCN to the {@code .java} path (relative to the generated-source root) it is written at. */
    public static Path toRelativeJavaPath(String fqcn) {
        String[] parts = fqcn.split("\\.");
        Path p = Path.of(parts[0] + (parts.length == 1 ? ".java" : ""));
        for (int i = 1; i < parts.length; i++) {
            p = p.resolve(parts[i] + (i == parts.length - 1 ? ".java" : ""));
        }
        return p;
    }
}
