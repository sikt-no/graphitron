package no.sikt.graphitron.rewrite.test.extensions;

import no.sikt.graphitron.rewrite.test.jooq.public_.tables.records.ActorRecord;
import no.sikt.graphitron.rewrite.test.jooq.public_.tables.records.FilmRecord;
import org.jooq.Condition;
import org.jooq.Field;
import org.jooq.Table;
import org.jooq.UpdatableRecord;
import org.jooq.impl.DSL;
import org.jooq.impl.TableImpl;

/**
 * Methods taking a table as one of jOOQ's public table types rather than as a generated table class,
 * for the fact documents {@code call-parameter-table-fit} and {@code external-field-expression}: which
 * catalog tables such a parameter fits is jOOQ's public hierarchy above the generated classes, the
 * same for an {@code @externalField} lifter as for a {@code @condition}. One takes jOOQ's internal
 * {@code TableImpl}, which is no public contract and fits no table.
 *
 * <p>The bodies are not the point and none of them is called.
 */
public final class TableTypeFits {

    private TableTypeFits() {}

    /** A lifter typed as a Table of film's record: fits film, as its generated class does. */
    public static Field<String> titleOfFilm(Table<FilmRecord> table) {
        return DSL.upper(table.field("title", String.class));
    }

    /** A lifter typed as a Table of actor's record: fits actor, not film. */
    public static Field<String> nameOfActor(Table<ActorRecord> table) {
        return DSL.upper(table.field("first_name", String.class));
    }

    /** A lifter typed as a Table of any updatable record: fits every table with a primary key. */
    public static Field<String> keyedTitle(Table<? extends UpdatableRecord<?>> table) {
        return DSL.upper(table.field("title", String.class));
    }

    /** A lifter typed as jOOQ's internal TableImpl: fits no table. */
    public static Field<String> titleViaImpl(TableImpl<FilmRecord> table) {
        return DSL.upper(table.field("title", String.class));
    }

    /** A condition typed as a Table of film's record, its other parameter taking the field's argument. */
    public static Condition titleIs(Table<FilmRecord> table, String title) {
        return table.field("title", String.class).eq(title);
    }
}
