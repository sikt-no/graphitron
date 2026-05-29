package no.sikt.graphitron.rewrite.model;

/**
 * R246 — the slim per-argument surface a DML walker-carrier field needs to read its {@code @table}
 * input off {@code env} and reference the jOOQ table at emit time. Carries the SDL argument name,
 * the SDL input type name, the resolved jOOQ {@link TableRef}, and the single-row-vs-bulk
 * dispatch flag (the argument's outer {@code GraphQLList} wrapper).
 *
 * <p>Deliberately a separate slot from the verb-specific carrier ({@link UpdateRows}, and the
 * future {@code DeleteRows} / {@code InsertRows}): every DML walker-carrier slice reuses the same
 * arg surface, so the slot lands once here and is shared across kinds via the narrow
 * {@link UpdateRowsField}-style interfaces. The emitter reads {@link #name()} to pull the argument
 * value from {@code env} and {@link #table()} to reference the jOOQ constants; {@link #list()}
 * drives the single-row vs {@code FROM (VALUES …)} bulk dispatch.
 */
public record InputArgRef(
    String name,
    String inputTypeName,
    TableRef table,
    boolean list
) {}
