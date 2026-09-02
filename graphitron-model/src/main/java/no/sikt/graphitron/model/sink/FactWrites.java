package no.sikt.graphitron.model.sink;

import org.jooq.DSLContext;
import org.jooq.Table;
import org.jooq.TableRecord;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_ARGUMENT_BINDING;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ARGUMENT_REFERENCE_FOR_STEP;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ARGUMENT_REFERENCE_STEP;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_BINDING;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_REFERENCE_STEP;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_MUTATION;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_SPELLED_REFERENCE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_REFERENCE_FOR_STEP;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ROUTINE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_TABLE;
import static no.sikt.graphitron.model.Tables.GRAPHQL_ARGUMENT;
import static no.sikt.graphitron.model.Tables.JVM_METHOD;
import static no.sikt.graphitron.model.Tables.GRAPHQL_FIELD;
import static no.sikt.graphitron.model.Tables.SQL_COLUMN;
import static no.sikt.graphitron.model.Tables.SQL_ENUM_BINDING;
import static no.sikt.graphitron.model.Tables.SQL_CONSTRAINT;
import static no.sikt.graphitron.model.Tables.SQL_TABLE;

/**
 * The written half of capture's write path: one function per relation, each rendering its own bulk
 * insert and naming the columns it has data for.
 *
 * <p>An insert should say what it writes, in constants the compiler checks, so a column the writer
 * has nothing for cannot join it later without someone deciding that it should. {@link
 * FactSink#flush()} reaches the same answer for a relation with no function here by taking the
 * columns its rows actually touched, which is what lets a relation carrying a column the database
 * computes go through the generic arm at all; what it cannot do is state the list somewhere a
 * reader of the writer can see it.
 *
 * <p>Each function issues one prepared statement bound once per row, which is the property the
 * sink's own batch has and the load's cost depends on. Conflict behaviour is stated per relation
 * rather than inferred from the relation's name prefix: the catalog ones ignore a duplicate key,
 * because two builds crawling one jar concurrently both land, and the graph-keyed rest do not,
 * because there a duplicate is a capture bug the constraint must surface.
 *
 * <p>This covers the relations that carry a generated column today, which were the relations the
 * generic arm could not write before it learned to name what it wrote. Everything else still goes
 * through that arm, so the two coexist and the write order has to span both; see
 * {@link FactSink#parentsFirst}.
 */
final class FactWrites {

    private FactWrites() {
    }

    /** How one relation's buffered rows become a statement. */
    @FunctionalInterface
    interface RelationWriter {
        void write(DSLContext dsl, List<TableRecord<?>> rows);
    }

    private static final Map<Table<?>, RelationWriter> WRITERS = registry();

    /**
     * The write function for {@code table}, or {@code null} where the relation has none and the
     * sink's generic arm renders it.
     */
    static RelationWriter of(Table<?> table) {
        return WRITERS.get(table);
    }

    /**
     * A row of {@code count} bind markers. The statement is prepared once and bound per row, so the
     * values placed here are never sent; the row's arity is what the render needs.
     */
    private static List<Object> markers(int count) {
        return Collections.nCopies(count, null);
    }

    private static Map<Table<?>, RelationWriter> registry() {
        Map<Table<?>, RelationWriter> writers = new HashMap<>();
        writers.put(GRAPHITRON_TABLE, FactWrites::graphitronTable);
        writers.put(GRAPHITRON_FIELD_BINDING, FactWrites::graphitronFieldBinding);
        writers.put(GRAPHITRON_ARGUMENT_BINDING, FactWrites::graphitronArgumentBinding);
        writers.put(GRAPHITRON_FIELD_REFERENCE_STEP, FactWrites::graphitronFieldReferenceStep);
        writers.put(GRAPHITRON_ARGUMENT_REFERENCE_STEP, FactWrites::graphitronArgumentReferenceStep);
        writers.put(GRAPHITRON_REFERENCE_FOR_STEP, FactWrites::graphitronReferenceForStep);
        writers.put(GRAPHITRON_ARGUMENT_REFERENCE_FOR_STEP, FactWrites::graphitronArgumentReferenceForStep);
        writers.put(GRAPHITRON_MUTATION, FactWrites::graphitronMutation);
        writers.put(GRAPHITRON_ROUTINE, FactWrites::graphitronRoutine);
        writers.put(GRAPHQL_FIELD, FactWrites::graphqlField);
        writers.put(GRAPHQL_ARGUMENT, FactWrites::graphqlArgument);
        writers.put(SQL_TABLE, FactWrites::sqlTable);
        writers.put(SQL_CONSTRAINT, FactWrites::sqlConstraint);
        writers.put(SQL_COLUMN, FactWrites::sqlColumn);
        writers.put(SQL_ENUM_BINDING, FactWrites::sqlEnumBinding);
        writers.put(GRAPHITRON_SPELLED_REFERENCE, FactWrites::graphitronSpelledReference);
        writers.put(JVM_METHOD, FactWrites::jvmMethod);
        return writers;
    }

