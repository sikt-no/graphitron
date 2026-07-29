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

    /** The VALUES-table alias used in the fetcher body. */
    static String inputTableAlias(LookupField field) {
        return fieldName(field) + "Input";
    }

    /**
     * Generates the {@code <fieldName>InputRows(DataFetchingEnvironment env, <TargetTable> table)}
     * helper for the root / batched paths; see {@link LookupRows#buildInputRowsMethod}.
     */
    static MethodSpec buildInputRowsMethod(LookupField field, ClassName targetTableClass) {
        return LookupRows.buildInputRowsMethod((ColumnMapping) field.lookupMapping(),
            inputRowsMethodName(field), targetTableClass, LookupRows.ArgSource.ENV, fieldName(field));
    }

    /**
     * Generates the VALUES + JOIN derived-table select body for a lookup field's rows method:
     * input-rows helper call, {@code dsl} declaration (via {@link TenantDslEmitter}), empty-input
     * short-circuit, {@code DSL.values(rows)} derived table joined with {@code USING}, and
     * {@code .orderBy(input.field("idx"))} to preserve input ordering.
     *
     * <p>Expects two locals already declared in the surrounding method: the target-table alias
     * named by {@code srcAlias} (from {@link GeneratorUtils#declareTableLocal}) and a
     * {@code Condition condition}. Callers typically initialise {@code condition} with
     * {@code DSL.noCondition()} and AND in any non-key filters; with no such filters the
     * {@code .where(noCondition())} is a no-op that jOOQ optimises away.
     *
     * @param field          the lookup field
     * @param typeFieldsCall the JavaPoet expression for the projection call feeding the SELECT list
     */
    static CodeBlock buildFetcherBody(TypeFetcherEmissionContext ctx, LookupField field, CodeBlock typeFieldsCall,
            String srcAlias, String outputPackage) {
        ColumnMapping cm = (ColumnMapping) field.lookupMapping();
        String alias = inputTableAlias(field);
        String name = fieldName(field);

        // VALUES column labels: "idx", then one per lookup slot. Labels must match the target
        // column's SQL name (e.g. "film_id"), not the jOOQ Java field name (e.g. "FILM_ID"), because
        // Postgres treats quoted identifiers case-sensitively and USING compares the rendered names.
        CodeBlock aliasArgs = LookupRows.aliasArgs(cm, alias, name);
        CodeBlock usingArgs = LookupRows.usingArgs(cm, srcAlias, name);

        // Every LookupField permit is an OutputField; the instanceof keeps the seam total if a
        // non-field permit ever appears (it would fall back to the escape-hatch read).
        CodeBlock dslDeclaration = field instanceof no.sikt.graphitron.rewrite.model.OutputField of
            ? TenantDslEmitter.resolve(ctx, of, outputPackage).declaration()
            : TenantDslEmitter.singleTenantDeclaration(ctx);

        return CodeBlock.builder()
            .addStatement("$T rows = $L(env, $L)",
                LookupRows.rowArrayType(cm, name), inputRowsMethodName(field), srcAlias)
            .add(dslDeclaration)
            .add("if (rows.length == 0) return dsl.newResult();\n")
            .addStatement("$T input = $T.values(rows).as($L)",
                LookupRows.inputTableType(cm, name), DSL, aliasArgs)
            .add("return dsl\n")
            .indent()
            .add(".select($L)\n", typeFieldsCall)
            .add(".from($L)\n", srcAlias)
            .add(".join(input).using($L)\n", usingArgs)
            .add(".where(condition)\n")
            .add(".orderBy(input.field($S))\n", "idx")
            .add(".fetch();\n")
            .unindent()
            .build();
    }
}
