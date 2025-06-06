/*
 * This file is generated by jOOQ.
 */
package no.sikt.graphitron.example.generated.jooq.tables;


import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import no.sikt.graphitron.converters.ShortToIntegerConverter;
import no.sikt.graphitron.example.generated.jooq.Indexes;
import no.sikt.graphitron.example.generated.jooq.Keys;
import no.sikt.graphitron.example.generated.jooq.Public;
import no.sikt.graphitron.example.generated.jooq.tables.Actor.ActorPath;
import no.sikt.graphitron.example.generated.jooq.tables.Film.FilmPath;
import no.sikt.graphitron.example.generated.jooq.tables.records.FilmActorRecord;

import org.jooq.Condition;
import org.jooq.Field;
import org.jooq.ForeignKey;
import org.jooq.Index;
import org.jooq.InverseForeignKey;
import org.jooq.Name;
import org.jooq.Path;
import org.jooq.PlainSQL;
import org.jooq.QueryPart;
import org.jooq.Record;
import org.jooq.SQL;
import org.jooq.Schema;
import org.jooq.Select;
import org.jooq.Stringly;
import org.jooq.Table;
import org.jooq.TableField;
import org.jooq.TableOptions;
import org.jooq.UniqueKey;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;
import org.jooq.impl.TableImpl;


/**
 * This class is generated by jOOQ.
 */
@SuppressWarnings({ "all", "unchecked", "rawtypes" })
public class FilmActor extends TableImpl<FilmActorRecord> {

    private static final long serialVersionUID = 1L;

    /**
     * The reference instance of <code>public.film_actor</code>
     */
    public static final FilmActor FILM_ACTOR = new FilmActor();

    /**
     * The class holding records for this type
     */
    @Override
    public Class<FilmActorRecord> getRecordType() {
        return FilmActorRecord.class;
    }

    /**
     * The column <code>public.film_actor.actor_id</code>.
     */
    public final TableField<FilmActorRecord, Integer> ACTOR_ID = createField(DSL.name("actor_id"), SQLDataType.SMALLINT.nullable(false), this, "", new ShortToIntegerConverter());

    /**
     * The column <code>public.film_actor.film_id</code>.
     */
    public final TableField<FilmActorRecord, Integer> FILM_ID = createField(DSL.name("film_id"), SQLDataType.SMALLINT.nullable(false), this, "", new ShortToIntegerConverter());

    /**
     * The column <code>public.film_actor.last_update</code>.
     */
    public final TableField<FilmActorRecord, LocalDateTime> LAST_UPDATE = createField(DSL.name("last_update"), SQLDataType.LOCALDATETIME(6).nullable(false).defaultValue(DSL.field(DSL.raw("now()"), SQLDataType.LOCALDATETIME)), this, "");

    private FilmActor(Name alias, Table<FilmActorRecord> aliased) {
        this(alias, aliased, (Field<?>[]) null, null);
    }

    private FilmActor(Name alias, Table<FilmActorRecord> aliased, Field<?>[] parameters, Condition where) {
        super(alias, null, aliased, parameters, DSL.comment(""), TableOptions.table(), where);
    }

    /**
     * Create an aliased <code>public.film_actor</code> table reference
     */
    public FilmActor(String alias) {
        this(DSL.name(alias), FILM_ACTOR);
    }

    /**
     * Create an aliased <code>public.film_actor</code> table reference
     */
    public FilmActor(Name alias) {
        this(alias, FILM_ACTOR);
    }

    /**
     * Create a <code>public.film_actor</code> table reference
     */
    public FilmActor() {
        this(DSL.name("film_actor"), null);
    }

    public <O extends Record> FilmActor(Table<O> path, ForeignKey<O, FilmActorRecord> childPath, InverseForeignKey<O, FilmActorRecord> parentPath) {
        super(path, childPath, parentPath, FILM_ACTOR);
    }

    /**
     * A subtype implementing {@link Path} for simplified path-based joins.
     */
    public static class FilmActorPath extends FilmActor implements Path<FilmActorRecord> {

        private static final long serialVersionUID = 1L;
        public <O extends Record> FilmActorPath(Table<O> path, ForeignKey<O, FilmActorRecord> childPath, InverseForeignKey<O, FilmActorRecord> parentPath) {
            super(path, childPath, parentPath);
        }
        private FilmActorPath(Name alias, Table<FilmActorRecord> aliased) {
            super(alias, aliased);
        }