    /**
     * Here rather than on the sink's generic arm because the relation gained a computed column, and
     * an insert that so much as names one is rejected. Ignores a duplicate key, which is the census
     * disposition rather than a choice made for this relation: the classpath is crawled per source
     * and two crawls of one jar both land, exactly as the catalog writers below describe.
     */
    private static void jvmMethod(DSLContext dsl, List<TableRecord<?>> rows) {
        var t = JVM_METHOD;
        var batch = dsl.batch((dsl.insertInto(t)
                .columns(t.SOURCE_NAME,
                         t.CLASS_NAME,
                         t.METHOD_NAME,
                         t.DESCRIPTOR,
                         t.RETURN_TYPE,
                         t.DECLARED_RETURN_TYPE,
                         t.RETURNS_CONDITION)
                .values(markers(7)))
                .onDuplicateKeyIgnore());
        for (TableRecord<?> row : rows) {
            batch = batch.bind(row.get(t.SOURCE_NAME),
                               row.get(t.CLASS_NAME),
                               row.get(t.METHOD_NAME),
                               row.get(t.DESCRIPTOR),
                               row.get(t.RETURN_TYPE),
                               row.get(t.DECLARED_RETURN_TYPE),
                               row.get(t.RETURNS_CONDITION));
        }
        batch.execute();
    }

    private static void graphitronSpelledReference(DSLContext dsl, List<TableRecord<?>> rows) {
        var t = GRAPHITRON_SPELLED_REFERENCE;
        var batch = dsl.batch(dsl.insertInto(t)
                .columns(t.GRAPH_NAME,
                         t.SPELLING,
                         t.NAMESPACE_PART,
                         t.NAME_PART)
                .values(markers(4)));
        for (TableRecord<?> row : rows) {
            batch = batch.bind(row.get(t.GRAPH_NAME),
                               row.get(t.SPELLING),
                               row.get(t.NAMESPACE_PART),
                               row.get(t.NAME_PART));
        }
        batch.execute();
    }

    private static void graphitronTable(DSLContext dsl, List<TableRecord<?>> rows) {
        var t = GRAPHITRON_TABLE;
        var batch = dsl.batch(dsl.insertInto(t)
                .columns(t.GRAPH_NAME,
                         t.TYPE_NAME,
                         t.SOURCE_NAME,
                         t.DECLARATION_LINE,
                         t.DECLARATION_COLUMN,
                         t.SOURCE_LINE,
                         t.SOURCE_COLUMN,
                         t.TABLE_REF,
                         t.TABLE_REF_NAMESPACE_PART,
                         t.TABLE_REF_NAME_PART)
                .values(markers(10)));
        for (TableRecord<?> row : rows) {
            batch = batch.bind(row.get(t.GRAPH_NAME),
                               row.get(t.TYPE_NAME),
                               row.get(t.SOURCE_NAME),
                               row.get(t.DECLARATION_LINE),
                               row.get(t.DECLARATION_COLUMN),
                               row.get(t.SOURCE_LINE),
                               row.get(t.SOURCE_COLUMN),
                               row.get(t.TABLE_REF),
                               row.get(t.TABLE_REF_NAMESPACE_PART),
                               row.get(t.TABLE_REF_NAME_PART));
        }
        batch.execute();
    }

    private static void graphitronFieldBinding(DSLContext dsl, List<TableRecord<?>> rows) {
        var t = GRAPHITRON_FIELD_BINDING;
        var batch = dsl.batch(dsl.insertInto(t)
                .columns(t.GRAPH_NAME,
                         t.TYPE_NAME,
                         t.FIELD_NAME,
                         t.SOURCE_NAME,
                         t.SOURCE_LINE,
                         t.SOURCE_COLUMN,
                         t.NAME_REF)
                .values(markers(7)));
        for (TableRecord<?> row : rows) {
            batch = batch.bind(row.get(t.GRAPH_NAME),
                               row.get(t.TYPE_NAME),
                               row.get(t.FIELD_NAME),
                               row.get(t.SOURCE_NAME),
                               row.get(t.SOURCE_LINE),
                               row.get(t.SOURCE_COLUMN),
                               row.get(t.NAME_REF));
        }
        batch.execute();
    }

