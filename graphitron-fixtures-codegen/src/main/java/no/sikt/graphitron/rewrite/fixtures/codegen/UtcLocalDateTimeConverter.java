package no.sikt.graphitron.rewrite.fixtures.codegen;

import org.jooq.Converter;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * Fixture converter: maps the {@code change_instant_domain} PostgreSQL domain (over
 * {@code timestamptz}) to {@code java.time.LocalDateTime}, both directions at UTC so a read and a
 * write agree whatever the session time zone. It stands in for an Oracle {@code DATE} key column a
 * consumer maps to {@code LocalDateTime}: a converter-backed temporal key whose database type does
 * not parse the user type's text, so a node id over it decodes only when the slot is parsed as the
 * user type. Registered via a {@code <forcedTypes>} entry on graphitron-sakila-db's public-schema
 * codegen execution.
 */
public class UtcLocalDateTimeConverter implements Converter<OffsetDateTime, LocalDateTime> {

    @java.io.Serial
    private static final long serialVersionUID = 1L;

    @Override
    public LocalDateTime from(OffsetDateTime databaseObject) {
        return databaseObject == null ? null : databaseObject.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }

    @Override
    public OffsetDateTime to(LocalDateTime userObject) {
        return userObject == null ? null : userObject.atOffset(ZoneOffset.UTC);
    }

    @Override
    public Class<OffsetDateTime> fromType() {
        return OffsetDateTime.class;
    }

    @Override
    public Class<LocalDateTime> toType() {
        return LocalDateTime.class;
    }
}
