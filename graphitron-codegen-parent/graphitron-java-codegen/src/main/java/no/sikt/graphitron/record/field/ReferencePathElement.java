package no.sikt.graphitron.record.field;

import java.util.Optional;

/**
 * One step in a {@code @reference} path, corresponding to one {@code ReferenceElement} in the schema.
 *
 * <p>{@code table} is the SQL name of the intermediate table to join to; {@code null} when not
 * specified (the join target is inferred from the key or from the field's return type).
 *
 * <p>{@code key} is the SQL name of the foreign key constant to use; {@code null} when not
 * specified (Graphitron will attempt FK auto-inference between the two tables).
 *
 * <p>{@code condition} is the resolved condition method for this step; empty when no
 * {@code condition} was specified on this element. When present, used to generate the join ON
 * clause (lift conditions have no FK and are method-only).
 */
public record ReferencePathElement(
    String table,
    String key,
    Optional<MethodRef> condition
) {}