    private static void graphitronArgumentBinding(DSLContext dsl, List<TableRecord<?>> rows) {
        var t = GRAPHITRON_ARGUMENT_BINDING;
        var batch = dsl.batch(dsl.insertInto(t)
                .columns(t.GRAPH_NAME,
                         t.TYPE_NAME,
                         t.FIELD_NAME,
                         t.ARGUMENT_NAME,
                         t.SOURCE_NAME,
                         t.SOURCE_LINE,
                         t.SOURCE_COLUMN,
                         t.NAME_REF)
                .values(markers(8)));
        for (TableRecord<?> row : rows) {
            batch = batch.bind(row.get(t.GRAPH_NAME),
                               row.get(t.TYPE_NAME),
                               row.get(t.FIELD_NAME),
                               row.get(t.ARGUMENT_NAME),
                               row.get(t.SOURCE_NAME),
                               row.get(t.SOURCE_LINE),
                               row.get(t.SOURCE_COLUMN),
                               row.get(t.NAME_REF));
        }
        batch.execute();
    }

    private static void graphitronFieldReferenceStep(DSLContext dsl, List<TableRecord<?>> rows) {
        var t = GRAPHITRON_FIELD_REFERENCE_STEP;
        var batch = dsl.batch(dsl.insertInto(t)
                .columns(t.GRAPH_NAME,
                         t.TYPE_NAME,
                         t.FIELD_NAME,
                         t.ORDINAL,
                         t.POSITION,
                         t.TABLE_REF,
                         t.TABLE_REF_NAMESPACE_PART,
                         t.TABLE_REF_NAME_PART,
                         t.KEY_REF,
                         t.KEY_REF_NAMESPACE_PART,
                         t.KEY_REF_NAME_PART,
                         t.CLASS_NAME,
                         t.METHOD,
                         t.ARGMAPPING)
                .values(markers(14)));
        for (TableRecord<?> row : rows) {
            batch = batch.bind(row.get(t.GRAPH_NAME),
                               row.get(t.TYPE_NAME),
                               row.get(t.FIELD_NAME),
                               row.get(t.ORDINAL),
                               row.get(t.POSITION),
                               row.get(t.TABLE_REF),
                               row.get(t.TABLE_REF_NAMESPACE_PART),
                               row.get(t.TABLE_REF_NAME_PART),
                               row.get(t.KEY_REF),
                               row.get(t.KEY_REF_NAMESPACE_PART),
                               row.get(t.KEY_REF_NAME_PART),
                               row.get(t.CLASS_NAME),
                               row.get(t.METHOD),
                               row.get(t.ARGMAPPING));
        }
        batch.execute();
    }

    private static void graphitronArgumentReferenceForStep(DSLContext dsl, List<TableRecord<?>> rows) {
        var t = GRAPHITRON_ARGUMENT_REFERENCE_FOR_STEP;
        var batch = dsl.batch(dsl.insertInto(t)
                .columns(t.GRAPH_NAME,
                         t.TYPE_NAME,
                         t.FIELD_NAME,
                         t.ARGUMENT_NAME,
                         t.ORDINAL,
                         t.POSITION,
                         t.TABLE_REF,
                         t.TABLE_REF_NAMESPACE_PART,
                         t.TABLE_REF_NAME_PART,
                         t.KEY_REF,
                         t.KEY_REF_NAMESPACE_PART,
                         t.KEY_REF_NAME_PART,
                         t.CLASS_NAME,
                         t.METHOD,
                         t.ARGMAPPING)
                .values(markers(15)));
        for (TableRecord<?> row : rows) {
            batch = batch.bind(row.get(t.GRAPH_NAME),
                               row.get(t.TYPE_NAME),
                               row.get(t.FIELD_NAME),
                               row.get(t.ARGUMENT_NAME),
                               row.get(t.ORDINAL),
                               row.get(t.POSITION),
                               row.get(t.TABLE_REF),
                               row.get(t.TABLE_REF_NAMESPACE_PART),
                               row.get(t.TABLE_REF_NAME_PART),
                               row.get(t.KEY_REF),
                               row.get(t.KEY_REF_NAMESPACE_PART),
                               row.get(t.KEY_REF_NAME_PART),
                               row.get(t.CLASS_NAME),
                               row.get(t.METHOD),
                               row.get(t.ARGMAPPING));
        }
        batch.execute();
    }

