package no.sikt.graphitron.rewrite.compile;

import java.nio.file.Path;

/**
 * Single source of truth for the fully-qualified names of graphitron's generated compilation units,
 * given the consumer's {@code outputPackage}. Every naming scheme here mirrors what the generators
 * actually emit (the sub-package + class-name suffix each {@code write(...)} call in
 * {@code GraphQLRewriteGenerator.runPipeline} uses); the {@link TypeSpecReferenceWalk} completeness
 * oracle pins these against the real emitted file set so a scheme drift is a test failure, not a
 * silent graph gap.
 *
 * <p>An FQCN maps deterministically to the {@code .java} path the writer lands it at
 * ({@link #toRelativeJavaPath}); the incremental engine (slice 4) uses that to bridge the writer's
 * {@code Set<Path>} delta and this graph's node space.
 */
final class GeneratedUnits {

    // Sub-packages the generators emit into (mirrors GraphQLRewriteGenerator.OWNED_SUBPACKAGES).
    static final String SUB_UTIL = "util";
    static final String SUB_SCHEMA = "schema";
    static final String SUB_TYPES = "types";
    static final String SUB_CONDITIONS = "conditions";
    static final String SUB_FETCHERS = "fetchers";
    static final String SUB_INPUTS = "inputs";

    static final String FETCHERS_SUFFIX = "Fetchers";
    static final String CONDITIONS_SUFFIX = "Conditions";
    static final String SCHEMA_SHAPE_SUFFIX = "Type";

    private final String outputPackage;

    GeneratedUnits(String outputPackage) {
        this.outputPackage = outputPackage;
    }

    /** {@code <pkg>.fetchers.<Type>Fetchers} — the per-type data-fetcher class. */
    String fetchers(String typeName) {
        return fqcn(SUB_FETCHERS, typeName + FETCHERS_SUFFIX);
    }

    /** {@code <pkg>.types.<Type>} — the per-type jOOQ projection class (TableType / NodeType only). */
    String typeClass(String typeName) {
        return fqcn(SUB_TYPES, typeName);
    }

    /** {@code <pkg>.conditions.<Type>Conditions} — the per-parent generated-condition class. */
    String conditions(String parentTypeName) {
        return fqcn(SUB_CONDITIONS, parentTypeName + CONDITIONS_SUFFIX);
    }

    /** {@code <pkg>.inputs.<Input>} — the per-input-type record class. */
    String inputRecord(String inputTypeName) {
        return fqcn(SUB_INPUTS, inputTypeName);
    }

    /** {@code <pkg>.schema.<Name>Type} — the graphql-java schema-shape class (object/input/enum/etc). */
    String schemaShape(String typeName) {
        return fqcn(SUB_SCHEMA, typeName + SCHEMA_SHAPE_SUFFIX);
    }

    /** A fixed-name singleton in the given sub-package (e.g. {@code util.NodeIdEncoder}). */
    String singleton(String subPackage, String className) {
        return fqcn(subPackage, className);
    }

    /** A fixed-name unit in the root output package (the {@code Graphitron} facade). */
    String rootUnit(String className) {
        return fqcn("", className);
    }

    String fqcn(String subPackage, String className) {
        String pkg = subPackage.isEmpty() ? outputPackage : outputPackage + "." + subPackage;
        return pkg.isEmpty() ? className : pkg + "." + className;
    }

    /** Maps an FQCN to the {@code .java} path (relative to the generated-source root) it is written at. */
    static Path toRelativeJavaPath(String fqcn) {
        String[] parts = fqcn.split("\\.");
        Path p = Path.of(parts[0] + (parts.length == 1 ? ".java" : ""));
        for (int i = 1; i < parts.length; i++) {
            p = p.resolve(parts[i] + (i == parts.length - 1 ? ".java" : ""));
        }
        return p;
    }
}
