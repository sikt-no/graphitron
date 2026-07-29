package no.sikt.graphitron.rewrite.model;

import java.util.List;

/**
 * A WHERE-clause contribution backed by Graphitron-generated column predicates.
 *
 * <p>The builder creates one {@code GeneratedConditionFilter} per SQL-generating field that has
 * at least one filterable argument (scalar, enum, or input). All filterable arguments for a field
 * are grouped into this single filter; each argument becomes one {@link BodyParam} entry paired
 * positionally with its {@link CallParam} call-site view. Validation of column resolution, enum
 * value matching, and input type expansion happens in the builder before this record is
 * constructed. The condition producer turns the pairs into column terms the glue renderer
 * renders directly; no named method identity exists for the generated arm (the retired entity
 * layer's {@code <ReturnType>Conditions} naming facts went with it).
 *
 * <p>{@link #tableRef()} is the resolved table of the field's return type, the table the
 * predicates land on.
 */
public record GeneratedConditionFilter(
    TableRef tableRef,
    List<CallParam> callParams,
    List<BodyParam> bodyParams
) implements WhereFilter {}