    private static void graphitronArgumentReferenceStep(DSLContext dsl, List<TableRecord<?>> rows) {
        var t = GRAPHITRON_ARGUMENT_REFERENCE_STEP;
        var batch = dsl.batch(dsl.insertInto(t)
                .columns(t.GRAPH_NAME,
                         t.TYPE_NAME,
                         t.FIELD_NAME,
                         t.ARGUMENT_NAME,
                         t.ORDINAL,
                         t.POSITION,
                         t.TABLE_REF,
                         t.TABLE_REF_NAMESPACE_PART,
                         t.TABLE_REF_NAME_PART,
                         t.KEY_REF,
                         t.KEY_REF_NAMESPACE_PART,
                         t.KEY_REF_NAME_PART,
                         t.CLASS_NAME,
                         t.METHOD,
                         t.ARGMAPPING)
                .values(markers(15)));
        for (TableRecord<?> row : rows) {
            batch = batch.bind(row.get(t.GRAPH_NAME),
                               row.get(t.TYPE_NAME),
                               row.get(t.FIELD_NAME),
                               row.get(t.ARGUMENT_NAME),
                               row.get(t.ORDINAL),
                               row.get(t.POSITION),
                               row.get(t.TABLE_REF),
                               row.get(t.TABLE_REF_NAMESPACE_PART),
                               row.get(t.TABLE_REF_NAME_PART),
                               row.get(t.KEY_REF),
                               row.get(t.KEY_REF_NAMESPACE_PART),
                               row.get(t.KEY_REF_NAME_PART),
                               row.get(t.CLASS_NAME),
                               row.get(t.METHOD),
                               row.get(t.ARGMAPPING));
        }
        batch.execute();
    }

    private static void graphitronReferenceForStep(DSLContext dsl, List<TableRecord<?>> rows) {
        var t = GRAPHITRON_REFERENCE_FOR_STEP;
        var batch = dsl.batch(dsl.insertInto(t)
                .columns(t.GRAPH_NAME,
                         t.TYPE_NAME,
                         t.FIELD_NAME,
                         t.ORDINAL,
                         t.POSITION,
                         t.TABLE_REF,
                         t.TABLE_REF_NAMESPACE_PART,
                         t.TABLE_REF_NAME_PART,
                         t.KEY_REF,
                         t.KEY_REF_NAMESPACE_PART,
                         t.KEY_REF_NAME_PART,
                         t.CLASS_NAME,
                         t.METHOD,
                         t.ARGMAPPING)
                .values(markers(14)));
        for (TableRecord<?> row : rows) {
            batch = batch.bind(row.get(t.GRAPH_NAME),
                               row.get(t.TYPE_NAME),
                               row.get(t.FIELD_NAME),
                               row.get(t.ORDINAL),
                               row.get(t.POSITION),
                               row.get(t.TABLE_REF),
                               row.get(t.TABLE_REF_NAMESPACE_PART),
                               row.get(t.TABLE_REF_NAME_PART),
                               row.get(t.KEY_REF),
                               row.get(t.KEY_REF_NAMESPACE_PART),
                               row.get(t.KEY_REF_NAME_PART),
                               row.get(t.CLASS_NAME),
                               row.get(t.METHOD),
                               row.get(t.ARGMAPPING));
        }
        batch.execute();
    }

    private static void graphitronMutation(DSLContext dsl, List<TableRecord<?>> rows) {
        var t = GRAPHITRON_MUTATION;
        var batch = dsl.batch(dsl.insertInto(t)
                .columns(t.GRAPH_NAME,
                         t.TYPE_NAME,
                         t.FIELD_NAME,
                         t.SOURCE_NAME,
                         t.SOURCE_LINE,
                         t.SOURCE_COLUMN,
                         t.OPERATION,
                         t.MULTI_ROW,
                         t.TABLE_REF,
                         t.TABLE_REF_NAMESPACE_PART,
                         t.TABLE_REF_NAME_PART)
                .values(markers(11)));
        for (TableRecord<?> row : rows) {
            batch = batch.bind(row.get(t.GRAPH_NAME),
                               row.get(t.TYPE_NAME),
                               row.get(t.FIELD_NAME),
                               row.get(t.SOURCE_NAME),
                               row.get(t.SOURCE_LINE),
                               row.get(t.SOURCE_COLUMN),
                               row.get(t.OPERATION),
                               row.get(t.MULTI_ROW),
                               row.get(t.TABLE_REF),
                               row.get(t.TABLE_REF_NAMESPACE_PART),
                               row.get(t.TABLE_REF_NAME_PART));
        }
        batch.execute();
    }

