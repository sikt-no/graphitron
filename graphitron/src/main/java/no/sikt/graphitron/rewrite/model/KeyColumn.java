package no.sikt.graphitron.rewrite.model;

/**
 * One column contribution on the WHERE side of an UPDATE or DELETE: a filter column the
 * input fills. Carries the GraphQL input field name it came from, the jOOQ column it fills, and how
 * to read the input value at the call-site root. On UPDATE every {@code KeyColumn} is a matched-key
 * column (the WHERE partition); on DELETE it is any admitted input column, since DELETE
 * has no SET partition and every input column is a WHERE filter — the matched key there is a
 * single-row guard, not the WHERE-column set.
 *
 * <p>Like {@link SetColumn}, decoupled from {@link InputField}. The composite-NodeId case
 * maps one SDL input field to N {@code KeyColumn} rows sharing one {@link #sdlFieldName()}
 * but differing in {@link #targetColumn()}; the emitter groups by {@link #sdlFieldName()} to emit
 * one decode local that all N columns reference. {@link #extraction()} reuses the existing
 * {@link CallSiteExtraction} family ({@code Direct}, or arity-1 / arity-N
 * {@link CallSiteExtraction.NodeIdDecodeKeys}).
 */
public record KeyColumn(
    String sdlFieldName,
    ColumnRef targetColumn,
    CallSiteExtraction extraction
) {}
