package no.sikt.graphitron.rewrite.generators;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.render.LookupRows;
import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.LookupField;
import no.sikt.graphitron.rewrite.model.LookupMapping;
import no.sikt.graphitron.rewrite.model.LookupMapping.ColumnMapping;
import no.sikt.graphitron.rewrite.model.QueryField;

/**
 * Emits the {@code <fieldName>InputRows} helper for a {@link LookupField}
 * ({@link QueryField.QueryLookupTableField} and the batched child shape), driven by its
 * {@link LookupMapping}. Row construction lives in {@link LookupRows} (the projection renderer
 * consumes the same core for inline child lookups), so all hosts keep one derivation; this
 * class carries only the leaf-taking entry point.
 *
 * <p>See {@code docs/architecture/reference/argument-resolution.adoc} for the design rationale
 * across the lookup paths. Emitted helpers take the aliased target {@code Table} as a
 * parameter; see "Helper-locality" in
 * {@code docs/architecture/reference/emitter-conventions.adoc}.
 */
final class LookupValuesJoinEmitter {

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
     * Generates the {@code <fieldName>InputRows(DataFetchingEnvironment env, <TargetTable> table)}
     * helper. The helper name arrives from the launcher row's minted ref
     * ({@code GeneratedUnits.inputRowsMethod}) rather than a leaf formula, so the emitted
     * helper and the launcher body that calls it read one name.
     */
    static MethodSpec buildInputRowsMethod(LookupField field, ClassName targetTableClass, String methodName) {
        return LookupRows.buildInputRowsMethod((ColumnMapping) field.lookupMapping(),
            methodName, targetTableClass, LookupRows.ArgSource.ENV, fieldName(field));
    }
}