    private static void graphitronRoutine(DSLContext dsl, List<TableRecord<?>> rows) {
        var t = GRAPHITRON_ROUTINE;
        var batch = dsl.batch(dsl.insertInto(t)
                .columns(t.GRAPH_NAME,
                         t.TYPE_NAME,
                         t.FIELD_NAME,
                         t.ORDINAL,
                         t.SOURCE_NAME,
                         t.SOURCE_LINE,
                         t.SOURCE_COLUMN,
                         t.ROUTINE_REF,
                         t.ROUTINE_REF_NAMESPACE_PART,
                         t.ROUTINE_REF_NAME_PART,
                         t.ARGMAPPING,
                         t.COLUMN_MAPPING)
                .values(markers(12)));
        for (TableRecord<?> row : rows) {
            batch = batch.bind(row.get(t.GRAPH_NAME),
                               row.get(t.TYPE_NAME),
                               row.get(t.FIELD_NAME),
                               row.get(t.ORDINAL),
                               row.get(t.SOURCE_NAME),
                               row.get(t.SOURCE_LINE),
                               row.get(t.SOURCE_COLUMN),
                               row.get(t.ROUTINE_REF),
                               row.get(t.ROUTINE_REF_NAMESPACE_PART),
                               row.get(t.ROUTINE_REF_NAME_PART),
                               row.get(t.ARGMAPPING),
                               row.get(t.COLUMN_MAPPING));
        }
        batch.execute();
    }

    private static void graphqlField(DSLContext dsl, List<TableRecord<?>> rows) {
        var t = GRAPHQL_FIELD;
        var batch = dsl.batch(dsl.insertInto(t)
                .columns(t.GRAPH_NAME,
                         t.TYPE_NAME,
                         t.FIELD_NAME,
                         t.ORDINAL,
                         t.DECLARATION_LINE,
                         t.DECLARATION_COLUMN,
                         t.TYPE_SDL,
                         t.NAMED_TYPE,
                         t.NON_NULL,
                         t.IS_LIST,
                         t.ITEM_NON_NULL,
                         t.DEFAULT_VALUE_SDL,
                         t.DESCRIPTION,
                         t.SOURCE_NAME,
                         t.SOURCE_LINE,
                         t.SOURCE_COLUMN)
                .values(markers(16)));
        for (TableRecord<?> row : rows) {
            batch = batch.bind(row.get(t.GRAPH_NAME),
                               row.get(t.TYPE_NAME),
                               row.get(t.FIELD_NAME),
                               row.get(t.ORDINAL),
                               row.get(t.DECLARATION_LINE),
                               row.get(t.DECLARATION_COLUMN),
                               row.get(t.TYPE_SDL),
                               row.get(t.NAMED_TYPE),
                               row.get(t.NON_NULL),
                               row.get(t.IS_LIST),
                               row.get(t.ITEM_NON_NULL),
                               row.get(t.DEFAULT_VALUE_SDL),
                               row.get(t.DESCRIPTION),
                               row.get(t.SOURCE_NAME),
                               row.get(t.SOURCE_LINE),
                               row.get(t.SOURCE_COLUMN));
        }
        batch.execute();
    }

    private static void graphqlArgument(DSLContext dsl, List<TableRecord<?>> rows) {
        var t = GRAPHQL_ARGUMENT;
        var batch = dsl.batch(dsl.insertInto(t)
                .columns(t.GRAPH_NAME,
                         t.TYPE_NAME,
                         t.FIELD_NAME,
                         t.ARGUMENT_NAME,
                         t.ORDINAL,
                         t.TYPE_SDL,
                         t.NAMED_TYPE,
                         t.NON_NULL,
                         t.IS_LIST,
                         t.ITEM_NON_NULL,
                         t.DEFAULT_VALUE_SDL,
                         t.DESCRIPTION,
                         t.SOURCE_NAME,
                         t.SOURCE_LINE,
                         t.SOURCE_COLUMN)
                .values(markers(15)));
        for (TableRecord<?> row : rows) {
            batch = batch.bind(row.get(t.GRAPH_NAME),
                               row.get(t.TYPE_NAME),
                               row.get(t.FIELD_NAME),
                               row.get(t.ARGUMENT_NAME),
                               row.get(t.ORDINAL),
                               row.get(t.TYPE_SDL),
                               row.get(t.NAMED_TYPE),
                               row.get(t.NON_NULL),
                               row.get(t.IS_LIST),
                               row.get(t.ITEM_NON_NULL),
                               row.get(t.DEFAULT_VALUE_SDL),
                               row.get(t.DESCRIPTION),
                               row.get(t.SOURCE_NAME),
                               row.get(t.SOURCE_LINE),
                               row.get(t.SOURCE_COLUMN));
        }
        batch.execute();
    }

