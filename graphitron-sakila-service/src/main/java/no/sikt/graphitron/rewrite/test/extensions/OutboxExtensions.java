package no.sikt.graphitron.rewrite.test.extensions;

import no.sikt.graphitron.rewrite.test.jooq.public_.tables.Outbox;
import org.jooq.Field;
import org.jooq.impl.DSL;

/**
 * {@code @externalField} helpers over {@code outbox}, for a nesting type reached through a field
 * named like one of the parent's columns: {@code payload} is a {@code jsonb} column, whose Java type
 * has no accessor for any of the nesting type's fields, so the nesting type must be read off the
 * parent's row and not off the column's value.
 */
public final class OutboxExtensions {

    private OutboxExtensions() {}

    /** The message's event in lower case, visibly distinct from the column. */
    public static Field<String> eventKind(Outbox table) {
        return DSL.lower(table.TYPE);
    }
}
