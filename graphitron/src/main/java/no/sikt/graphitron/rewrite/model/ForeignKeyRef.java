package no.sikt.graphitron.rewrite.model;

import no.sikt.graphitron.javapoet.ClassName;

/**
 * A resolved foreign-key reference: enough information to emit
 * {@code .onKey(<keysClass>.<constantName>)} without any per-emit-site classpath assumptions.
 *
 * <p>{@code sqlName} is the SQL constraint name (e.g. {@code "film_language_id_fkey"}), retained
 * for error messages and debugging. {@code constantName} is the Java field name on the schema's
 * generated {@code Keys} class (e.g. {@code "FK_FILM__FILM_LANGUAGE_ID_FKEY"}). {@code keysClass}
 * is the {@link ClassName} of that generated {@code Keys} class — read directly from the live
 * class via reflection at catalog-resolution time, so multi-schema layouts produce schema-
 * segmented FQNs (e.g. {@code multischema_a.Keys}) without the per-caller
 * {@code ClassName.get(jooqPackage, "Keys")} concatenation that this record retires.
 *
 * <p>Built by {@link no.sikt.graphitron.rewrite.JooqCatalog#findForeignKeyByName(String)} from a
 * resolved jOOQ {@link org.jooq.ForeignKey}; emitters consume it through
 * {@link JoinStep.FkJoin#fk()}.
 */
public record ForeignKeyRef(String sqlName, ClassName keysClass, String constantName) {}