    private static void sqlTable(DSLContext dsl, List<TableRecord<?>> rows) {
        var t = SQL_TABLE;
        var batch = dsl.batch((dsl.insertInto(t)
                .columns(t.SOURCE_NAME,
                         t.TABLE_SCHEMA,
                         t.TABLE_NAME,
                         t.TABLE_TYPE,
                         t.JOOQ_NAME,
                         t.CLASS_FQN,
                         t.RECORD_CLASS_FQN,
                         t.DESCRIPTION)
                .values(markers(8)))
                .onDuplicateKeyIgnore());
        for (TableRecord<?> row : rows) {
            batch = batch.bind(row.get(t.SOURCE_NAME),
                               row.get(t.TABLE_SCHEMA),
                               row.get(t.TABLE_NAME),
                               row.get(t.TABLE_TYPE),
                               row.get(t.JOOQ_NAME),
                               row.get(t.CLASS_FQN),
                               row.get(t.RECORD_CLASS_FQN),
                               row.get(t.DESCRIPTION));
        }
        batch.execute();
    }

    private static void sqlConstraint(DSLContext dsl, List<TableRecord<?>> rows) {
        var t = SQL_CONSTRAINT;
        var batch = dsl.batch((dsl.insertInto(t)
                .columns(t.SOURCE_NAME,
                         t.TABLE_SCHEMA,
                         t.TABLE_NAME,
                         t.CONSTRAINT_NAME,
                         t.CONSTRAINT_TYPE,
                         t.JOOQ_NAME,
                         t.KEY_POSITION)
                .values(markers(7)))
                .onDuplicateKeyIgnore());
        for (TableRecord<?> row : rows) {
            batch = batch.bind(row.get(t.SOURCE_NAME),
                               row.get(t.TABLE_SCHEMA),
                               row.get(t.TABLE_NAME),
                               row.get(t.CONSTRAINT_NAME),
                               row.get(t.CONSTRAINT_TYPE),
                               row.get(t.JOOQ_NAME),
                               row.get(t.KEY_POSITION));
        }
        batch.execute();
    }


    private static void sqlColumn(DSLContext dsl, List<TableRecord<?>> rows) {
        var t = SQL_COLUMN;
        var batch = dsl.batch((dsl.insertInto(t)
                .columns(t.SOURCE_NAME,
                         t.TABLE_SCHEMA,
                         t.TABLE_NAME,
                         t.COLUMN_NAME,
                         t.ORDINAL,
                         t.JOOQ_NAME,
                         t.SQL_TYPE,
                         t.BINDING_TYPE,
                         t.NULLABLE,
                         t.DESCRIPTION)
                .values(markers(10)))
                .onDuplicateKeyIgnore());
        for (TableRecord<?> row : rows) {
            batch = batch.bind(row.get(t.SOURCE_NAME),
                               row.get(t.TABLE_SCHEMA),
                               row.get(t.TABLE_NAME),
                               row.get(t.COLUMN_NAME),
                               row.get(t.ORDINAL),
                               row.get(t.JOOQ_NAME),
                               row.get(t.SQL_TYPE),
                               row.get(t.BINDING_TYPE),
                               row.get(t.NULLABLE),
                               row.get(t.DESCRIPTION));
        }
        batch.execute();
    }

    private static void sqlEnumBinding(DSLContext dsl, List<TableRecord<?>> rows) {
        var t = SQL_ENUM_BINDING;
        var batch = dsl.batch((dsl.insertInto(t)
                .columns(t.SOURCE_NAME,
                         t.CLASS_FQN,
                         t.TABLE_SCHEMA,
                         t.TYPE_NAME)
                .values(markers(4)))
                .onDuplicateKeyIgnore());
        for (TableRecord<?> row : rows) {
            batch = batch.bind(row.get(t.SOURCE_NAME),
                               row.get(t.CLASS_FQN),
                               row.get(t.TABLE_SCHEMA),
                               row.get(t.TYPE_NAME));
        }
        batch.execute();
    }
}
