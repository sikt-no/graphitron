package no.sikt.graphitron.rewrite.compile;

import no.sikt.graphitron.plan.GeneratedUnits;

/**
 * Legacy String view of the plan's naming vocabulary ({@link GeneratedUnits}), for the compile
 * graph whose node space is FQCN strings. Pure delegation, so the two views cannot drift; this
 * adapter retires together with {@link CompileDependencyGraphBuilder} when the recompile graph
 * becomes a projection over the command relation.
 */
final class UnitNames {

    private final GeneratedUnits units;

    UnitNames(String outputPackage) {
        this.units = new GeneratedUnits(outputPackage);
    }

    /** {@code <pkg>.fetchers.<Type>Fetchers}: the per-type data-fetcher class. */
    String fetchers(String typeName) {
        return units.fetchers(typeName).fqcn();
    }

    /** {@code <pkg>.types.<Type>}: the per-type jOOQ projection class (TableType / NodeType only). */
    String typeClass(String typeName) {
        return units.typeClass(typeName).fqcn();
    }

    /** {@code <pkg>.types.<Anchor><Nested>}: a nesting type's projection unit under one anchor. */
    String nestingUnit(String anchorTypeName, String nestedTypeName) {
        return units.nestingUnit(anchorTypeName, nestedTypeName).fqcn();
    }

    /** {@code <pkg>.types.<Parent><Field>}: a {@code @pivot} coordinate's projection unit. */
    String pivotUnit(String parentTypeName, String fieldName) {
        return units.pivotUnit(parentTypeName, fieldName).fqcn();
    }

    /** {@code <pkg>.conditions.<Type>Conditions}: the per-parent generated-condition class. */
    String conditions(String parentTypeName) {
        return units.conditions(parentTypeName).fqcn();
    }

    /** {@code <pkg>.inputs.<Input>}: the per-input-type record class. */
    String inputRecord(String inputTypeName) {
        return units.inputRecord(inputTypeName).fqcn();
    }

    /** {@code <pkg>.schema.<Name>Type}: the graphql-java schema-shape class (object/input/enum/etc). */
    String schemaShape(String typeName) {
        return units.schemaShape(typeName).fqcn();
    }

    /** A fixed-name singleton in the given sub-package (e.g. {@code util.NodeIdEncoder}). */
    String singleton(String subPackage, String className) {
        return units.singleton(subPackage, className).fqcn();
    }

    /** A fixed-name unit in the root output package (the {@code Graphitron} facade). */
    String rootUnit(String className) {
        return units.rootUnit(className).fqcn();
    }

    /** The FQCN a scheme produces; prefix probes pass an empty {@code className}. */
    String fqcn(String subPackage, String className) {
        return units.fqcn(subPackage, className);
    }
}