        @Override
        public FilmActorPath as(String alias) {
            return new FilmActorPath(DSL.name(alias), this);
        }

        @Override
        public FilmActorPath as(Name alias) {
            return new FilmActorPath(alias, this);
        }

        @Override
        public FilmActorPath as(Table<?> alias) {
            return new FilmActorPath(alias.getQualifiedName(), this);
        }
    }

    @Override
    public Schema getSchema() {
        return aliased() ? null : Public.PUBLIC;
    }

    @Override
    public List<Index> getIndexes() {
        return Arrays.asList(Indexes.IDX_FK_FILM_ID);
    }

    @Override
    public UniqueKey<FilmActorRecord> getPrimaryKey() {
        return Keys.FILM_ACTOR_PKEY;
    }

    @Override
    public List<ForeignKey<FilmActorRecord, ?>> getReferences() {
        return Arrays.asList(Keys.FILM_ACTOR__FILM_ACTOR_ACTOR_ID_FKEY, Keys.FILM_ACTOR__FILM_ACTOR_FILM_ID_FKEY);
    }

    private transient ActorPath _actor;

    /**
     * Get the implicit join path to the <code>public.actor</code> table.
     */
    public ActorPath actor() {
        if (_actor == null)
            _actor = new ActorPath(this, Keys.FILM_ACTOR__FILM_ACTOR_ACTOR_ID_FKEY, null);

        return _actor;
    }

    private transient FilmPath _film;

    /**
     * Get the implicit join path to the <code>public.film</code> table.
     */
    public FilmPath film() {
        if (_film == null)
            _film = new FilmPath(this, Keys.FILM_ACTOR__FILM_ACTOR_FILM_ID_FKEY, null);

        return _film;
    }

    @Override
    public FilmActor as(String alias) {
        return new FilmActor(DSL.name(alias), this);
    }

    @Override
    public FilmActor as(Name alias) {
        return new FilmActor(alias, this);
    }

    @Override
    public FilmActor as(Table<?> alias) {
        return new FilmActor(alias.getQualifiedName(), this);
    }

    /**
     * Rename this table
     */
    @Override
    public FilmActor rename(String name) {
        return new FilmActor(DSL.name(name), null);
    }

    /**
     * Rename this table
     */
    @Override
    public FilmActor rename(Name name) {
        return new FilmActor(name, null);
    }

    /**
     * Rename this table
     */
    @Override
    public FilmActor rename(Table<?> name) {
        return new FilmActor(name.getQualifiedName(), null);
    }

    /**
     * Create an inline derived table from this table
     */
    @Override
    public FilmActor where(Condition condition) {
        return new FilmActor(getQualifiedName(), aliased() ? this : null, null, condition);
    }

    /**
     * Create an inline derived table from this table
     */
    @Override
    public FilmActor where(Collection<? extends Condition> conditions) {
        return where(DSL.and(conditions));
    }

    /**
     * Create an inline derived table from this table
     */
    @Override
    public FilmActor where(Condition... conditions) {
        return where(DSL.and(conditions));
    }

    /**
     * Create an inline derived table from this table
     */
    @Override
    public FilmActor where(Field<Boolean> condition) {
        return where(DSL.condition(condition));
    }

    /**
     * Create an inline derived table from this table
     */
    @Override
    @PlainSQL
    public FilmActor where(SQL condition) {
        return where(DSL.condition(condition));
    }

    /**
     * Create an inline derived table from this table
     */
    @Override
    @PlainSQL
    public FilmActor where(@Stringly.SQL String condition) {
        return where(DSL.condition(condition));
    }

    /**
     * Create an inline derived table from this table
     */
    @Override
    @PlainSQL
    public FilmActor where(@Stringly.SQL String condition, Object... binds) {
        return where(DSL.condition(condition, binds));
    }

    /**
     * Create an inline derived table from this table
     */
    @Override
    @PlainSQL
    public FilmActor where(@Stringly.SQL String condition, QueryPart... parts) {
        return where(DSL.condition(condition, parts));
    }

    /**
     * Create an inline derived table from this table
     */
    @Override
    public FilmActor whereExists(Select<?> select) {
        return where(DSL.exists(select));
    }

    /**
     * Create an inline derived table from this table
     */
    @Override
    public FilmActor whereNotExists(Select<?> select) {
        return where(DSL.notExists(select));
    }
}
