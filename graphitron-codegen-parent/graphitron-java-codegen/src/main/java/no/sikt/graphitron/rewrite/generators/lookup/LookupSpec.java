package no.sikt.graphitron.rewrite.generators.lookup;

import java.util.List;

/**
 * All data needed to generate the lookup methods for one {@code LookupQueryField}.
 *
 * <p>{@code typeName} is the return type name (e.g. {@code "Customer"}), used to name the
 * generated class ({@code CustomerLookup}).
 *
 * <p>{@code inputArgName} distinguishes two code-generation paths:
 * <ul>
 *   <li>Non-null (e.g. {@code "input"}) — the lookup uses a single {@code TableInputType}
 *       argument. The generated {@code toInputRows} method will cast
 *       {@code arguments.get(inputArgName)} to {@code List<Map<String,Object>>} and extract
 *       each {@link LookupInputFieldSpec#argName()} from each element map.</li>
 *   <li>Null — the lookup uses flat scalar/list arguments. List fields have local variables
 *       declared from {@code arguments}; scalar fields are read inline per row.</li>
 * </ul>
 *
 * <p>{@code fields} is the ordered list of column mappings to include in the generated row.
 */
public record LookupSpec(
    String typeName,
    String inputArgName,
    List<LookupInputFieldSpec> fields
) {}
