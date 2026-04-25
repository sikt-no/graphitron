package no.sikt.graphitron.rewrite.model;

/**
 * Maps one field on a GraphQL input type to a target jOOQ column.
 *
 * <p>Used by the argument-resolution pipeline when an input type is annotated with {@code @table}
 * and its fields need to resolve to columns on that table — for example, the composite-key
 * lookup scenario where an input record carries several key columns passed together.
 *
 * <p>Canonical shape shared across plans: argument-resolution uses it on
 * {@code ArgumentRef.TableInputArg.fieldBindings}, and the {@code @nodeId} + {@code @node}
 * directive-support plan uses it to map composite-platform-key inputs to record-level accessor
 * columns. See {@code docs/argument-resolution.md#cross-plan-ownership} and
 * {@code docs/planning/rewrite-roadmap.md}.
 *
 * <p>{@code inputFieldName} is the GraphQL field name (e.g. {@code "filmId"}).
 * {@code targetColumn} is the resolved column on the target table.
 * {@code extraction} tells the generator how to read the field's value at the call site.
 */
public record InputColumnBinding(
    String inputFieldName,
    ColumnRef targetColumn,
    CallSiteExtraction extraction
) {}
