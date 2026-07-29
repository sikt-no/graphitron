package no.sikt.graphitron.plan;

import no.sikt.graphitron.command.UnitMethodRef;
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

    /**
     * {@code <pkg>.types.<Anchor><Nested>} — a nesting type's projection unit under one
     * table-backed anchor. The anchor prefix is what disambiguates one nesting type shared
     * across anchors with different tables, and the anchor (not the immediate parent) because
     * nesting is a pass-through: every nesting descendant reads the anchor's row, and at depth
     * two and beyond only the anchor disambiguates. The concatenated name can collide with an
     * authored type's unit or another prefixed pair's; the plan rejects any duplicate
     * projection-unit address at produce time (see the projection producer's address census).
     */
    public UnitRef nestingUnit(String anchorTypeName, String nestedTypeName) {
        return unit(SUB_TYPES, anchorTypeName + nestedTypeName);
    }

    /**
     * {@code <pkg>.types.<Parent><FieldName>} — a {@code @pivot} coordinate's projection unit,
     * field name upper-camelled. Keyed by coordinate rather than by projection type because the
     * pivot spec is coordinate-grain (two coordinates can pivot into the same projection type
     * over different attribute tables); subject to the same duplicate-address rejection as
     * {@link #nestingUnit}.
     */
    public UnitRef pivotUnit(String parentTypeName, String fieldName) {
        return unit(SUB_TYPES, parentTypeName + upperCamel(fieldName));
    }

    private static String upperCamel(String fieldName) {
        return Character.toUpperCase(fieldName.charAt(0)) + fieldName.substring(1);
    }

    /** {@code <pkg>.conditions.<Type>Conditions} — the per-parent generated-condition class. */
    public UnitRef conditions(String parentTypeName) {
        return unit(SUB_CONDITIONS, parentTypeName + CONDITIONS_SUFFIX);
    }

    /**
     * {@code <Parent>Conditions#<field>Condition}: a coordinate's glue method, the fold every
     * WHERE consumer calls. One formula for both ends: the producer mints the ref onto the
     * condition row, and the not-yet-migrated call sites derive the same ref here instead of
     * recomputing the class or method name locally.
     */
    public UnitMethodRef conditionMethod(String parentTypeName, String fieldName) {
        return new UnitMethodRef(conditions(parentTypeName), fieldName + "Condition");
    }

    /** {@code <field>FacetBaseCondition}: the faceted carrier's filter-minus-every-facet fragment. */
    public UnitMethodRef facetBaseConditionMethod(String parentTypeName, String fieldName) {
        return new UnitMethodRef(conditions(parentTypeName), fieldName + "FacetBaseCondition");
    }

    /** {@code <field>Facet_<inputField>Condition}: one facet's own predicate alone. */
    public UnitMethodRef facetConditionMethod(String parentTypeName, String fieldName, String facetInputFieldName) {
        return new UnitMethodRef(conditions(parentTypeName), fieldName + "Facet_" + facetInputFieldName + "Condition");
    }

    /**
     * {@code <field>Participant_<Type>Condition}: a polymorphic root coordinate's per-participant
     * glue method; participant rows on one coordinate disambiguate by minted name, mirroring the
     * facet-fragment scheme.
     */
    public UnitMethodRef participantConditionMethod(String parentTypeName, String fieldName, String participantTypeName) {
        return new UnitMethodRef(conditions(parentTypeName), fieldName + "Participant_" + participantTypeName + "Condition");
    }

    /**
     * {@code <Parent>Fetchers#rows<Field>}: a root coordinate's launcher method, the named unit
     * owning the coordinate's whole query composition (the root {@code rows<X>}-equivalent).
     * Hosted on the coordinate's fetchers class; the thin fetcher entry point calls it with the
     * resolved {@code DSLContext}. One formula for both ends: the launcher producer mints the
     * ref onto the row, and the fetcher generator reads the same ref off the relation.
     */
    public UnitMethodRef launcherMethod(String parentTypeName, String fieldName) {
        return new UnitMethodRef(fetchers(parentTypeName), "rows" + upperCamel(fieldName));
    }

    /**
     * {@code <Parent>Fetchers#rows<Field>}: a batched child coordinate's launcher unit, the
     * DataLoader-backed rows method taking the batch keys. Same formula as the root launcher's
     * scheme and deliberately a separate method: the two populations join the relation from
     * different families, and the day one of them needs a different formula the fork happens
     * here, in the one minting locus.
     */
    public UnitMethodRef rowsMethod(String parentTypeName, String fieldName) {
        return new UnitMethodRef(fetchers(parentTypeName), "rows" + upperCamel(fieldName));
    }

    /**
     * {@code <Parent>Fetchers#lookup<Field>}: a keyed-lookup coordinate's launcher unit. The
     * emitted name predates the seam (the lookup root was the one root path that already
     * delegated to a named unit) and is kept as signed off on the launcher item, so the
     * launcher-name formula forks on the row's kind here, in the one minting locus; every
     * consumer reads the ref off the row, never a formula.
     */
    public UnitMethodRef lookupMethod(String parentTypeName, String fieldName) {
        return new UnitMethodRef(fetchers(parentTypeName), "lookup" + upperCamel(fieldName));
    }

    /**
     * {@code <Parent>Fetchers#<field>OrderBy}: a coordinate's emitted ordering helper, present
     * exactly when the ordering rides a runtime {@code @orderBy} argument. Minted onto the
     * launcher row's {@code Ordering.Helper} arm; the helper itself is emitted by the fetcher
     * generator with the same formula.
     */
    public UnitMethodRef orderByHelperMethod(String parentTypeName, String fieldName) {
        return new UnitMethodRef(fetchers(parentTypeName), fieldName + "OrderBy");
    }

    /**
     * {@code <owner>#<field>InputRows}: a lookup coordinate's generated VALUES-rows helper,
     * hosted on the projection unit whose {@code $project} arm consumes it. One formula for both
     * ends: the projection producer mints the ref onto the lookup wrap, and the legacy hosts'
     * emitter ({@code LookupValuesJoinEmitter.inputRowsMethodName}) spells the same name.
     */
    public UnitMethodRef inputRowsMethod(UnitRef owner, String fieldName) {
        return new UnitMethodRef(owner, fieldName + "InputRows");
    }

    /** {@code <pkg>.inputs.<Input>} — the per-input-type record class. */
    public UnitRef inputRecord(String inputTypeName) {
        return unit(SUB_INPUTS, inputTypeName);
    }

    /** {@code <pkg>.schema.<Name>Type} — the graphql-java schema-shape class (object/input/enum/etc). */
    public UnitRef schemaShape(String typeName) {
        return unit(SUB_SCHEMA, typeName + SCHEMA_SHAPE_SUFFIX);
    }

    /** {@code <pkg>.util.ConnectionHelper} — the generated pagination runtime. */
    public UnitRef connectionHelper() {
        return unit(SUB_UTIL, "ConnectionHelper");
    }

    /** {@code <pkg>.util.ConnectionResult} — the generated connection carrier. */
    public UnitRef connectionResult() {
        return unit(SUB_UTIL, "ConnectionResult");
    }

    /** {@code <pkg>.util.OrderByResult} — the ordering helpers' two-view result carrier. */
    public UnitRef orderByResult() {
        return unit(SUB_UTIL, "OrderByResult");
    }

    /** {@code <pkg>.schema.TenantConnections} — the multi-tenant scatter/acquisition carrier. */
    public UnitRef tenantConnections() {
        return unit(SUB_SCHEMA, "TenantConnections");
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
