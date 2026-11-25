package no.sikt.graphitron.model.capture.jooq;

import no.sikt.graphitron.model.sink.Progress;
import no.sikt.graphitron.model.derive.ColumnSpellings;
import no.sikt.graphitron.model.derive.NameMatchedKeys;
import no.sikt.graphitron.model.derive.ReferentialReaches;
import no.sikt.graphitron.model.sink.BindBatch;
import no.sikt.graphitron.model.jooq.JooqCatalog;
import no.sikt.graphitron.model.jooq.RecordGetters;
import no.sikt.graphitron.model.capture.code.ClasspathSourceCapture;
import no.sikt.graphitron.model.config.ClasspathEntry;
import no.sikt.graphitron.model.read.SourceStamp;
import no.sikt.graphitron.model.sources.Observation;
import org.jooq.DSLContext;
import org.jooq.EnumType;
import org.jooq.Field;
import org.jooq.Rows;
import org.jooq.Schema;
import org.jooq.Table;
import org.jooq.UniqueKey;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static no.sikt.graphitron.model.Tables.SQL_COLUMN;
import static no.sikt.graphitron.model.Tables.SQL_COLUMN_RECORD_ACCESSOR;
import static no.sikt.graphitron.model.Tables.STORE_GRAPH_SOURCE;
import static no.sikt.graphitron.model.Tables.SQL_CONSTRAINT;
import static no.sikt.graphitron.model.Tables.SQL_CONSTRAINT_COLUMN;
import static no.sikt.graphitron.model.Tables.SQL_ENUM_BINDING;
import static no.sikt.graphitron.model.Tables.SQL_ENUM_BINDING_LITERAL;
import static no.sikt.graphitron.model.Tables.SQL_INDEX;
import static no.sikt.graphitron.model.Tables.SQL_INDEX_COLUMN;
import static no.sikt.graphitron.model.Tables.SQL_EMBEDDABLE_COLUMN;
import static no.sikt.graphitron.model.Tables.SQL_TABLE_FIELD;
import static no.sikt.graphitron.model.Tables.SQL_NODE_KEY_COLUMN;
import static no.sikt.graphitron.model.Tables.SQL_NODE_LEGACY_KEY_COLUMN;
import static no.sikt.graphitron.model.Tables.SQL_NODE_LEGACY_KEY_UNREADABLE;
import static no.sikt.graphitron.model.Tables.SQL_NODE_METADATA;
import static no.sikt.graphitron.model.Tables.SQL_NODE_METADATA_UNREADABLE;
import static no.sikt.graphitron.model.Tables.SQL_PRIMARY_KEY;
import static no.sikt.graphitron.model.Tables.SQL_REFERENTIAL_CONSTRAINT;
import static no.sikt.graphitron.model.Tables.SQL_REFERENTIAL_CONSTRAINT_COLUMN;
import static no.sikt.graphitron.model.Tables.SQL_ROUTINE;
import static no.sikt.graphitron.model.Tables.SQL_ROUTINE_PARAMETER;
import static no.sikt.graphitron.model.Tables.SQL_SCHEMA;
import static no.sikt.graphitron.model.Tables.SQL_TABLE;
import static no.sikt.graphitron.model.Tables.SQL_TABLE_RECORD_SUPERTYPE;
import static no.sikt.graphitron.model.Tables.SQL_TABLE_REFERENCE;
import static no.sikt.graphitron.model.Tables.STORE_SOURCE;
import static org.jooq.impl.DSL.count;
import static org.jooq.impl.DSL.excluded;
import static org.jooq.impl.DSL.val;
import static org.jooq.impl.SQLDataType.INTEGER;

/**
 * Writes the consumer's database as the generated jOOQ classes describe it.
 *
 * <p>The {@code sql_} family and nothing else: one source of one shape behind one entry point.
 *
 * <p>Keyed on the source rather than on a graph, because a catalog describes one database and
 * several graphs may read it. A source is one schema package as one classpath entry holds it, which
 * {@link #sourceName} spells: two builds of one package, a stale jar and a fresh compile say, are
 * two sources, so neither reading's sweep reaches the other's rows. The sweep is scoped to the
 * sources, so a source that has left the build keeps its rows until the registry drops it.
 *
 * <p>Reads {@link JooqCatalog} directly rather than a consumer-shaped view of it. A view built for
 * one reader carries that reader's narrowings, and a narrowing landing here would become a fact
 * about the consumer's database.
 */
public final class JooqFactCapture {

    private JooqFactCapture() {}

    /** The source kind one schema package of a classpath entry is registered under. */
    private static final String JOOQ_SCHEMA = "JOOQ_SCHEMA";

    /** This gatherer's row in {@code store_graph_progress}. */
    private static final String GATHERER = "jooq";

    /**
     * Makes the {@code sql_} rows of the catalog generated into {@code jooqPackage} be what it now
     * says, then the catalog's own closure over them, the keys a name alone matches; one
     * transaction, its progress recorded by {@link Progress}. The catalog is read through
     * {@code loader}, jOOQ building its tables in static initialisers, and left alone where the entry
     * it is generated into is what it was last read from.
     *
     * <p>The catalog is the one in the entry {@code loader} loads its {@code DefaultCatalog} class
     * from, and only where that entry is one of {@code classpath}'s own: an entry the graph inherits
     * ({@link ClasspathEntry.Origin#TRANSITIVE}) holds no catalog of the graph's, so a graph naming a
     * package only such an entry holds claims no catalog, as does a graph naming none. Either way the
     * graph drops whatever catalog sources it claimed before. A run handed no classpath has said
     * nothing about which entries are its own, the classpath reading reading none of them, so its
     * catalog is the entry the loader reads it from.
     *
     * <p>The catalog's bytes are its entry's, so its stamp is the entry's stamp, decided by
     * {@link ClasspathSourceCapture#entryStamp} on the classpath reading's own terms, with the
     * entry's path beside it: the same classes under another entry are another reading. A catalog
     * left alone is still claimed, and its rows are not swept, the sweep being the reading's. The
     * stamp is written once the reading has committed, so a capture stopped before it leaves the
     * catalog unvouched and the next one reads it again.
     *
     * @param classpath   the graph's entries, inherited ones included, one of which holds the catalog
     * @param observation what a dev session watches; null for a run watching nothing
     * @return whether the catalog was left alone, or there is none; which package the graph names
     *         is configuration, and {@code StoreEntries} vouches that
     */
    public static boolean capture(DSLContext dsl, String graphName, String jooqPackage, ClassLoader loader,
                                  List<ClasspathEntry> classpath, Observation observation,
                                  LocalDateTime touchedAt) {
        ClasspathEntry entry = jooqPackage == null ? null : entryOf(jooqPackage, loader, classpath);
        if (entry == null) {
            capture(dsl, graphName, null, null, touchedAt);
            return jooqPackage == null;
        }
        String stamp = stampOf(dsl, entry, observation);
        Set<String> current = stamp == null ? Set.of() : vouched(dsl, jooqPackage, stamp);
        if (current.isEmpty()) {
            var read = capture(dsl, graphName, new JooqCatalog(jooqPackage, loader), entryName(entry),
                touchedAt);
            vouch(dsl, read, stamp);
            return false;
        }
        Progress.started(dsl, graphName, GATHERER);
        // No catalog read, so no sql_ row moved: the name-matched keys, the column spellings and the
        // referential reaches, which read the sql_ family and nothing else, are what the last reading
        // derived.
        dsl.transaction(tx -> {
            dropMembership(tx.dsl(), graphName, current);
            graphSources(tx.dsl(), graphName, List.copyOf(current), touchedAt, false);
        });
        Progress.completed(dsl, graphName, GATHERER);
        return true;
    }

    private static Set<String> capture(DSLContext dsl, String graphName, JooqCatalog jooq,
                                       String entryName, LocalDateTime touchedAt) {
        Progress.started(dsl, graphName, GATHERER);
        Set<String> read = dsl.transactionResult(tx -> {
            var sources = gather(tx.dsl(), graphName, jooq, entryName, touchedAt);
            NameMatchedKeys.derive(tx.dsl());
            ColumnSpellings.derive(tx.dsl());
            ReferentialReaches.derive(tx.dsl());
            return sources;
        });
        Progress.completed(dsl, graphName, GATHERER);
        return read;
    }

    /**
     * The name a catalog source is registered under: the classpath entry its classes were read from,
     * as the classpath reading names that entry's own source, then {@code !}, then the schema's
     * generated package. A reactor build's catalog is
     * {@code /work/db/target/classes!com.example.db.public_} and a jar's
     * {@code /home/u/.m2/.../db-1.0.jar!com.example.db.public_}. The entry is half the key because
     * two builds of one package are two catalogs; the package is the other half because one entry
     * carries every schema a codegen run produced, each in a package of its own. One build reachable
     * at two paths is stored twice.
     *
     * <p>A name to join on, never to take apart: the package is {@code sql_schema.schema_package}
     * and the catalog it belongs to {@code sql_schema.catalog_package}.
     */
    static String sourceName(String entryName, String schemaPackage) {
        return entryName + "!" + schemaPackage;
    }

    /** The entry as the classpath reading names its own source of it. */
    private static String entryName(ClasspathEntry entry) {
        return entry.path().toString();
    }

    /**
     * The entry of {@code classpath} the catalog's {@code DefaultCatalog} class is loaded from, where
     * that is one the graph does not inherit. Null where the loader finds no such class, or finds it
     * in an entry the classpath does not name or names as {@link ClasspathEntry.Origin#TRANSITIVE}.
     * An empty classpath classifies nothing, so the entry is the one the loader found.
     */
    private static ClasspathEntry entryOf(String jooqPackage, ClassLoader loader,
                                          List<ClasspathEntry> classpath) {
        String resource = jooqPackage.replace('.', '/') + "/DefaultCatalog.class";
        var url = loader.getResource(resource);
        if (url == null) {
            return null;
        }
        Path root;
        try {
            if ("jar".equals(url.getProtocol())) {
                String spec = url.getPath();
                root = Path.of(java.net.URI.create(spec.substring(0, spec.indexOf("!/"))));
            } else if ("file".equals(url.getProtocol())) {
                root = Path.of(url.toURI());
                for (int i = 0; i < resource.split("/").length; i++) {
                    root = root.getParent();
                }
            } else {
                return null;
            }
        } catch (java.net.URISyntaxException | RuntimeException e) {
            return null;
        }
        Path held = root.toAbsolutePath().normalize();
        if (classpath.isEmpty()) {
            return ClasspathEntry.project(held);
        }
        for (ClasspathEntry entry : classpath) {
            if (entry.path().toAbsolutePath().normalize().equals(held)) {
                return entry.origin() == ClasspathEntry.Origin.TRANSITIVE ? null : entry;
            }
        }
        return null;
    }

