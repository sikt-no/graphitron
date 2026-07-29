package no.sikt.graphitron.rewrite.generators;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.render.LookupRows;
import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.LookupField;
import no.sikt.graphitron.rewrite.model.LookupMapping;
import no.sikt.graphitron.rewrite.model.LookupMapping.ColumnMapping;
import no.sikt.graphitron.rewrite.model.QueryField;

import static no.sikt.graphitron.rewrite.generators.GeneratorUtils.DSL;

/**
 * Emits the VALUES + JOIN lookup select for a root {@link LookupField}
 * ({@link QueryField.QueryLookupTableField} and the batched child shapes), driven by its
 * {@link LookupMapping}. Row construction lives in {@link LookupRows} (the projection renderer
 * consumes the same core for inline child lookups), so both migration sides keep one
 * derivation; this class carries the leaf-taking entry points and the root fetcher body.
 *
 * <p>The root fetcher joins with {@code USING}, which is safe because the root lookup's
 * {@code FROM} side is the target table only; no FK chain can bring in a duplicate column name.
 * The inline child path renders an explicit {@code ON} predicate instead, because its FK chain
 * may traverse a junction table whose column name collides with the lookup-key target column.
 *
 * <p>See {@code docs/architecture/reference/argument-resolution.adoc} for the design rationale
 * across both paths. Emitted {@code <fieldName>InputRows} helpers take the aliased target
 * {@code Table} as a parameter; see "Helper-locality" in
 * {@code docs/architecture/reference/emitter-conventions.adoc}.
 */
final class LookupValuesJoinEmitter {

    /** Directive context surfaced in arity-cap error messages. */
    private static final String DIRECTIVE_CONTEXT = "@lookupKey";

    private LookupValuesJoinEmitter() {}

    /** Returns the GraphQL field name for a {@link LookupField}, used to derive helper names. */
    static String fieldName(LookupField field) {
        return switch (field) {
            case QueryField.QueryLookupTableField f -> f.name();
            case ChildField.LookupTableField f -> f.name();
            case ChildField.BatchedLookupTableField f -> f.name();
        };
    }

    /**
     * The name of the generated input-rows helper method for a lookup field. Spells the same
     * name as the plan's {@code GeneratedUnits.inputRowsMethod} scheme, so the migrated and
     * unmigrated hosts derive one method name.
     */
    static String inputRowsMethodName(LookupField field) {
        return fieldName(field) + "InputRows";
    }

    /**
     * Generates the {@code <fieldName>InputRows(DataFetchingEnvironment env, <TargetTable> table)}
     * helper for the batched child paths; see {@link LookupRows#buildInputRowsMethod}. The helper
     * name derives from the leaf while the child family's migration window is open.
     */
    static MethodSpec buildInputRowsMethod(LookupField field, ClassName targetTableClass) {
        return buildInputRowsMethod(field, targetTableClass, inputRowsMethodName(field));
    }

    /**
     * The migrated root's variant: the helper name arrives from the launcher row's minted ref
     * ({@code GeneratedUnits.inputRowsMethod}) rather than the leaf formula, so the emitted
     * helper and the launcher body that calls it read one name.
     */
    static MethodSpec buildInputRowsMethod(LookupField field, ClassName targetTableClass, String methodName) {
        return LookupRows.buildInputRowsMethod((ColumnMapping) field.lookupMapping(),
            methodName, targetTableClass, LookupRows.ArgSource.ENV, fieldName(field));
    }
}
