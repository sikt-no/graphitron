package no.sikt.graphitron.rewrite.model;

import java.util.List;

/**
 * A WHERE-clause contribution backed by a Graphitron-generated condition method.
 *
 * <p>Implements {@link WhereFilter} so the fetcher generator can call the method without
 * knowing how it was produced. Additionally carries {@link #bodyParams()} consumed exclusively
 * by {@link no.sikt.graphitron.rewrite.generators.TypeConditionsGenerator} to emit the method
 * signature and body.
 *
 * <p>The builder creates one {@code GeneratedConditionFilter} per SQL-generating field that has
 * at least one filterable argument (scalar, enum, or input). All filterable arguments for a field
 * are grouped into a single generated condition method; each argument becomes one
 * {@link BodyParam} entry. Validation of column resolution, enum value matching, and input type
 * expansion happens in the builder before this record is constructed.
 *
 * <p>{@link #tableRef()} provides the jOOQ table needed for the condition method's first
 * parameter ({@code <TableClass> table}). It is the resolved table of the field's return type.
 *
 * <p>Naming convention: {@link #className()} is {@code <ReturnTypeName>Conditions} and
 * {@link #methodName()} is {@code <fieldName>Condition}, both computed by the builder.
 */
public record GeneratedConditionFilter(
    String className,
    String methodName,
    TableRef tableRef,
    List<CallParam> callParams,
    List<BodyParam> bodyParams
) implements WhereFilter {}