    /**
     * The catalog's stamp: the stamp of the entry it is generated into, with that entry's path. Null
     * where the entry cannot be stamped, which reads as changed.
     */
    private static String stampOf(DSLContext dsl, ClasspathEntry entry, Observation observation) {
        String entryStamp = ClasspathSourceCapture.entryStamp(dsl, entry, observation);
        return entryStamp == null ? null : SourceStamp.of((entry.path() + "\u0001" + entryStamp)
            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * The catalog sources last read from the bytes {@code stamp} names, which are one entry's, under
     * the catalog {@code jooqPackage} names: one entry may hold several catalogs.
     */
    private static Set<String> vouched(DSLContext dsl, String jooqPackage, String stamp) {
        var s = STORE_SOURCE;
        var c = SQL_SCHEMA;
        return Set.copyOf(dsl.selectDistinct(s.SOURCE_NAME).from(s)
            .join(c).on(c.SOURCE_NAME.eq(s.SOURCE_NAME))
            .where(s.SOURCE_KIND.eq(JOOQ_SCHEMA), s.STAMP.eq(stamp), c.CATALOG_PACKAGE.eq(jooqPackage))
            .fetch(s.SOURCE_NAME));
    }

    /**
     * Vouches for the catalog sources a committed reading wrote: they are derived from {@code stamp}.
     * A reading with no stamp clears the one they held, which vouched for rows this reading replaced.
     */
    private static void vouch(DSLContext dsl, Set<String> read, String stamp) {
        if (read.isEmpty()) {
            return;
        }
        var s = STORE_SOURCE;
        dsl.update(s).set(s.STAMP, stamp).where(s.SOURCE_NAME.in(read)).execute();
    }

    private static Set<String> gather(DSLContext dsl, String graphName, JooqCatalog jooq,
                                      String entryName, LocalDateTime touchedAt) {
        List<TableAt> tables = jooq == null ? List.of() : tablesOf(jooq, entryName);
        dropMembership(dsl, graphName, sourceNames(tables));
        if (tables.isEmpty()) {
            return Set.of();
        }
        graphSources(dsl, graphName, tables.stream().map(TableAt::source).distinct().toList(), touchedAt, true);
        // Parents before children, which the sweep then takes in reverse.
        sources(dsl, tables, touchedAt);
        schemas(dsl, jooq, tables, touchedAt);
        tables(dsl, tables, touchedAt);
        columns(dsl, jooq, tables, touchedAt);
        List<ConstraintAt> constraints = constraintsOf(jooq, tables);
        constraints(dsl, constraints, touchedAt);
        constraintColumns(dsl, constraints, touchedAt);
        primaryKeys(dsl, tables, touchedAt);
        referentialConstraints(dsl, jooq, tables, touchedAt);
        referentialConstraintColumns(dsl, jooq, tables, touchedAt);
        tableReferences(dsl, sourceNames(tables), touchedAt);
        indexes(dsl, jooq, tables, touchedAt);
        indexColumns(dsl, jooq, tables, touchedAt);
        embeddables(dsl, jooq, tables, touchedAt);
        embeddableColumns(dsl, jooq, tables, touchedAt);
        List<NodeMetadataAt> nodeMetadata = nodeMetadataOf(jooq, tables);
        nodeMetadata(dsl, nodeMetadata, touchedAt);
        nodeKeyColumns(dsl, nodeMetadata, touchedAt);
        nodeMetadataUnreadable(dsl, nodeMetadata, touchedAt);
        List<NodeLegacyKeyAt> legacyKeys = nodeLegacyKeysOf(jooq, tables, nodeMetadata);
        nodeLegacyKeyColumns(dsl, legacyKeys, touchedAt);
        nodeLegacyKeyUnreadable(dsl, legacyKeys, touchedAt);
        routines(dsl, jooq, tables, touchedAt);
        routineParameters(dsl, jooq, tables, touchedAt);
        executableRoutines(dsl, jooq, tables, entryName, touchedAt);
        recordSupertypes(dsl, tables);
        recordAccessors(dsl, jooq, tables, touchedAt);
        enumBindings(dsl, tables, touchedAt);
        sweep(dsl, sourceNames(tables), touchedAt);
        return sourceNames(tables);
    }

    // --------------------------------------------------------------- what a row is written about

    /**
     * One table of the generated model, with the parts every row about it repeats. Its source is
     * the schema package it was generated into, {@link JooqCatalog#schemaPackageOf}, in the entry
     * the catalog was read from, as {@link #sourceName} spells the two.
     */
    private record TableAt(String source, String schema, String name, Table<?> table,
                           String jooqName) {}

    /** One constraint of one table, and the columns it names in key order. */
    private record ConstraintAt(TableAt at, String name, String type, String jooqName,
                                Integer keyPosition, List<String> columns) {}

    /** A value at its place in a parent's ordered list. */
    private record PositionAt<P>(P parent, int position, String value) {}

    /** One table whose class declares node metadata, with what the catalog made of it. */
    private record NodeMetadataAt(TableAt table, JooqCatalog.NodeIdMetadataLookup lookup) {}

    /** One table whose class declares a legacy node key shape, with what the catalog made of it. */
    private record NodeLegacyKeyAt(TableAt table, JooqCatalog.NodeLegacyKeyLookup lookup) {}

    // ------------------------------------------------------------------------- the sql_ relations

    /**
     * The registry rows every {@code sql_} row hangs its source on. A jOOQ package is a source the
     * store read, on the same terms as a schema file.
     */
    private static void sources(DSLContext dsl, List<TableAt> tables, LocalDateTime touchedAt) {
        var t = STORE_SOURCE;
        var rows = sourceNames(tables).stream().collect(Rows.toRowList(
            source -> val(source, t.SOURCE_NAME),
            source -> val("JOOQ_SCHEMA", t.SOURCE_KIND),
            source -> val(touchedAt, t.LAST_SEEN),
            source -> val(touchedAt, t.READ_AT)));
        BindBatch.execute(dsl, rows, markers ->
            dsl.insertInto(t, t.SOURCE_NAME, t.SOURCE_KIND, t.LAST_SEEN, t.READ_AT)
                .values(markers)
                .onDuplicateKeyUpdate()
                .set(t.LAST_SEEN, excluded(t.LAST_SEEN))
                .set(t.READ_AT, excluded(t.READ_AT)));
    }

    private static void schemas(DSLContext dsl, JooqCatalog jooq, List<TableAt> tables,
                                LocalDateTime touchedAt) {
        var t = SQL_SCHEMA;
        var seen = schemasOf(tables);
        if (seen.isEmpty()) {
            return;
        }
        var rows = seen.stream().collect(Rows.toRowList(
            at -> val(at.source(), t.SOURCE_NAME),
            at -> val(at.schema(), t.TABLE_SCHEMA),
            at -> val(jooq.keysClassFqn(at.table().getSchema()).orElse(null), t.KEYS_CLASS_FQN),
            at -> val(jooq.tablesClassFqn(at.table().getSchema()).orElse(null), t.TABLES_CLASS_FQN),
            at -> val(JooqCatalog.schemaPackageOf(at.table().getSchema()).orElseThrow(), t.SCHEMA_PACKAGE),
            at -> val(catalogPackageOf(at.table().getSchema()), t.CATALOG_PACKAGE),
            at -> val(touchedAt, t.TOUCHED_AT)));
        BindBatch.execute(dsl, rows, markers ->
            dsl.insertInto(t, t.SOURCE_NAME, t.TABLE_SCHEMA, t.KEYS_CLASS_FQN, t.TABLES_CLASS_FQN,
                    t.SCHEMA_PACKAGE, t.CATALOG_PACKAGE, t.TOUCHED_AT)
                .values(markers)
                .onDuplicateKeyUpdate()
                .set(t.KEYS_CLASS_FQN, excluded(t.KEYS_CLASS_FQN))
                .set(t.TABLES_CLASS_FQN, excluded(t.TABLES_CLASS_FQN))
                .set(t.SCHEMA_PACKAGE, excluded(t.SCHEMA_PACKAGE))
                .set(t.CATALOG_PACKAGE, excluded(t.CATALOG_PACKAGE))
                .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT)));
    }

