package no.sikt.graphitron.model.jooq;

/**
 * A resolved foreign-key reference: enough information to emit
 * {@code .onKey(<keysClass>.<constantName>)} without any per-emit-site classpath assumptions.
 *
 * <p>{@code sqlName} is the SQL constraint name (e.g. {@code "film_language_id_fkey"}), retained
 * for error messages and debugging. {@code constantName} is the Java field name on the schema's
 * generated {@code Keys} class (e.g. {@code "FK_FILM__FILM_LANGUAGE_ID_FKEY"}). {@code keysClassName}
 * is the fully qualified name of that generated {@code Keys} class — read directly from the live
 * class via reflection at catalog-resolution time, so multi-schema layouts produce schema-
 * segmented FQNs (e.g. {@code multischema_a.Keys}) without the per-caller
 * {@code ClassName.get(jooqPackage, "Keys")} concatenation that this record retires.
 *
 * <p>Built by {@link no.sikt.graphitron.model.jooq.JooqCatalog#findForeignKeyRef(org.jooq.ForeignKey)}
 * from a resolved jOOQ {@link org.jooq.ForeignKey} by reference identity; emitters consume it
 * through {@code On.Keying.ForeignKey#fk()}.
 */
public record ForeignKeyRef(String sqlName, String keysClassName, String constantName) {}