    private static void tables(DSLContext dsl, List<TableAt> tables, LocalDateTime touchedAt) {
        var t = SQL_TABLE;
        var rows = tables.stream().collect(Rows.toRowList(
            at -> val(at.source(), t.SOURCE_NAME),
            at -> val(at.schema(), t.TABLE_SCHEMA),
            at -> val(at.name(), t.TABLE_NAME),
            at -> val(at.table().getTableType().name(), t.TABLE_TYPE),
            at -> val(at.jooqName(), t.JOOQ_NAME),
            at -> val(at.table().getClass().getName(), t.CLASS_FQN),
            at -> val(at.table().getRecordType().getName(), t.RECORD_CLASS_FQN),
            at -> val(nullIfBlank(at.table().getComment()), t.DESCRIPTION),
            at -> val(touchedAt, t.TOUCHED_AT)));
        BindBatch.execute(dsl, rows, markers ->
            dsl.insertInto(t, t.SOURCE_NAME, t.TABLE_SCHEMA, t.TABLE_NAME, t.TABLE_TYPE, t.JOOQ_NAME,
                    t.CLASS_FQN, t.RECORD_CLASS_FQN, t.DESCRIPTION, t.TOUCHED_AT)
                .values(markers)
                .onDuplicateKeyUpdate()
                .set(t.TABLE_TYPE, excluded(t.TABLE_TYPE))
                .set(t.JOOQ_NAME, excluded(t.JOOQ_NAME))
                .set(t.CLASS_FQN, excluded(t.CLASS_FQN))
                .set(t.RECORD_CLASS_FQN, excluded(t.RECORD_CLASS_FQN))
                .set(t.DESCRIPTION, excluded(t.DESCRIPTION))
                .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT)));
    }

    /**
     * The ordinal is the table definition's own field order, not the walk's. A generated field the
     * table does not declare has no position the definition gives it, and is left out rather than
     * given one this reading invented.
     */
    private static void columns(DSLContext dsl, JooqCatalog jooq, List<TableAt> tables,
                                LocalDateTime touchedAt) {
        var t = SQL_COLUMN;
        List<PositionAt<TableAt>> named = new ArrayList<>();
        List<JooqCatalog.ColumnFacts> facts = new ArrayList<>();
        for (TableAt at : tables) {
            Map<String, Integer> positions = declaredPositions(at.table());
            for (JooqCatalog.ColumnFacts column : jooq.columnFactsOf(at.table())) {
                Integer ordinal = positions.get(column.sqlName());
                if (ordinal == null) {
                    continue;
                }
                named.add(new PositionAt<>(at, ordinal, column.sqlName()));
                facts.add(column);
            }
        }
        if (named.isEmpty()) {
            return;
        }
        var indexes = indices(named.size());
        // The supertype row first, holding the generated name and value class the column's row
        // repeats under its foreign key.
        tableFields(dsl, indexes.stream().map(i -> new FieldRow(named.get(i).parent(), named.get(i).value(),
            "COLUMN", facts.get(i).javaName(), facts.get(i).bindingType())).toList(), touchedAt);
        var rows = indexes.stream().collect(Rows.toRowList(
            i -> val(named.get(i).parent().source(), t.SOURCE_NAME),
            i -> val(named.get(i).parent().schema(), t.TABLE_SCHEMA),
            i -> val(named.get(i).parent().name(), t.TABLE_NAME),
            i -> val(named.get(i).value(), t.COLUMN_NAME),
            i -> val(named.get(i).position(), t.ORDINAL),
            i -> val(facts.get(i).javaName(), t.JOOQ_NAME),
            i -> val(facts.get(i).sqlType(), t.SQL_TYPE),
            i -> val(facts.get(i).bindingType(), t.BINDING_TYPE),
            i -> val(facts.get(i).databaseType(), t.DATABASE_TYPE),
            i -> val(facts.get(i).nullable(), t.NULLABLE),
            i -> val(facts.get(i).defaulted(), t.DEFAULTED),
            i -> val(facts.get(i).declaredLength(), t.DECLARED_LENGTH),
            i -> val(nullIfBlank(facts.get(i).comment()), t.DESCRIPTION),
            i -> val("COLUMN", t.KIND),
            i -> val(touchedAt, t.TOUCHED_AT)));
        BindBatch.execute(dsl, rows, markers ->
            dsl.insertInto(t, t.SOURCE_NAME, t.TABLE_SCHEMA, t.TABLE_NAME, t.COLUMN_NAME, t.ORDINAL,
                    t.JOOQ_NAME, t.SQL_TYPE, t.BINDING_TYPE, t.DATABASE_TYPE, t.NULLABLE, t.DEFAULTED,
                    t.DECLARED_LENGTH, t.DESCRIPTION, t.KIND, t.TOUCHED_AT)
                .values(markers)
                .onDuplicateKeyUpdate()
                .set(t.ORDINAL, excluded(t.ORDINAL))
                .set(t.JOOQ_NAME, excluded(t.JOOQ_NAME))
                .set(t.SQL_TYPE, excluded(t.SQL_TYPE))
                .set(t.BINDING_TYPE, excluded(t.BINDING_TYPE))
                .set(t.DATABASE_TYPE, excluded(t.DATABASE_TYPE))
                .set(t.NULLABLE, excluded(t.NULLABLE))
                .set(t.DEFAULTED, excluded(t.DEFAULTED))
                .set(t.DECLARED_LENGTH, excluded(t.DECLARED_LENGTH))
                .set(t.DESCRIPTION, excluded(t.DESCRIPTION))
                .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT)));
    }

    private static void constraints(DSLContext dsl, List<ConstraintAt> constraints,
                                    LocalDateTime touchedAt) {
        var t = SQL_CONSTRAINT;
        if (constraints.isEmpty()) {
            return;
        }
        // A primary key row repeats its constraint's type under its foreign key, so a constraint read
        // under its name with another type than last reading's takes that row first; primaryKeys()
        // writes the table's primary key afresh.
        var typeOf = new java.util.HashMap<List<String>, String>();
        constraints.forEach(c -> typeOf.put(List.of(c.at().source(), c.at().schema(), c.at().name(), c.name()), c.type()));
        var pk = SQL_PRIMARY_KEY;
        var sources = constraints.stream().map(c -> c.at().source()).distinct().toList();
        for (var held : dsl.select(pk.SOURCE_NAME, pk.TABLE_SCHEMA, pk.TABLE_NAME, pk.CONSTRAINT_NAME).from(pk)
                .where(pk.SOURCE_NAME.in(sources)).fetch()) {
            String type = typeOf.get(List.of(held.value1(), held.value2(), held.value3(), held.value4()));
            if (type != null && !"PRIMARY KEY".equals(type)) {
                dsl.deleteFrom(pk).where(pk.SOURCE_NAME.eq(held.value1()), pk.TABLE_SCHEMA.eq(held.value2()),
                    pk.TABLE_NAME.eq(held.value3())).execute();
            }
        }
        var rows = constraints.stream().collect(Rows.toRowList(
            c -> val(c.at().source(), t.SOURCE_NAME),
            c -> val(c.at().schema(), t.TABLE_SCHEMA),
            c -> val(c.at().name(), t.TABLE_NAME),
            c -> val(c.name(), t.CONSTRAINT_NAME),
            c -> val(c.type(), t.CONSTRAINT_TYPE),
            c -> val(c.jooqName(), t.JOOQ_NAME),
            c -> val(c.keyPosition(), t.KEY_POSITION),
            c -> val(touchedAt, t.TOUCHED_AT)));
        BindBatch.execute(dsl, rows, markers ->
            dsl.insertInto(t, t.SOURCE_NAME, t.TABLE_SCHEMA, t.TABLE_NAME, t.CONSTRAINT_NAME,
                    t.CONSTRAINT_TYPE, t.JOOQ_NAME, t.KEY_POSITION, t.TOUCHED_AT)
                .values(markers)
                // The key named: left to find any key, the upsert matches the primary key or the
                // unique one, and an OR finds no index.
                .onConflict(t.SOURCE_NAME, t.TABLE_SCHEMA, t.TABLE_NAME, t.CONSTRAINT_NAME)
                .doUpdate()
                .set(t.CONSTRAINT_TYPE, excluded(t.CONSTRAINT_TYPE))
                .set(t.JOOQ_NAME, excluded(t.JOOQ_NAME))
                .set(t.KEY_POSITION, excluded(t.KEY_POSITION))
                .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT)));
    }

    private static void constraintColumns(DSLContext dsl, List<ConstraintAt> constraints,
                                          LocalDateTime touchedAt) {
        var t = SQL_CONSTRAINT_COLUMN;
        List<PositionAt<ConstraintAt>> named = new ArrayList<>();
        for (ConstraintAt c : constraints) {
            for (int i = 0; i < c.columns().size(); i++) {
                named.add(new PositionAt<>(c, i, c.columns().get(i)));
            }
        }
        if (named.isEmpty()) {
            return;
        }
        var rows = named.stream().collect(Rows.toRowList(
            at -> val(at.parent().at().source(), t.SOURCE_NAME),
            at -> val(at.parent().at().schema(), t.TABLE_SCHEMA),
            at -> val(at.parent().at().name(), t.TABLE_NAME),
            at -> val(at.parent().name(), t.CONSTRAINT_NAME),
            at -> val(at.position(), t.POSITION),
            at -> val(at.value(), t.COLUMN_NAME),
            at -> val(touchedAt, t.TOUCHED_AT)));
        BindBatch.execute(dsl, rows, markers ->
            dsl.insertInto(t, t.SOURCE_NAME, t.TABLE_SCHEMA, t.TABLE_NAME, t.CONSTRAINT_NAME,
                    t.POSITION, t.COLUMN_NAME, t.TOUCHED_AT)
                .values(markers)
                .onDuplicateKeyUpdate()
                .set(t.COLUMN_NAME, excluded(t.COLUMN_NAME))
                .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT)));
    }

    private static void primaryKeys(DSLContext dsl, List<TableAt> tables, LocalDateTime touchedAt) {
        var t = SQL_PRIMARY_KEY;
        var withKey = tables.stream().filter(at -> at.table().getPrimaryKey() != null).toList();
        if (withKey.isEmpty()) {
            return;
        }
        var rows = withKey.stream().collect(Rows.toRowList(
            at -> val(at.source(), t.SOURCE_NAME),
            at -> val(at.schema(), t.TABLE_SCHEMA),
            at -> val(at.name(), t.TABLE_NAME),
            at -> val(at.table().getPrimaryKey().getName(), t.CONSTRAINT_NAME),
            at -> val(touchedAt, t.TOUCHED_AT)));
        BindBatch.execute(dsl, rows, markers ->
            dsl.insertInto(t, t.SOURCE_NAME, t.TABLE_SCHEMA, t.TABLE_NAME, t.CONSTRAINT_NAME,
                    t.TOUCHED_AT)
                .values(markers)
                .onDuplicateKeyUpdate()
                .set(t.CONSTRAINT_NAME, excluded(t.CONSTRAINT_NAME))
                .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT)));
    }

    /**
     * The referential half of a foreign key: the constraint it references. Which of that
     * constraint's columns each of its own names is {@link #referentialConstraintColumns}'s.
     */
    private static void referentialConstraints(DSLContext dsl, JooqCatalog jooq,
                                               List<TableAt> tables, LocalDateTime touchedAt) {
        var t = SQL_REFERENTIAL_CONSTRAINT;
        Map<String, String> sourceByTable = sourceByTable(tables);
        List<TableAt> owners = new ArrayList<>();
        List<JooqCatalog.ForeignKeyFacts> keys = new ArrayList<>();
        for (TableAt at : tables) {
            for (JooqCatalog.ForeignKeyFacts key : jooq.foreignKeyFactsOf(at.table())) {
                owners.add(at);
                keys.add(key);
            }
        }
        if (keys.isEmpty()) {
            return;
        }
        // A key read under its name with another target than last reading's goes first, its columns
        // with it: they repeat the target under their foreign key, so updating it in place would leave
        // them naming the old one. The upsert below then writes it, and its columns, afresh.
        var targetOf = new java.util.HashMap<List<String>, List<String>>();
        for (int i = 0; i < keys.size(); i++) {
            var key = keys.get(i);
            targetOf.put(List.of(owners.get(i).source(), owners.get(i).schema(), owners.get(i).name(), key.constraintName()),
                List.of(sourceByTable.getOrDefault(key.targetTable(), owners.get(i).source()),
                    split(key.targetTable())[0], split(key.targetTable())[1], key.referencedConstraintName()));
        }
        var sources = owners.stream().map(TableAt::source).distinct().toList();
        for (var held : dsl.select(t.SOURCE_NAME, t.TABLE_SCHEMA, t.TABLE_NAME, t.CONSTRAINT_NAME,
                    t.REFERENCED_SOURCE_NAME, t.REFERENCED_SCHEMA, t.REFERENCED_TABLE, t.REFERENCED_CONSTRAINT_NAME)
                .from(t).where(t.SOURCE_NAME.in(sources)).fetch()) {
            var target = targetOf.get(List.of(held.value1(), held.value2(), held.value3(), held.value4()));
            if (target != null && !target.equals(List.of(held.value5(), held.value6(), held.value7(), held.value8()))) {
                dsl.deleteFrom(t).where(t.SOURCE_NAME.eq(held.value1()), t.TABLE_SCHEMA.eq(held.value2()),
                    t.TABLE_NAME.eq(held.value3()), t.CONSTRAINT_NAME.eq(held.value4())).execute();
            }
        }
        var indexes = indices(keys.size());
        var rows = indexes.stream().collect(Rows.toRowList(
            i -> val(owners.get(i).source(), t.SOURCE_NAME),
            i -> val(sourceByTable.getOrDefault(keys.get(i).targetTable(), owners.get(i).source()),
                t.REFERENCED_SOURCE_NAME),
            i -> val(owners.get(i).schema(), t.TABLE_SCHEMA),
            i -> val(owners.get(i).name(), t.TABLE_NAME),
            i -> val(keys.get(i).constraintName(), t.CONSTRAINT_NAME),
            i -> val(split(keys.get(i).targetTable())[0], t.REFERENCED_SCHEMA),
            i -> val(split(keys.get(i).targetTable())[1], t.REFERENCED_TABLE),
            i -> val(keys.get(i).referencedConstraintName(), t.REFERENCED_CONSTRAINT_NAME),
            i -> val(touchedAt, t.TOUCHED_AT)));
        BindBatch.execute(dsl, rows, markers ->
            dsl.insertInto(t, t.SOURCE_NAME, t.REFERENCED_SOURCE_NAME, t.TABLE_SCHEMA, t.TABLE_NAME,
                    t.CONSTRAINT_NAME, t.REFERENCED_SCHEMA, t.REFERENCED_TABLE,
                    t.REFERENCED_CONSTRAINT_NAME, t.TOUCHED_AT)
                .values(markers)
                // The primary key, named: the referenced constraint beside it is a second unique
                // key, and an upsert matching on either scans every source's rows.
                .onConflict(t.SOURCE_NAME, t.TABLE_SCHEMA, t.TABLE_NAME, t.CONSTRAINT_NAME)
                .doUpdate()
                .set(t.REFERENCED_SOURCE_NAME, excluded(t.REFERENCED_SOURCE_NAME))
                .set(t.REFERENCED_SCHEMA, excluded(t.REFERENCED_SCHEMA))
                .set(t.REFERENCED_TABLE, excluded(t.REFERENCED_TABLE))
                .set(t.REFERENCED_CONSTRAINT_NAME, excluded(t.REFERENCED_CONSTRAINT_NAME))
                .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT)));
    }

    /**
     * Each foreign-key column with the position of the referenced key it names. The key's
     * {@code REFERENCES} list orders the referenced columns, and may order them differently from
     * the referenced key's own declaration, so the pairing is read off the key rather than matched
     * on position.
     */
    private static void referentialConstraintColumns(DSLContext dsl, JooqCatalog jooq,
                                                     List<TableAt> tables, LocalDateTime touchedAt) {
        var t = SQL_REFERENTIAL_CONSTRAINT_COLUMN;
        Map<String, String> sourceByTable = sourceByTable(tables);
        record Paired(TableAt owner, JooqCatalog.ForeignKeyFacts key, int position) {}
        List<Paired> paired = new ArrayList<>();
        for (TableAt at : tables) {
            for (JooqCatalog.ForeignKeyFacts key : jooq.foreignKeyFactsOf(at.table())) {
                for (int i = 0; i < key.referencedPositions().size(); i++) {
                    paired.add(new Paired(at, key, i));
                }
            }
        }
        if (paired.isEmpty()) {
            return;
        }
        var rows = paired.stream().collect(Rows.toRowList(
            p -> val(p.owner().source(), t.SOURCE_NAME),
            p -> val(p.owner().schema(), t.TABLE_SCHEMA),
            p -> val(p.owner().name(), t.TABLE_NAME),
            p -> val(p.key().constraintName(), t.CONSTRAINT_NAME),
            p -> val(p.position(), t.POSITION),
            p -> val(sourceByTable.getOrDefault(p.key().targetTable(), p.owner().source()),
                t.REFERENCED_SOURCE_NAME),
            p -> val(split(p.key().targetTable())[0], t.REFERENCED_SCHEMA),
            p -> val(split(p.key().targetTable())[1], t.REFERENCED_TABLE),
            p -> val(p.key().referencedConstraintName(), t.REFERENCED_CONSTRAINT_NAME),
            p -> val(p.key().referencedPositions().get(p.position()), t.REFERENCED_POSITION),
            p -> val(touchedAt, t.TOUCHED_AT)));
        BindBatch.execute(dsl, rows, markers ->
            dsl.insertInto(t, t.SOURCE_NAME, t.TABLE_SCHEMA, t.TABLE_NAME, t.CONSTRAINT_NAME,
                    t.POSITION, t.REFERENCED_SOURCE_NAME, t.REFERENCED_SCHEMA, t.REFERENCED_TABLE,
                    t.REFERENCED_CONSTRAINT_NAME, t.REFERENCED_POSITION, t.TOUCHED_AT)
                .values(markers)
                .onConflict(t.SOURCE_NAME, t.TABLE_SCHEMA, t.TABLE_NAME, t.CONSTRAINT_NAME, t.POSITION)
                .doUpdate()
                .set(t.REFERENCED_SOURCE_NAME, excluded(t.REFERENCED_SOURCE_NAME))
                .set(t.REFERENCED_SCHEMA, excluded(t.REFERENCED_SCHEMA))
                .set(t.REFERENCED_TABLE, excluded(t.REFERENCED_TABLE))
                .set(t.REFERENCED_CONSTRAINT_NAME, excluded(t.REFERENCED_CONSTRAINT_NAME))
                .set(t.REFERENCED_POSITION, excluded(t.REFERENCED_POSITION))
                .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT)));
    }

    /**
     * How many foreign keys connect each ordered pair of tables, which is the fact a reader that
     * discovers a route rather than naming one asks for: exactly one key is a route it may take,
     * and two are an ambiguity it declines.
     *
     * <p>Counted here and not by each reader. Every asker was recounting the same aggregate over
     * the whole census on the inner side of its own join, so the count was paid once per driving
     * row for a value that varies with the catalog alone. Stated as its own relation rather than
     * as a column beside each key, which would repeat one value down every constraint of a pair.
     *
     * <p>Read out of the rows the stage above it just wrote rather than out of the catalog a
     * second time, which is what makes the count exactly the one the census holds: a key the
     * reference rows deduplicated is one key here too, where a second walk of the catalog would
     * have counted it twice.
     *
     * <p>Ordered, the declaring end and the referenced end not being interchangeable. A
     * self-referential key is one row, both ends being the same table.
     */
    private static void tableReferences(DSLContext dsl, Set<String> sources,
                                        LocalDateTime touchedAt) {
        var t = SQL_TABLE_REFERENCE;
        var rc = SQL_REFERENTIAL_CONSTRAINT;
        dsl.insertInto(t, t.SOURCE_NAME, t.TABLE_SCHEMA, t.TABLE_NAME, t.REFERENCED_SOURCE_NAME,
                t.REFERENCED_SCHEMA, t.REFERENCED_TABLE, t.CONSTRAINTS, t.TOUCHED_AT)
            .select(dsl
                .select(rc.SOURCE_NAME, rc.TABLE_SCHEMA, rc.TABLE_NAME, rc.REFERENCED_SOURCE_NAME,
                    rc.REFERENCED_SCHEMA, rc.REFERENCED_TABLE,
                    count().cast(INTEGER), val(touchedAt, t.TOUCHED_AT))
                .from(rc)
                .where(rc.SOURCE_NAME.in(sources))
                .groupBy(rc.SOURCE_NAME, rc.TABLE_SCHEMA, rc.TABLE_NAME, rc.REFERENCED_SOURCE_NAME,
                    rc.REFERENCED_SCHEMA, rc.REFERENCED_TABLE))
            .onDuplicateKeyUpdate()
            .set(t.CONSTRAINTS, excluded(t.CONSTRAINTS))
            .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
            .execute();
    }

    private static void indexes(DSLContext dsl, JooqCatalog jooq, List<TableAt> tables,
                                LocalDateTime touchedAt) {
        var t = SQL_INDEX;
        List<TableAt> owners = new ArrayList<>();
        List<JooqCatalog.IndexFacts> found = new ArrayList<>();
        for (TableAt at : tables) {
            for (JooqCatalog.IndexFacts index : jooq.indexFactsOf(at.table())) {
                owners.add(at);
                found.add(index);
            }
        }
        if (found.isEmpty()) {
            return;
        }
        var indexes = indices(found.size());
        var rows = indexes.stream().collect(Rows.toRowList(
            i -> val(owners.get(i).source(), t.SOURCE_NAME),
            i -> val(owners.get(i).schema(), t.TABLE_SCHEMA),
            i -> val(owners.get(i).name(), t.TABLE_NAME),
            i -> val(found.get(i).name(), t.INDEX_NAME),
            i -> val(touchedAt, t.TOUCHED_AT)));
        BindBatch.execute(dsl, rows, markers ->
            dsl.insertInto(t, t.SOURCE_NAME, t.TABLE_SCHEMA, t.TABLE_NAME, t.INDEX_NAME, t.TOUCHED_AT)
                .values(markers)
                .onDuplicateKeyUpdate()
                .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT)));
    }

    private static void indexColumns(DSLContext dsl, JooqCatalog jooq, List<TableAt> tables,
                                     LocalDateTime touchedAt) {
        var t = SQL_INDEX_COLUMN;
        List<PositionAt<TableAt>> named = new ArrayList<>();
        List<String> indexNames = new ArrayList<>();
        for (TableAt at : tables) {
            for (JooqCatalog.IndexFacts index : jooq.indexFactsOf(at.table())) {
                for (int i = 0; i < index.columns().size(); i++) {
                    named.add(new PositionAt<>(at, i, index.columns().get(i)));
                    indexNames.add(index.name());
                }
            }
        }
        if (named.isEmpty()) {
            return;
        }
        var indexes = indices(named.size());
        var rows = indexes.stream().collect(Rows.toRowList(
            i -> val(named.get(i).parent().source(), t.SOURCE_NAME),
            i -> val(named.get(i).parent().schema(), t.TABLE_SCHEMA),
            i -> val(named.get(i).parent().name(), t.TABLE_NAME),
            i -> val(indexNames.get(i), t.INDEX_NAME),
            i -> val(named.get(i).position(), t.POSITION),
            i -> val(named.get(i).value(), t.COLUMN_NAME),
            i -> val(touchedAt, t.TOUCHED_AT)));
        BindBatch.execute(dsl, rows, markers ->
            dsl.insertInto(t, t.SOURCE_NAME, t.TABLE_SCHEMA, t.TABLE_NAME, t.INDEX_NAME, t.POSITION,
                    t.COLUMN_NAME, t.TOUCHED_AT)
                .values(markers)
                .onDuplicateKeyUpdate()
                .set(t.COLUMN_NAME, excluded(t.COLUMN_NAME))
                .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT)));
    }

    /** One row of sql_table_field: a field of a table, its kind, generated name and value class. */
    private record FieldRow(TableAt table, String name, String kind, String jooqName, String bindingType) {}

    private static void tableFields(DSLContext dsl, List<FieldRow> fields, LocalDateTime touchedAt) {
        if (fields.isEmpty()) {
            return;
        }
        var t = SQL_TABLE_FIELD;
        var rows = indices(fields.size()).stream().collect(Rows.toRowList(
            i -> val(fields.get(i).table().source(), t.SOURCE_NAME),
            i -> val(fields.get(i).table().schema(), t.TABLE_SCHEMA),
            i -> val(fields.get(i).table().name(), t.TABLE_NAME),
            i -> val(fields.get(i).name(), t.FIELD_NAME),
            i -> val(fields.get(i).kind(), t.KIND),
            i -> val(fields.get(i).jooqName(), t.JOOQ_NAME),
            i -> val(fields.get(i).bindingType(), t.BINDING_TYPE),
            i -> val(touchedAt, t.TOUCHED_AT)));
        BindBatch.execute(dsl, rows, markers ->
            dsl.insertInto(t, t.SOURCE_NAME, t.TABLE_SCHEMA, t.TABLE_NAME, t.FIELD_NAME, t.KIND, t.JOOQ_NAME,
                    t.BINDING_TYPE, t.TOUCHED_AT)
                .values(markers)
                .onConflict(t.SOURCE_NAME, t.TABLE_SCHEMA, t.TABLE_NAME, t.FIELD_NAME)
                .doUpdate()
                .set(t.KIND, excluded(t.KIND))
                .set(t.JOOQ_NAME, excluded(t.JOOQ_NAME))
                .set(t.BINDING_TYPE, excluded(t.BINDING_TYPE))
                .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT)));
    }

    /** The EMBEDDABLE rows of sql_table_field, one per embeddable a table's generated class declares. */
    private static void embeddables(DSLContext dsl, JooqCatalog jooq, List<TableAt> tables,
                                    LocalDateTime touchedAt) {
        List<FieldRow> fields = new ArrayList<>();
        for (TableAt at : tables) {
            for (JooqCatalog.EmbeddableFacts embeddable : jooq.embeddableFactsOf(at.table())) {
                fields.add(new FieldRow(at, embeddable.name(), "EMBEDDABLE", embeddable.jooqName(),
                    embeddable.recordClass()));
            }
        }
        tableFields(dsl, fields, touchedAt);
    }

    private static void embeddableColumns(DSLContext dsl, JooqCatalog jooq, List<TableAt> tables,
                                          LocalDateTime touchedAt) {
        var t = SQL_EMBEDDABLE_COLUMN;
        List<PositionAt<TableAt>> named = new ArrayList<>();
        List<String> embeddableNames = new ArrayList<>();
        for (TableAt at : tables) {
            for (JooqCatalog.EmbeddableFacts embeddable : jooq.embeddableFactsOf(at.table())) {
                for (int i = 0; i < embeddable.columns().size(); i++) {
                    named.add(new PositionAt<>(at, i, embeddable.columns().get(i)));
                    embeddableNames.add(embeddable.name());
                }
            }
        }
        if (named.isEmpty()) {
            return;
        }
        var indexes = indices(named.size());
        var rows = indexes.stream().collect(Rows.toRowList(
            i -> val(named.get(i).parent().source(), t.SOURCE_NAME),
            i -> val(named.get(i).parent().schema(), t.TABLE_SCHEMA),
            i -> val(named.get(i).parent().name(), t.TABLE_NAME),
            i -> val(embeddableNames.get(i), t.EMBEDDABLE_NAME),
            i -> val(named.get(i).position(), t.POSITION),
            i -> val(named.get(i).value(), t.COLUMN_NAME),
            i -> val("EMBEDDABLE", t.KIND),
            i -> val(touchedAt, t.TOUCHED_AT)));
        BindBatch.execute(dsl, rows, markers ->
            dsl.insertInto(t, t.SOURCE_NAME, t.TABLE_SCHEMA, t.TABLE_NAME, t.EMBEDDABLE_NAME, t.POSITION,
                    t.COLUMN_NAME, t.KIND, t.TOUCHED_AT)
                .values(markers)
                .onDuplicateKeyUpdate()
                .set(t.COLUMN_NAME, excluded(t.COLUMN_NAME))
                .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT)));
    }

    /** Read once per table, since the reading is reflection and a failed attempt logs. */
    private static List<NodeMetadataAt> nodeMetadataOf(JooqCatalog jooq, List<TableAt> tables) {
        List<NodeMetadataAt> found = new ArrayList<>();
        for (TableAt at : tables) {
            var lookup = jooq.nodeMetadataOf(at.table());
            if (!(lookup instanceof JooqCatalog.NodeIdMetadataLookup.Absent)) {
                found.add(new NodeMetadataAt(at, lookup));
            }
        }
        return found;
    }

    private static void nodeMetadata(DSLContext dsl, List<NodeMetadataAt> nodeMetadata,
                                     LocalDateTime touchedAt) {
        var t = SQL_NODE_METADATA;
        List<TableAt> owners = new ArrayList<>();
        List<JooqCatalog.NodeIdMetadata> found = new ArrayList<>();
        for (NodeMetadataAt at : nodeMetadata) {
            if (at.lookup() instanceof JooqCatalog.NodeIdMetadataLookup.Present p) {
                owners.add(at.table());
                found.add(p.metadata());
            }
        }
        if (found.isEmpty()) {
            return;
        }
        var indexes = indices(found.size());
        var rows = indexes.stream().collect(Rows.toRowList(
            i -> val(owners.get(i).source(), t.SOURCE_NAME),
            i -> val(owners.get(i).schema(), t.TABLE_SCHEMA),
            i -> val(owners.get(i).name(), t.TABLE_NAME),
            i -> val(found.get(i).typeId(), t.TYPE_ID),
            i -> val(touchedAt, t.TOUCHED_AT)));
        BindBatch.execute(dsl, rows, markers ->
            dsl.insertInto(t, t.SOURCE_NAME, t.TABLE_SCHEMA, t.TABLE_NAME, t.TYPE_ID, t.TOUCHED_AT)
                .values(markers)
                .onDuplicateKeyUpdate()
                .set(t.TYPE_ID, excluded(t.TYPE_ID))
                .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT)));
    }

    private static void nodeKeyColumns(DSLContext dsl, List<NodeMetadataAt> nodeMetadata,
                                       LocalDateTime touchedAt) {
        var t = SQL_NODE_KEY_COLUMN;
        List<PositionAt<TableAt>> named = new ArrayList<>();
        for (NodeMetadataAt at : nodeMetadata) {
            if (at.lookup() instanceof JooqCatalog.NodeIdMetadataLookup.Present p) {
                var keys = p.metadata().keyColumns();
                for (int i = 0; i < keys.size(); i++) {
                    named.add(new PositionAt<>(at.table(), i, keys.get(i).sqlName()));
                }
            }
        }
        if (named.isEmpty()) {
            return;
        }
        var rows = named.stream().collect(Rows.toRowList(
            at -> val(at.parent().source(), t.SOURCE_NAME),
            at -> val(at.parent().schema(), t.TABLE_SCHEMA),
            at -> val(at.parent().name(), t.TABLE_NAME),
            at -> val(at.position(), t.POSITION),
            at -> val(at.value(), t.COLUMN_NAME),
            at -> val(touchedAt, t.TOUCHED_AT)));
        BindBatch.execute(dsl, rows, markers ->
            dsl.insertInto(t, t.SOURCE_NAME, t.TABLE_SCHEMA, t.TABLE_NAME, t.POSITION, t.COLUMN_NAME,
                    t.TOUCHED_AT)
                .values(markers)
                .onDuplicateKeyUpdate()
                .set(t.COLUMN_NAME, excluded(t.COLUMN_NAME))
                .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT)));
    }

    /**
     * Read once per table, beside the identity pair it needs: a class declaring the legacy array
     * with no usable pair is an attempt that failed, so the reading is asked of every table and not
     * only of those {@link #nodeMetadataOf} kept.
     */
    private static List<NodeLegacyKeyAt> nodeLegacyKeysOf(JooqCatalog jooq, List<TableAt> tables,
                                                          List<NodeMetadataAt> nodeMetadata) {
        Map<TableAt, JooqCatalog.NodeIdMetadataLookup> identities = new LinkedHashMap<>();
        for (NodeMetadataAt at : nodeMetadata) {
            identities.put(at.table(), at.lookup());
        }
        List<NodeLegacyKeyAt> found = new ArrayList<>();
        for (TableAt at : tables) {
            var identity = identities.getOrDefault(at, new JooqCatalog.NodeIdMetadataLookup.Absent());
            var lookup = jooq.nodeLegacyKeyOf(at.table(), identity);
            if (!(lookup instanceof JooqCatalog.NodeLegacyKeyLookup.Absent)) {
                found.add(new NodeLegacyKeyAt(at, lookup));
            }
        }
        return found;
    }

    private static void nodeLegacyKeyColumns(DSLContext dsl, List<NodeLegacyKeyAt> legacyKeys,
                                             LocalDateTime touchedAt) {
        var t = SQL_NODE_LEGACY_KEY_COLUMN;
        List<PositionAt<TableAt>> named = new ArrayList<>();
        for (NodeLegacyKeyAt at : legacyKeys) {
            if (at.lookup() instanceof JooqCatalog.NodeLegacyKeyLookup.Present p) {
                var keys = p.legacyKeyColumns();
                for (int i = 0; i < keys.size(); i++) {
                    named.add(new PositionAt<>(at.table(), i, keys.get(i).sqlName()));
                }
            }
        }
        if (named.isEmpty()) {
            return;
        }
        var rows = named.stream().collect(Rows.toRowList(
            at -> val(at.parent().source(), t.SOURCE_NAME),
            at -> val(at.parent().schema(), t.TABLE_SCHEMA),
            at -> val(at.parent().name(), t.TABLE_NAME),
            at -> val(at.position(), t.POSITION),
            at -> val(at.value(), t.COLUMN_NAME),
            at -> val(touchedAt, t.TOUCHED_AT)));
        BindBatch.execute(dsl, rows, markers ->
            dsl.insertInto(t, t.SOURCE_NAME, t.TABLE_SCHEMA, t.TABLE_NAME, t.POSITION, t.COLUMN_NAME,
                    t.TOUCHED_AT)
                .values(markers)
                .onDuplicateKeyUpdate()
                .set(t.COLUMN_NAME, excluded(t.COLUMN_NAME))
                .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT)));
    }

    private static void nodeLegacyKeyUnreadable(DSLContext dsl, List<NodeLegacyKeyAt> legacyKeys,
                                                LocalDateTime touchedAt) {
        var t = SQL_NODE_LEGACY_KEY_UNREADABLE;
        List<TableAt> attempted = new ArrayList<>();
        for (NodeLegacyKeyAt at : legacyKeys) {
            if (at.lookup() instanceof JooqCatalog.NodeLegacyKeyLookup.Malformed) {
                attempted.add(at.table());
            }
        }
        if (attempted.isEmpty()) {
            return;
        }
        var rows = attempted.stream().collect(Rows.toRowList(
            at -> val(at.source(), t.SOURCE_NAME),
            at -> val(at.schema(), t.TABLE_SCHEMA),
            at -> val(at.name(), t.TABLE_NAME),
            at -> val(touchedAt, t.TOUCHED_AT)));
        BindBatch.execute(dsl, rows, markers ->
            dsl.insertInto(t, t.SOURCE_NAME, t.TABLE_SCHEMA, t.TABLE_NAME, t.TOUCHED_AT)
                .values(markers)
                .onDuplicateKeyUpdate()
                .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT)));
    }

    private static void nodeMetadataUnreadable(DSLContext dsl, List<NodeMetadataAt> nodeMetadata,
                                               LocalDateTime touchedAt) {
        var t = SQL_NODE_METADATA_UNREADABLE;
        List<TableAt> attempted = new ArrayList<>();
        for (NodeMetadataAt at : nodeMetadata) {
            if (at.lookup() instanceof JooqCatalog.NodeIdMetadataLookup.Malformed) {
                attempted.add(at.table());
            }
        }
        if (attempted.isEmpty()) {
            return;
        }
        var rows = attempted.stream().collect(Rows.toRowList(
            at -> val(at.source(), t.SOURCE_NAME),
            at -> val(at.schema(), t.TABLE_SCHEMA),
            at -> val(at.name(), t.TABLE_NAME),
            at -> val(touchedAt, t.TOUCHED_AT)));
        BindBatch.execute(dsl, rows, markers ->
            dsl.insertInto(t, t.SOURCE_NAME, t.TABLE_SCHEMA, t.TABLE_NAME, t.TOUCHED_AT)
                .values(markers)
                .onDuplicateKeyUpdate()
                .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT)));
    }

    private static void routines(DSLContext dsl, JooqCatalog jooq, List<TableAt> tables,
                                 LocalDateTime touchedAt) {
        var t = SQL_ROUTINE;
        var functions = functionsOf(tables);
        if (functions.isEmpty()) {
            return;
        }
        var rows = functions.stream().collect(Rows.toRowList(
            at -> val(at.source(), t.SOURCE_NAME),
            at -> val(at.schema(), t.TABLE_SCHEMA),
            at -> val(at.name(), t.ROUTINE_NAME),
            at -> val("FUNCTION", t.ROUTINE_TYPE),
            at -> val(jooq.routineCallFactsOf(at.table()).routinesClassFqn(), t.ROUTINES_CLASS_FQN),
            at -> val(jooq.routineCallFactsOf(at.table()).methodName(), t.ROUTINES_METHOD_NAME),
            at -> val(touchedAt, t.TOUCHED_AT)));
        BindBatch.execute(dsl, rows, markers ->
            dsl.insertInto(t, t.SOURCE_NAME, t.TABLE_SCHEMA, t.ROUTINE_NAME, t.ROUTINE_TYPE,
                    t.ROUTINES_CLASS_FQN, t.ROUTINES_METHOD_NAME, t.TOUCHED_AT)
                .values(markers)
                .onDuplicateKeyUpdate()
                .set(t.ROUTINE_TYPE, excluded(t.ROUTINE_TYPE))
                .set(t.ROUTINES_CLASS_FQN, excluded(t.ROUTINES_CLASS_FQN))
                .set(t.ROUTINES_METHOD_NAME, excluded(t.ROUTINES_METHOD_NAME))
                .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT)));
    }

    private static void routineParameters(DSLContext dsl, JooqCatalog jooq, List<TableAt> tables,
                                          LocalDateTime touchedAt) {
        var t = SQL_ROUTINE_PARAMETER;
        List<TableAt> owners = new ArrayList<>();
        List<Integer> positions = new ArrayList<>();
        List<JooqCatalog.RoutineParamFacts> found = new ArrayList<>();
        for (TableAt at : functionsOf(tables)) {
            var parameters = jooq.routineCallFactsOf(at.table()).parameters();
            for (int i = 0; i < parameters.size(); i++) {
                owners.add(at);
                positions.add(i);
                found.add(parameters.get(i));
            }
        }
        if (found.isEmpty()) {
            return;
        }
        var indexes = indices(found.size());
        var rows = indexes.stream().collect(Rows.toRowList(
            i -> val(owners.get(i).source(), t.SOURCE_NAME),
            i -> val(owners.get(i).schema(), t.TABLE_SCHEMA),
            i -> val(owners.get(i).name(), t.ROUTINE_NAME),
            i -> val(positions.get(i), t.POSITION),
            i -> val(found.get(i).javaName(), t.JOOQ_NAME),
            i -> val(found.get(i).bindingType(), t.BINDING_TYPE),
            i -> val(touchedAt, t.TOUCHED_AT)));
        BindBatch.execute(dsl, rows, markers ->
            dsl.insertInto(t, t.SOURCE_NAME, t.TABLE_SCHEMA, t.ROUTINE_NAME, t.POSITION, t.JOOQ_NAME,
                    t.BINDING_TYPE, t.TOUCHED_AT)
                .values(markers)
                .onDuplicateKeyUpdate()
                .set(t.JOOQ_NAME, excluded(t.JOOQ_NAME))
                .set(t.BINDING_TYPE, excluded(t.BINDING_TYPE))
                .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT)));
    }

    /**
     * The routines that are no table: procedures and scalar or void functions, which the table census
     * cannot reach. A routine is written where its schema is one a table registered, the rows' foreign
     * keys needing it, and never over a table-valued function of the same name, whose rows the two
     * writers above own.
     */
    private static void executableRoutines(DSLContext dsl, JooqCatalog jooq, List<TableAt> tables,
                                           String entryName, LocalDateTime touchedAt) {
        Set<String> schemas = new LinkedHashSet<>();
        tables.forEach(at -> schemas.add(at.source() + " " + at.schema()));
        Set<String> tableValued = new LinkedHashSet<>();
        functionsOf(tables).forEach(at -> tableValued.add(at.source() + " " + at.schema() + " " + at.name()));
        // The catalog names a routine's schema package; its source is that package in this entry.
        var routines = jooq.executableRoutineFacts().stream()
            .filter(r -> schemas.contains(sourceName(entryName, r.schemaPackage()) + " " + r.schemaName()))
            .filter(r -> !tableValued.contains(sourceName(entryName, r.schemaPackage()) + " "
                + r.schemaName() + " " + r.sqlName()))
            .toList();
        if (routines.isEmpty()) {
            return;
        }
        var t = SQL_ROUTINE;
        var rows = routines.stream().collect(Rows.toRowList(
            r -> val(sourceName(entryName, r.schemaPackage()), t.SOURCE_NAME),
            r -> val(r.schemaName(), t.TABLE_SCHEMA),
            r -> val(r.sqlName(), t.ROUTINE_NAME),
            r -> val(r.returnsValue() ? "FUNCTION" : "PROCEDURE", t.ROUTINE_TYPE),
            r -> val(r.routinesClassFqn(), t.ROUTINES_CLASS_FQN),
            r -> val(r.methodName(), t.ROUTINES_METHOD_NAME),
            r -> val(touchedAt, t.TOUCHED_AT)));
        BindBatch.execute(dsl, rows, markers ->
            dsl.insertInto(t, t.SOURCE_NAME, t.TABLE_SCHEMA, t.ROUTINE_NAME, t.ROUTINE_TYPE,
                    t.ROUTINES_CLASS_FQN, t.ROUTINES_METHOD_NAME, t.TOUCHED_AT)
                .values(markers)
                .onDuplicateKeyUpdate()
                .set(t.ROUTINE_TYPE, excluded(t.ROUTINE_TYPE))
                .set(t.ROUTINES_CLASS_FQN, excluded(t.ROUTINES_CLASS_FQN))
                .set(t.ROUTINES_METHOD_NAME, excluded(t.ROUTINES_METHOD_NAME))
                .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT)));

        var p = SQL_ROUTINE_PARAMETER;
        List<JooqCatalog.ExecutableRoutineFacts> owners = new ArrayList<>();
        List<Integer> positions = new ArrayList<>();
        List<JooqCatalog.ExecutableParamFacts> found = new ArrayList<>();
        for (var routine : routines) {
            for (int i = 0; i < routine.parameters().size(); i++) {
                owners.add(routine);
                positions.add(i);
                found.add(routine.parameters().get(i));
            }
        }
        if (found.isEmpty()) {
            return;
        }
        var parameterRows = indices(found.size()).stream().collect(Rows.toRowList(
            i -> val(sourceName(entryName, owners.get(i).schemaPackage()), p.SOURCE_NAME),
            i -> val(owners.get(i).schemaName(), p.TABLE_SCHEMA),
            i -> val(owners.get(i).sqlName(), p.ROUTINE_NAME),
            i -> val(positions.get(i), p.POSITION),
            i -> val(found.get(i).javaName(), p.JOOQ_NAME),
            i -> val(found.get(i).bindingType(), p.BINDING_TYPE),
            i -> val(found.get(i).sqlName(), p.SQL_NAME),
            i -> val(touchedAt, p.TOUCHED_AT)));
        BindBatch.execute(dsl, parameterRows, markers ->
            dsl.insertInto(p, p.SOURCE_NAME, p.TABLE_SCHEMA, p.ROUTINE_NAME, p.POSITION, p.JOOQ_NAME,
                    p.BINDING_TYPE, p.SQL_NAME, p.TOUCHED_AT)
                .values(markers)
                .onDuplicateKeyUpdate()
                .set(p.JOOQ_NAME, excluded(p.JOOQ_NAME))
                .set(p.BINDING_TYPE, excluded(p.BINDING_TYPE))
                .set(p.SQL_NAME, excluded(p.SQL_NAME))
                .set(p.TOUCHED_AT, excluded(p.TOUCHED_AT)));
    }

    /**
     * The database enum a bound Java enum stands for, read off its first constant: jOOQ generates
     * every constant as an {@link EnumType}, which carries the catalog coordinate the class name
     * alone does not. Null where the type is not one of those, which is guarded because
     * {@code getEnumConstants} can return an empty array rather than because a catalog reaches it.
     */
    private static EnumType enumTypeOf(Field<?> field) {
        Object[] constants = field.getType().getEnumConstants();
        return constants != null && constants.length > 0 && constants[0] instanceof EnumType type
            ? type
            : null;
    }

    /** The schema the bound database enum lives in, or null where the binding names no coordinate. */
    private static String enumSchemaOf(Field<?> field) {
        EnumType type = enumTypeOf(field);
        return type == null || type.getSchema() == null ? null : type.getSchema().getName();
    }

    /** The bound database enum's own name, or null where the binding names no coordinate. */
    private static String enumTypeNameOf(Field<?> field) {
        EnumType type = enumTypeOf(field);
        return type == null ? null : type.getName();
    }

    /**
     * Which graph read the sources this family is keyed by.
     *
     * <p>Two rows per catalog source, in the order their keys require. The registry row says the
     * source exists at all, which every source-keyed fact hangs a foreign key on and which the
     * membership row below references in turn.
     *
     * <p>The {@code sql_} family keys on the source and not on the graph, so a graph-scoped reader
     * reaches it through this relation, and every view over the catalog joins it. The gatherers that
     * write through {@link no.sikt.graphitron.model.sink.FactSink} get the row for free, the sink
     * stamping the graph on everything it writes; this one writes through the {@code DSLContext} and
     * so has to say it itself. Absent, the catalog is captured and every graph-scoped view over it
     * returns nothing, which reads as a capture that did not run.
     */
    private static void graphSources(DSLContext dsl, String graphName, List<String> sources,
                                     LocalDateTime touchedAt, boolean read) {
        var s = STORE_SOURCE;
        if (read) {
            var registry = sources.stream().collect(Rows.toRowList(
                source -> val(source, s.SOURCE_NAME),
                source -> val(JOOQ_SCHEMA, s.SOURCE_KIND),
                source -> val(touchedAt, s.LAST_SEEN),
                source -> val(touchedAt, s.READ_AT)));
            BindBatch.execute(dsl, registry, markers ->
                dsl.insertInto(s, s.SOURCE_NAME, s.SOURCE_KIND, s.LAST_SEEN, s.READ_AT)
                    .values(markers)
                    .onDuplicateKeyUpdate()
                    .set(s.SOURCE_KIND, excluded(s.SOURCE_KIND))
                    .set(s.LAST_SEEN, excluded(s.LAST_SEEN))
                    .set(s.READ_AT, excluded(s.READ_AT)));
        } else {
            // Seen, not read: the row's read_at stays the reading the stamp vouches for.
            dsl.update(s).set(s.LAST_SEEN, touchedAt).where(s.SOURCE_NAME.in(sources)).execute();
        }

        if (graphName == null) {
            return;
        }
        var t = STORE_GRAPH_SOURCE;
        var membership = sources.stream().collect(Rows.toRowList(
            source -> val(graphName, t.GRAPH_NAME),
            source -> val(source, t.SOURCE_NAME)));
        BindBatch.execute(dsl, membership, markers ->
            dsl.insertInto(t, t.GRAPH_NAME, t.SOURCE_NAME)
                .values(markers)
                .onDuplicateKeyIgnore());
    }

    /**
     * The catalog sources this graph has stopped claiming: a package no longer configured, one the
     * catalog no longer has a table under, or every one of them where no package is configured.
     *
     * <p>Scoped to the kind this gatherer owns, the schema files and classpath entries having
     * memberships in the same relation that a reading of the catalog has nothing to say about.
     * Only the claim goes. The source and its {@code sql_} rows are what the store holds for that
     * package whoever reads it, so they stay until the registry drops the source; this graph just
     * stops seeing them.
     */
    private static void dropMembership(DSLContext dsl, String graphName, Set<String> claimed) {
        if (graphName == null) {
            return;
        }
        var m = STORE_GRAPH_SOURCE;
        var s = STORE_SOURCE;
        var scope = m.GRAPH_NAME.eq(graphName)
            .and(m.SOURCE_NAME.in(dsl.select(s.SOURCE_NAME).from(s)
                .where(s.SOURCE_KIND.eq(JOOQ_SCHEMA))));
        if (!claimed.isEmpty()) {
            scope = scope.and(m.SOURCE_NAME.notIn(claimed));
        }
        dsl.deleteFrom(m).where(scope).execute();
    }

    /**
     * Every type a table's generated record <em>is</em>, itself excluded: the superclass chain and
     * every interface reachable from it.
     *
     * <p>Captured rather than derived, and that is the one thing to know before reading it. The
     * chain above a generated record runs through jOOQ's own runtime classes, which a graph's
     * census reads only where the build declares the jOOQ jar, so the code family's edges may stop
     * at the jar's edge. This walk holds the record {@link Class} itself, so it is the only reader
     * that can state the closure whatever the classpath holds.
     */
    private static void recordSupertypes(DSLContext dsl, List<TableAt> tables) {
        var t = SQL_TABLE_RECORD_SUPERTYPE;
        record Supertype(TableAt at, String name) {}
        var found = new ArrayList<Supertype>();
        for (TableAt at : tables) {
            Class<?> recordClass = at.table().getRecordType();
            if (recordClass == null || recordClass.getName().equals("org.jooq.Record")) {
                continue;
            }
            var seen = new LinkedHashSet<Class<?>>();
            collectSupertypes(recordClass, seen);
            seen.remove(recordClass);
            seen.forEach(supertype -> found.add(new Supertype(at, supertype.getName())));
        }
        var rows = found.stream().collect(Rows.toRowList(
            found1 -> val(found1.at().source(), t.SOURCE_NAME),
            found1 -> val(found1.at().schema(), t.TABLE_SCHEMA),
            found1 -> val(found1.at().name(), t.TABLE_NAME),
            found1 -> val(found1.name(), t.SUPERTYPE_NAME)));
        // Every column is a key column, so a row that is still true is the row that is already
        // there and there is nothing on it to update.
        BindBatch.execute(dsl, rows, markers ->
            dsl.insertInto(t, t.SOURCE_NAME, t.TABLE_SCHEMA, t.TABLE_NAME, t.SUPERTYPE_NAME)
                .values(markers)
                .onDuplicateKeyIgnore());
    }

    /** The closure above one class: its interfaces, their interfaces, and its superclass chain. */
    private static void collectSupertypes(Class<?> cls, Set<Class<?>> out) {
        if (cls == null || !out.add(cls)) {
            return;
        }
        for (Class<?> each : cls.getInterfaces()) {
            collectSupertypes(each, out);
        }
        collectSupertypes(cls.getSuperclass(), out);
    }

    /**
     * The getter each column's generated record reads it through, the property Bean Validation
     * names it by. The name is the one jOOQ's default generator strategy writes ({@link RecordGetters#getterName}),
     * kept only where the live record class declares it, so a strategy that renames getters leaves
     * its columns without a row rather than with a guessed one.
     */
    private static void recordAccessors(DSLContext dsl, JooqCatalog jooq, List<TableAt> tables,
                                        LocalDateTime touchedAt) {
        var t = SQL_COLUMN_RECORD_ACCESSOR;
        record Accessor(TableAt at, String column, String getter) {}
        var found = new ArrayList<Accessor>();
        for (TableAt at : tables) {
            Class<?> recordClass = at.table().getRecordType();
            if (recordClass == null || recordClass.getName().equals("org.jooq.Record")) {
                continue;
            }
            // The columns sql_column holds, which is what the foreign key references.
            var captured = new LinkedHashSet<String>();
            jooq.columnFactsOf(at.table()).forEach(column -> captured.add(column.sqlName()));
            for (Field<?> field : at.table().fields()) {
                if (!captured.contains(field.getName())) {
                    continue;
                }
                recordGetter(recordClass, field.getName(), field.getType())
                    .ifPresent(getter -> found.add(new Accessor(at, field.getName(), getter)));
            }
        }
        if (found.isEmpty()) {
            return;
        }
        var rows = found.stream().collect(Rows.toRowList(
            a -> val(a.at().source(), t.SOURCE_NAME),
            a -> val(a.at().schema(), t.TABLE_SCHEMA),
            a -> val(a.at().name(), t.TABLE_NAME),
            a -> val(a.column(), t.COLUMN_NAME),
            a -> val(a.getter(), t.GETTER_NAME),
            a -> val(RecordGetters.propertyName(a.getter()), t.PROPERTY_NAME),
            a -> val(touchedAt, t.TOUCHED_AT)));
        BindBatch.execute(dsl, rows, markers ->
            dsl.insertInto(t, t.SOURCE_NAME, t.TABLE_SCHEMA, t.TABLE_NAME, t.COLUMN_NAME, t.GETTER_NAME,
                    t.PROPERTY_NAME, t.TOUCHED_AT)
                .values(markers)
                .onDuplicateKeyUpdate()
                .set(t.GETTER_NAME, excluded(t.GETTER_NAME))
                .set(t.PROPERTY_NAME, excluded(t.PROPERTY_NAME))
                .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT)));
    }

    /**
     * The getter {@code recordClass} reads the column {@code sqlName} through, where it declares the
     * name jOOQ's default strategy writes as a public no-argument instance method returning a type the
     * column's Java type {@code columnType} is assignable to. The return-type check is also what
     * rejects a name jOOQ disambiguated against a method {@link org.jooq.Record} already has.
     */
    static java.util.Optional<String> recordGetter(Class<?> recordClass, String sqlName, Class<?> columnType) {
        String name = RecordGetters.getterName(sqlName);
        try {
            var method = recordClass.getMethod(name);
            if (java.lang.reflect.Modifier.isStatic(method.getModifiers())
                    || !method.getReturnType().isAssignableFrom(columnType)) {
                return java.util.Optional.empty();
            }
            return java.util.Optional.of(name);
        } catch (NoSuchMethodException e) {
            return java.util.Optional.empty();
        }
    }

    /** The Java enum a column binds to, which is a fact about the generated code and not the table. */
    private static void enumBindings(DSLContext dsl, List<TableAt> tables, LocalDateTime touchedAt) {
        var t = SQL_ENUM_BINDING;
        Map<String, TableAt> byClass = new LinkedHashMap<>();
        Map<String, Field<?>> fieldByClass = new LinkedHashMap<>();
        for (TableAt at : tables) {
            for (Field<?> field : at.table().fields()) {
                if (field.getType().isEnum()) {
                    String key = at.source() + " " + field.getType().getName();
                    byClass.putIfAbsent(key, at);
                    fieldByClass.putIfAbsent(key, field);
                }
            }
        }
        if (byClass.isEmpty()) {
            return;
        }
        var keys = new ArrayList<>(byClass.keySet());
        var rows = keys.stream().collect(Rows.toRowList(
            key -> val(byClass.get(key).source(), t.SOURCE_NAME),
            key -> val(fieldByClass.get(key).getType().getName(), t.CLASS_FQN),
            key -> val(enumSchemaOf(fieldByClass.get(key)), t.TABLE_SCHEMA),
            key -> val(enumTypeNameOf(fieldByClass.get(key)), t.TYPE_NAME),
            key -> val(touchedAt, t.TOUCHED_AT)));
        BindBatch.execute(dsl, rows, markers ->
            dsl.insertInto(t, t.SOURCE_NAME, t.CLASS_FQN, t.TABLE_SCHEMA, t.TYPE_NAME, t.TOUCHED_AT)
                .values(markers)
                .onDuplicateKeyUpdate()
                .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT)));
        enumLiterals(dsl, keys.stream().map(key -> new EnumAt(byClass.get(key).source(), fieldByClass.get(key).getType()))
            .toList(), touchedAt);
    }

    /** A bound enum class and the source it was reached through. */
    private record EnumAt(String source, Class<?> enumClass) {}

    /** One constant of a bound enum: its class, its name, the value the database stores, its position. */
    private record Literal(String source, String classFqn, String constantName, String literal, int ordinal) {}

    /**
     * Each bound enum's constants, with the value the database stores for each: the literal of a
     * jOOQ-generated {@link EnumType}, the name of a converter-bound Java enum.
     */
    private static void enumLiterals(DSLContext dsl, List<EnumAt> enums, LocalDateTime touchedAt) {
        var t = SQL_ENUM_BINDING_LITERAL;
        var literals = new ArrayList<Literal>();
        for (EnumAt at : enums) {
            for (Object constant : at.enumClass().getEnumConstants()) {
                var e = (Enum<?>) constant;
                literals.add(new Literal(at.source(), at.enumClass().getName(), e.name(),
                    constant instanceof EnumType type ? type.getLiteral() : e.name(), e.ordinal()));
            }
        }
        if (literals.isEmpty()) {
            return;
        }
        var rows = literals.stream().collect(Rows.toRowList(
            l -> val(l.source(), t.SOURCE_NAME),
            l -> val(l.classFqn(), t.CLASS_FQN),
            l -> val(l.constantName(), t.CONSTANT_NAME),
            l -> val(l.literal(), t.LITERAL),
            l -> val(l.ordinal(), t.ORDINAL),
            l -> val(touchedAt, t.TOUCHED_AT)));
        BindBatch.execute(dsl, rows, markers ->
            dsl.insertInto(t, t.SOURCE_NAME, t.CLASS_FQN, t.CONSTANT_NAME, t.LITERAL, t.ORDINAL, t.TOUCHED_AT)
                .values(markers)
                .onConflict(t.SOURCE_NAME, t.CLASS_FQN, t.CONSTANT_NAME)
                .doUpdate()
                .set(t.LITERAL, excluded(t.LITERAL))
                .set(t.ORDINAL, excluded(t.ORDINAL))
                .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT)));
    }

    // ------------------------------------------------------------------------------ mark and sweep

    /**
     * Every relation this gatherer writes, parents first. Listed rather than found by prefix: a
     * relation added above and not here would keep its stale rows silently.
     */
    private static final List<Table<?>> TABLES_TO_SWEEP = List.of(
        SQL_SCHEMA, SQL_TABLE, SQL_TABLE_FIELD, SQL_COLUMN, SQL_COLUMN_RECORD_ACCESSOR, SQL_CONSTRAINT, SQL_CONSTRAINT_COLUMN, SQL_PRIMARY_KEY,
        SQL_REFERENTIAL_CONSTRAINT, SQL_REFERENTIAL_CONSTRAINT_COLUMN, SQL_TABLE_REFERENCE, SQL_INDEX, SQL_INDEX_COLUMN,
        SQL_EMBEDDABLE_COLUMN, SQL_NODE_METADATA,
        SQL_NODE_KEY_COLUMN, SQL_NODE_LEGACY_KEY_COLUMN, SQL_NODE_METADATA_UNREADABLE,
        SQL_NODE_LEGACY_KEY_UNREADABLE, SQL_ROUTINE, SQL_ROUTINE_PARAMETER, SQL_ENUM_BINDING, SQL_ENUM_BINDING_LITERAL);

    /**
     * Deletes the rows of the sources this reading walked that it did not touch, which are the
     * tables, columns and keys the consumer's database no longer has.
     *
     * <p>Reversed, because these relations reference one another: a table deleted before its
     * columns is a foreign key violation, and the same list read backwards cannot produce one.
     */
    private static void sweep(DSLContext dsl, Set<String> sources, LocalDateTime touchedAt) {
        for (Table<?> table : TABLES_TO_SWEEP.reversed()) {
            // Table.field(Field) is a lookup by name returning this table's own typed column.
            var named = SQL_TABLE;
            dsl.deleteFrom(table)
                .where(table.field(named.SOURCE_NAME).in(sources))
                .and(table.field(named.TOUCHED_AT).isNull()
                    .or(table.field(named.TOUCHED_AT).ne(touchedAt)))
                .execute();
        }
    }

    // ----------------------------------------------------------------- reading the catalog's parts

    private static List<TableAt> tablesOf(JooqCatalog jooq, String entryName) {
        List<TableAt> tables = new ArrayList<>();
        for (JooqCatalog.TableEntry entry : jooq.allTableEntries()) {
            Table<?> table = entry.table();
            tables.add(new TableAt(
                sourceName(entryName, JooqCatalog.schemaPackageOf(table.getSchema()).orElse("")),
                schemaOf(table), table.getName(), table, entry.javaFieldName()));
        }
        return tables;
    }

    /**
     * Every unique key of every table as a constraint, then every foreign key. One relation with
     * two sources, which is the grammar of a constraint rather than a merge of two ideas.
     */
    private static List<ConstraintAt> constraintsOf(JooqCatalog jooq, List<TableAt> tables) {
        List<ConstraintAt> constraints = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (TableAt at : tables) {
            UniqueKey<?> primary = at.table().getPrimaryKey();
            var keys = new LinkedHashSet<UniqueKey<?>>(at.table().getKeys());
            if (primary != null) {
                keys.add(primary);
            }
            int keyPosition = 0;
            for (UniqueKey<?> key : keys) {
                if (!seen.add(at.source() + " " + at.schema() + " " + at.name() + " " + key.getName())) {
                    continue;
                }
                constraints.add(new ConstraintAt(at, key.getName(),
                    key.equals(primary) ? "PRIMARY KEY" : "UNIQUE",
                    jooq.keyJavaConstantName(key).orElse(null), keyPosition++,
                    key.getFields().stream().map(Field::getName).toList()));
            }
            for (JooqCatalog.ForeignKeyFacts key : jooq.foreignKeyFactsOf(at.table())) {
                if (!seen.add(at.source() + " " + at.schema() + " " + at.name() + " " + key.constraintName())) {
                    continue;
                }
                constraints.add(new ConstraintAt(at, key.constraintName(), "FOREIGN KEY",
                    key.jooqName(), null, key.columns()));
            }
        }
        return constraints;
    }

    private static List<TableAt> schemasOf(List<TableAt> tables) {
        Map<String, TableAt> byKey = new LinkedHashMap<>();
        for (TableAt at : tables) {
            if (at.table().getSchema() != null) {
                byKey.putIfAbsent(at.source() + " " + at.schema(), at);
            }
        }
        return new ArrayList<>(byKey.values());
    }

    private static List<TableAt> functionsOf(List<TableAt> tables) {
        return tables.stream().filter(at -> at.table().getTableType().isFunction()).toList();
    }

    private static Set<String> sourceNames(List<TableAt> tables) {
        Set<String> sources = new LinkedHashSet<>();
        tables.forEach(at -> sources.add(at.source()));
        return sources;
    }

    private static Map<String, String> sourceByTable(List<TableAt> tables) {
        Map<String, String> byTable = new LinkedHashMap<>();
        tables.forEach(at -> byTable.put(at.schema() + "." + at.name(), at.source()));
        return byTable;
    }

    private static Map<String, Integer> declaredPositions(Table<?> table) {
        Map<String, Integer> positions = new LinkedHashMap<>();
        Field<?>[] declared = table.fields();
        for (int i = 0; i < declared.length; i++) {
            positions.put(declared[i].getName(), i);
        }
        return positions;
    }

    /**
     * The package of the catalog class a schema belongs to, the one a graph's {@code jooqPackage}
     * names; null where the schema has no catalog.
     */
    private static String catalogPackageOf(Schema schema) {
        var catalog = schema.getCatalog();
        return catalog == null ? null : catalog.getClass().getPackageName();
    }

    private static String schemaOf(Table<?> table) {
        return table.getSchema() == null ? "" : table.getSchema().getName();
    }

    /** A qualified table name as its schema and its table, the way the catalog spells it. */
    private static String[] split(String qualified) {
        int dot = qualified.indexOf('.');
        return dot < 0 ? new String[] {"", qualified}
            : new String[] {qualified.substring(0, dot), qualified.substring(dot + 1)};
    }

    private static List<Integer> indices(int size) {
        List<Integer> indexes = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            indexes.add(i);
        }
        return indexes;
    }

    private static String nullIfBlank(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
