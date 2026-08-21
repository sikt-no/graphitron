package no.sikt.graphitron.lsp.facts;

import no.sikt.graphitron.model.read.StoreHandle;
import org.eclipse.lsp4j.Location;
import org.jooq.Condition;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Record1;
import org.jooq.Records;
import org.jooq.Select;
import org.jooq.TableField;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.SequencedMap;
import java.util.stream.Stream;

import static no.sikt.graphitron.model.Tables.INTENT_BOUND_TABLE;
import static no.sikt.graphitron.model.Tables.INTENT_CLASS_MEMBER_SLOT;
import static no.sikt.graphitron.model.Tables.INTENT_FIELD_PRODUCER_METHOD;
import static no.sikt.graphitron.model.Tables.INTENT_FIELD_PRODUCER_REFERENCE;
import static no.sikt.graphitron.model.Tables.INTENT_FIELD_ROUTINE_METHOD;
import static no.sikt.graphitron.model.Tables.INTENT_TYPE_BACKING;
import static no.sikt.graphitron.model.Tables.INTENT_TYPE_BACKING_SEED;
import static no.sikt.graphitron.model.Tables.JAVA_CLASS_DECLARATION;
import static no.sikt.graphitron.model.Tables.JAVA_FIELD_DECLARATION;
import static no.sikt.graphitron.model.Tables.JAVA_METHOD_DECLARATION;
import static no.sikt.graphitron.model.Tables.JVM_METHOD_PARAMETER;
import static no.sikt.graphitron.model.Tables.SQL_COLUMN;
import static no.sikt.graphitron.model.Tables.SQL_TABLE;
import static org.jooq.impl.DSL.exists;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.multiset;
import static org.jooq.impl.DSL.select;
import static org.jooq.impl.DSL.selectCount;
import static org.jooq.impl.DSL.selectDistinct;

/**
 * Everything the two declaration surfaces ask the store about one SDL coordinate, in one statement.
 * A projection and not a view: it joins the relations owning each fact to the one coordinate under a
 * cursor and hands back the populations the resolution is about to reduce. Nothing here is a rule of
 * its own, each precedence being applied by the reader that owns it once every arm has returned.
 *
 * <p>The shape is {@code InlayFacts}': a multiset per arm, each subquery over the relation owning its
 * fact, nothing joined between arms, and the whole driven from no table so no arm is conditional on
 * another's rows. The grain is one declaration, which is what a goto-definition or hover request is
 * about.
 *
 * <h2>Why the declarations ride along</h2>
 *
 * <p>Three arms answer about the java-source family, which holds the doc comment a hover overlays and
 * the position an editor jumps to. What either surface wants is that family's answer about the
 * <em>one</em> declaration the coordinate resolved to, and which declaration that is only becomes
 * known once the resolution has run over the arms above. So these arms answer for every declaration
 * the coordinate <em>could</em> resolve to, admitted by whichever relation could name it, and the
 * resolution picks by name from rows already in hand.
 *
 * <p>That is the trade {@code InlayFacts} states as rows in a payload rather than a round trip, and
 * it is what lets a request that resolves a binding and then describes it stay one statement. The
 * candidate population is small at a single coordinate: the classes a type could be backed by, the
 * tables it could be bound to, and, at the field grain, the columns and members its own name reaches.
 */
public final class DeclarationFacts {

    private DeclarationFacts() {}

    /**
     * The coordinate a declaration surface is asking about. Two grains because an SDL declaration name
     * is either a type's or a member's, and the field grain asks four questions the type grain has no
     * use for.
     *
     * <p>A member name here is the one the field <em>binds</em> to, which is its
     * {@code @field(name:)} override where it carries one. Reading that override off the parse is the
     * caller's, this being a store key rather than a parse artifact.
     */
    public sealed interface Coord {

        /** A type-declaration name. */
        record Type(String typeName) implements Coord {}

        /** A field or input-value-declaration name, under the member name it binds to. */
        record Member(String typeName, String memberName) implements Coord {}

        /** The type the coordinate is declared in, which every arm is keyed on. */
        default String typeName() {
            return switch (this) {
                case Type t -> t.typeName();
                case Member m -> m.typeName();
            };
        }

        /** The member name, or null at the type grain. */
        default String memberName() {
            return this instanceof Member m ? m.memberName() : null;
        }
    }

    // ===== The rows the arms return =====

    /** One table a coordinate's type could resolve against, with what the census says about it. */
    public record TableRow(CatalogTable key, String classFqn, String description) {

        /** The table's SQL name, which is what a surface naming the table calls it. */
        public String tableName() {
            return key.tableName();
        }
    }

    /** A candidate backing class read back as the table whose rows jOOQ binds to it. */
    public record RedirectRow(String className, TableRow table) {}

    /** A column of a candidate table that the member name reaches, under both of its names. */
    public record ColumnRow(
        CatalogTable key, String columnName, String jooqName, String comment
    ) {}

    /** One member slot a candidate backing class offers under the member name. */
    public record SlotRow(
        String className, String slotName, String accessorMethodName, ClassMemberSlots.Origin origin
    ) {}

    /**
     * One generated call surface a {@code @routine} application on the coordinate resolves to: the
     * {@code Routines} class, the method an emitted FROM clause calls, and that method's arity. All
     * three come from the catalog census, so the arity is the generated method's own rather than a
     * count over classpath entries that ordinarily do not include the consumer's jOOQ output.
     */
    public record RoutineMethod(String className, String methodName, int arity) {}

    /**
     * One parsed class declaration: its doc comment, and where the parse positioned it. A declaration
     * the parse could not position still carries its comment, which is the asymmetry the java-source
     * family's schema keeps room for and the reason the two are read as one row here.
     */
    public record ClassRow(String className, String javadoc, Optional<Location> location) {}

    /** One parsed field declaration, which is a record component or a generated column constant. */
    public record FieldRow(
        String className, String fieldName, String javadoc, Optional<Location> location
    ) {}

    /**
     * One parsed method declaration, keyed additionally by the parameters it declares. Arity is the
     * only ground the parse and the classpath census share, {@link SourceDeclarations} stating why.
     */
    public record MethodRow(
        String className, String methodName, Integer arity, String javadoc, Optional<Location> location
    ) {}

    /**
     * Every population one coordinate's resolution reads, answered. The accessors are lookups over
     * rows in hand and no more: every rule they apply is delegated to the reader that owns it.
     */
    public record Rows(
        List<TableRow> boundTables,
        List<String> backingSeeds,
        List<String> backingReached,
        List<RedirectRow> redirects,
        List<ColumnRow> columns,
        List<FieldProducerMethods.Reference> producers,
        List<RoutineMethod> routineMethods,
        List<SlotRow> slots,
        List<ClassRow> classDeclarations,
        List<FieldRow> fieldDeclarations,
        List<MethodRow> methodDeclarations
    ) {

        /** What member names written inside the type resolve against, by the scope reader's own rules. */
        public Optional<TypeMemberScope.Scope> scope() {
            return TypeMemberScope.resolve(
                boundTables.stream().map(TableRow::key).toList(),
                this::backingClass,
                className -> redirects.stream()
                    .filter(row -> row.className().equals(className))
                    .map(row -> row.table().key())
                    .toList());
        }

        /** The class standing for the type, by the grounding rule the backing reader owns. */
        public Optional<String> backingClass() {
            return TypeBackingClass.resolve(backingSeeds, backingReached);
        }

        /** The method the coordinate's producer reference names, by that reader's own refusal rule. */
        public Optional<FieldProducerMethods.Producer> producer() {
            return FieldProducerMethods.resolve(producers);
        }

        /**
         * The call surface the coordinate's {@code @routine} binds to, which is the first application
         * in written order and, within one application, the first candidate in schema order. Picking
         * the first is what every other resolution here does with an ambiguous population: which one
         * was meant is a resolution question, and a single declaration target cannot hold both.
         */
        public Optional<RoutineMethod> routineMethod() {
            return routineMethods.stream().findFirst();
        }

        /** What the census says about one of the tables in scope, which is where its class comes from. */
        public Optional<TableRow> table(CatalogTable key) {
            return tables().filter(row -> row.key().equals(key)).findFirst();
        }

        /**
         * What the census says about a table a resolution has already named, matched
         * case-insensitively and answered for the first in schema order. The name-keyed read beside
         * the key-keyed one above, on {@code CatalogTables}'s terms: a caller holding a key asks about
         * the table it picked, and a caller holding a spelling is asking what the census spells.
         */
        public Optional<TableRow> tableNamed(String tableName) {
            if (tableName == null) return Optional.empty();
            return tables().filter(row -> row.tableName().equalsIgnoreCase(tableName)).findFirst();
        }

        /** The column of {@code key} the member name reached, empty where the table declares none. */
        public Optional<ColumnRow> column(CatalogTable key) {
            return columns.stream().filter(row -> row.key().equals(key)).findFirst();
        }

        /**
         * The named table's column under either of the two spellings the census carries, which is the
         * rule {@link CatalogColumns.Names} owns: a target resolved under the jOOQ spelling and one
         * resolved under the SQL spelling name the same column.
         */
        public Optional<ColumnRow> columnNamed(String tableName, String spelling) {
            if (tableName == null || spelling == null) return Optional.empty();
            return columns.stream()
                .filter(row -> row.key().tableName().equalsIgnoreCase(tableName))
                .filter(row -> new CatalogColumns.Names(row.columnName(), row.jooqName())
                    .isNamed(spelling))
                .findFirst();
        }

        /** The slot {@code className} offers under the member name. */
        public Optional<SlotRow> slot(String className) {
            return slots.stream().filter(row -> row.className().equals(className)).findFirst();
        }

        /** The parsed declaration of a class, or empty where no parsed source declares it. */
        public Optional<ClassRow> classDeclaration(String className) {
            if (className == null) return Optional.empty();
            return classDeclarations.stream()
                .filter(row -> row.className().equals(className))
                .findFirst();
        }

        /**
         * The parsed declaration of a field on the named class. Case-insensitive on the field name
         * because a target may name a column under either census spelling, where the parsed source
         * declares the generated constant under one of them.
         */
        public Optional<FieldRow> fieldDeclaration(String className, String fieldName) {
            if (className == null || fieldName == null) return Optional.empty();
            return fieldDeclarations.stream()
                .filter(row -> row.className().equals(className))
                .filter(row -> row.fieldName().equalsIgnoreCase(fieldName))
                .findFirst();
        }

        /**
         * Doc comments for one method name keyed by the arity the source declares, in the family's own
         * (file, ordinal) order, which is the map {@link SourceDeclarations#byArityThenName} reduces.
         */
        public SequencedMap<Integer, String> methodJavadocByArity(String className, String methodName) {
            return byArity(className, methodName,
                row -> row.javadoc().isBlank() ? null : row.javadoc());
        }

        /**
         * Jump positions for one method name, keyed the same way. Two maps rather than one because
         * each holds only the rows that can answer its own question: a declaration the parse
         * positioned but wrote no comment for answers the second and not the first, which is the
         * asymmetry {@link SourceDeclarations} keeps the two lookups apart for.
         */
        public SequencedMap<Integer, Location> methodLocationByArity(String className, String methodName) {
            return byArity(className, methodName, row -> row.location().orElse(null));
        }

        private <T> SequencedMap<Integer, T> byArity(
            String className, String methodName, java.util.function.Function<MethodRow, T> answer
        ) {
            var byArity = new LinkedHashMap<Integer, T>();
            if (className == null || methodName == null) return byArity;
            for (var row : methodDeclarations) {
                if (!row.className().equals(className) || !row.methodName().equals(methodName)) continue;
                T value = answer.apply(row);
                if (value != null) byArity.putIfAbsent(row.arity(), value);
            }
            return byArity;
        }

        private Stream<TableRow> tables() {
            return Stream.concat(
                boundTables.stream(), redirects.stream().map(RedirectRow::table));
        }
    }

    /**
     * What a session with no store answers, which is that every relation is empty. Deliberately the
     * same value a coordinate the store holds nothing about produces, so a surface has one silence
     * rather than a branch per absence.
     */
    public static Rows none() {
        return new Rows(List.of(), List.of(), List.of(), List.of(), List.of(),
            List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    }

    /**
     * The arms of one coordinate's read, as fields of a caller's own statement. A holder rather than a
     * finished query because the declaration hover asks its claim block in the same statement: the two
     * question sets are independent, they are keyed on the same coordinate, and asking them separately
     * would be the round trip this shape exists to remove.
     */
    public static final class Arms {

        private final Field<List<TableRow>> boundTables;
        private final Field<List<String>> backingSeeds;
        private final Field<List<String>> backingReached;
        private final Field<List<RedirectRow>> redirects;
        private final Field<List<ColumnRow>> columns;
        private final Field<List<FieldProducerMethods.Reference>> producers;
        private final Field<List<RoutineMethod>> routineMethods;
        private final Field<List<SlotRow>> slots;
        private final Field<List<ClassRow>> classDeclarations;
        private final Field<List<FieldRow>> fieldDeclarations;
        private final Field<List<MethodRow>> methodDeclarations;

        private Arms(StoreHandle store, Coord coord) {
            var classes = candidateClasses(store, coord);
            boundTables = named(boundTableArm(store, coord), "bound_tables");
            backingSeeds = named(backingArm(store, coord, INTENT_TYPE_BACKING_SEED.TYPE_NAME,
                INTENT_TYPE_BACKING_SEED.CLASS_NAME, INTENT_TYPE_BACKING_SEED.GRAPH_NAME),
                "backing_seeds");
            backingReached = named(backingArm(store, coord, INTENT_TYPE_BACKING.TYPE_NAME,
                INTENT_TYPE_BACKING.CLASS_NAME, INTENT_TYPE_BACKING.GRAPH_NAME),
                "backing_reached");
            redirects = named(redirectArm(store, coord), "redirects");
            columns = named(columnArm(store, coord), "columns");
            producers = named(producerArm(store, coord), "producers");
            routineMethods = named(routineMethodArm(store, coord), "routine_methods");
            slots = named(slotArm(store, coord), "slots");
            classDeclarations = named(classDeclarationArm(classes), "class_declarations");
            fieldDeclarations = named(fieldDeclarationArm(store, coord, classes), "field_declarations");
            methodDeclarations = named(methodDeclarationArm(store, coord, classes),
                "method_declarations");
        }

        /**
         * The arms, for a caller assembling them into a select of its own. An arm the grain has no
         * question for is absent rather than present and empty: a type name binds no member, so the
         * field-grain arms would be reading a relation to be told what the grain already says.
         */
        public List<Field<?>> fields() {
            var fields = new ArrayList<Field<?>>();
            for (Field<?> arm : new Field<?>[] {boundTables, backingSeeds, backingReached, redirects,
                columns, producers, routineMethods, slots, classDeclarations, fieldDeclarations,
                methodDeclarations}) {
                if (arm != null) fields.add(arm);
            }
            return fields;
        }

        /** The arms' rows, read off the record the caller's statement returned. */
        public Rows read(Record row) {
            return new Rows(row.get(boundTables), row.get(backingSeeds), row.get(backingReached),
                row.get(redirects), rowsOf(row, columns), rowsOf(row, producers),
                rowsOf(row, routineMethods), rowsOf(row, slots), row.get(classDeclarations),
                rowsOf(row, fieldDeclarations), rowsOf(row, methodDeclarations));
        }

        private static <T> List<T> rowsOf(Record row, Field<List<T>> arm) {
            return arm == null ? List.of() : row.get(arm);
        }

        /** Aliases an arm so a caller's wider statement can read it back by name, absent arms included. */
        private static <T> Field<T> named(Field<T> arm, String alias) {
            return arm == null ? null : arm.as(alias);
        }
    }

    /** The arms of one coordinate's read, for a caller composing them with questions of its own. */
    public static Arms arms(StoreHandle store, Coord coord) {
        return new Arms(store, coord);
    }

    /** One coordinate's populations, in one statement, for a caller asking nothing else. */
    public static Rows of(StoreHandle store, Coord coord) {
        var arms = arms(store, coord);
        return arms.read(store.dsl().select(arms.fields()).fetchOne());
    }

    // ===== The arms =====

    /**
     * The tables the type's {@code @table} binding resolves to, in {@link BoundTables}'s schema then
     * table order, joined to the census row that names their generated class. The join is what
     * {@code CatalogTables.of} performs per key, composed here rather than run per candidate.
     */
    private static Field<List<TableRow>> boundTableArm(StoreHandle store, Coord coord) {
        return multiset(select(INTENT_BOUND_TABLE.TABLE_SOURCE_NAME, INTENT_BOUND_TABLE.TABLE_SCHEMA,
                INTENT_BOUND_TABLE.TABLE_NAME, SQL_TABLE.CLASS_FQN, SQL_TABLE.DESCRIPTION)
            .from(INTENT_BOUND_TABLE)
            .join(SQL_TABLE).on(tableKeyOf(INTENT_BOUND_TABLE.TABLE_SOURCE_NAME,
                INTENT_BOUND_TABLE.TABLE_SCHEMA, INTENT_BOUND_TABLE.TABLE_NAME))
            .where(INTENT_BOUND_TABLE.GRAPH_NAME.eq(store.graphName()))
            .and(INTENT_BOUND_TABLE.TYPE_NAME.eq(coord.typeName()))
            .and(store.reads(SQL_TABLE.SOURCE_NAME))
            .orderBy(INTENT_BOUND_TABLE.TABLE_SCHEMA, INTENT_BOUND_TABLE.TABLE_NAME))
            .convertFrom(rows -> rows.map(row -> new TableRow(
                new CatalogTable(row.value1(), row.value2(), row.value3()),
                row.value4(), text(row.value5()))));
    }

    /**
     * One backing population, either of the two the grounding rule chooses between. Both have the
     * same shape over different relations, which is why this is one method called twice: what
     * separates them is which relation grounds a class, and that is the parameter.
     */
    private static Field<List<String>> backingArm(
        StoreHandle store, Coord coord, TableField<?, String> typeColumn,
        TableField<?, String> classColumn, TableField<?, String> graphColumn
    ) {
        return multiset(selectDistinct(classColumn)
            .from(typeColumn.getTable())
            .where(graphColumn.eq(store.graphName()))
            .and(typeColumn.eq(coord.typeName()))
            .orderBy(classColumn))
            .convertFrom(rows -> rows.map(Record1::value1));
    }

    /**
     * A candidate backing class read back as the table whose rows jOOQ binds to it, which is
     * {@link CatalogTables#ofRecordClass} composed per candidate rather than run per candidate. The
     * census's no-record-class sentinel is excluded here rather than filtered afterwards: every table
     * jOOQ generated no record for reports it, so reading it as a class name would match all of them
     * at once.
     *
     * <h2>The census is a lookup, not a join partner</h2>
     *
     * <p>Written as one join of the two relations, this arm cost about a second where every other arm
     * in the same statement answered in tens of milliseconds, and it cost it while returning nothing:
     * the second went on establishing that there was nothing to say. The reason is the join's driving
     * side. H2 is free to drive from either relation, it chose the census, and the derived relation
     * was therefore evaluated once per catalog table with the two predicates that make it cheap
     * applied after that expansion rather than before it.
     *
     * <p>Which predicates the query states and where it states them cannot fix that, and both ways of
     * asking were measured: a derived table around the filtered relation is inlined, and an
     * {@code IN} subquery is evaluated per driving row just the same. What settles it is a shape with
     * no driving side for the planner to choose, so the census is reached here as a correlated lookup
     * evaluated per surviving backing row. In the census's own arithmetic, the difference is scanning
     * its rows about once against scanning them once per row of a relation that grows with the
     * schema, which is what {@code SurfaceScanCountTest} pins.
     *
     * <p>One row of the answer is one (backing class, bound table) pair, so the nesting is flattened
     * back to that grain here. The sort restores the census order the flat join stated, which nesting
     * turns into class-major order: it is a handful of rows, and a member scope reading these
     * candidates should not have its order depend on which shape the arm is written in.
     */
    private static Field<List<RedirectRow>> redirectArm(StoreHandle store, Coord coord) {
        var boundTable = multiset(select(SQL_TABLE.SOURCE_NAME, SQL_TABLE.TABLE_SCHEMA,
                SQL_TABLE.TABLE_NAME, SQL_TABLE.CLASS_FQN, SQL_TABLE.DESCRIPTION)
            .from(SQL_TABLE)
            .where(SQL_TABLE.RECORD_CLASS_FQN.eq(INTENT_TYPE_BACKING.CLASS_NAME))
            .and(SQL_TABLE.RECORD_CLASS_FQN.ne(NO_RECORD_CLASS))
            .and(store.reads(SQL_TABLE.SOURCE_NAME))
            .orderBy(SQL_TABLE.TABLE_SCHEMA, SQL_TABLE.TABLE_NAME))
            // The description is passed through unnormalised, as the flat form passed it: what this
            // arm answers is not this item's to change.
            .convertFrom(rows -> rows.map(row -> new TableRow(
                new CatalogTable(row.value1(), row.value2(), row.value3()),
                row.value4(), row.value5())));
        return multiset(select(INTENT_TYPE_BACKING.CLASS_NAME, boundTable)
            .from(INTENT_TYPE_BACKING)
            .where(INTENT_TYPE_BACKING.GRAPH_NAME.eq(store.graphName()))
            .and(INTENT_TYPE_BACKING.TYPE_NAME.eq(coord.typeName()))
            .orderBy(INTENT_TYPE_BACKING.CLASS_NAME))
            .convertFrom(rows -> rows.stream()
                .flatMap(row -> row.value2().stream()
                    .map(table -> new RedirectRow(row.value1(), table)))
                .sorted(CENSUS_ORDER)
                .toList());
    }

    /** The order the redirect arm's flat form stated in SQL: the census's, by schema then table. */
    private static final Comparator<RedirectRow> CENSUS_ORDER =
        Comparator.comparing((RedirectRow row) -> row.table().key().schema())
            .thenComparing(row -> row.table().key().tableName());

    /**
     * The columns of the tables in scope that the member name reaches, under either of the two names
     * the census carries. Filtered by the name here rather than after the fetch because the only
     * question asked of this arm is whether a candidate table declares the member: the whole column
     * list of a wide table is a completion popup's population rather than a resolution's.
     *
     * <p>Which tables are in scope is the two arms above stated as a disjunction, because the scope
     * rule runs after every arm has returned and this arm cannot know which of them will win.
     */
    private static Field<List<ColumnRow>> columnArm(StoreHandle store, Coord coord) {
        String memberName = coord.memberName();
        if (memberName == null) return null;
        return multiset(select(SQL_COLUMN.SOURCE_NAME, SQL_COLUMN.TABLE_SCHEMA,
                SQL_COLUMN.TABLE_NAME, SQL_COLUMN.COLUMN_NAME, SQL_COLUMN.JOOQ_NAME,
                SQL_COLUMN.DESCRIPTION)
            .from(SQL_COLUMN)
            .where(store.reads(SQL_COLUMN.SOURCE_NAME))
            .and(SQL_COLUMN.JOOQ_NAME.equalIgnoreCase(memberName)
                .or(SQL_COLUMN.COLUMN_NAME.equalIgnoreCase(memberName)))
            .and(inScope(store, coord))
            .orderBy(SQL_COLUMN.TABLE_SCHEMA, SQL_COLUMN.ORDINAL))
            .convertFrom(rows -> rows.map(row -> new ColumnRow(
                new CatalogTable(row.value1(), row.value2(), row.value3()),
                row.value4(), row.value5(), text(row.value6()))));
    }

    /**
     * The producer references the coordinate carries, each left-joined to the census method it
     * resolves to. {@link FieldProducerMethods} owns both the reason the join is outer and the rule
     * that reduces the rows; this arm is that reader's statement composed into a wider one.
     */
    private static Field<List<FieldProducerMethods.Reference>> producerArm(
        StoreHandle store, Coord coord
    ) {
        if (coord.memberName() == null) return null;
        var arity = field(selectCount()
            .from(JVM_METHOD_PARAMETER)
            .where(JVM_METHOD_PARAMETER.SOURCE_NAME.eq(INTENT_FIELD_PRODUCER_METHOD.SOURCE_NAME))
            .and(JVM_METHOD_PARAMETER.CLASS_NAME.eq(INTENT_FIELD_PRODUCER_METHOD.CLASS_NAME))
            .and(JVM_METHOD_PARAMETER.METHOD_NAME.eq(INTENT_FIELD_PRODUCER_METHOD.METHOD_NAME))
            .and(JVM_METHOD_PARAMETER.DESCRIPTOR.eq(INTENT_FIELD_PRODUCER_METHOD.DESCRIPTOR)));
        return multiset(select(INTENT_FIELD_PRODUCER_REFERENCE.CLASS_NAME,
                INTENT_FIELD_PRODUCER_REFERENCE.METHOD_NAME, arity)
            .from(INTENT_FIELD_PRODUCER_REFERENCE)
            .leftJoin(INTENT_FIELD_PRODUCER_METHOD)
            .on(INTENT_FIELD_PRODUCER_METHOD.GRAPH_NAME
                .eq(INTENT_FIELD_PRODUCER_REFERENCE.GRAPH_NAME))
            .and(INTENT_FIELD_PRODUCER_METHOD.TYPE_NAME
                .eq(INTENT_FIELD_PRODUCER_REFERENCE.TYPE_NAME))
            .and(INTENT_FIELD_PRODUCER_METHOD.FIELD_NAME
                .eq(INTENT_FIELD_PRODUCER_REFERENCE.FIELD_NAME))
            .and(INTENT_FIELD_PRODUCER_METHOD.DECLARED_VIA
                .eq(INTENT_FIELD_PRODUCER_REFERENCE.DECLARED_VIA))
            .where(coordinate(INTENT_FIELD_PRODUCER_REFERENCE.GRAPH_NAME.eq(store.graphName()),
                INTENT_FIELD_PRODUCER_REFERENCE.TYPE_NAME.eq(coord.typeName()),
                INTENT_FIELD_PRODUCER_REFERENCE.FIELD_NAME.eq(coord.memberName())))
            .orderBy(INTENT_FIELD_PRODUCER_REFERENCE.DECLARED_VIA,
                INTENT_FIELD_PRODUCER_METHOD.DESCRIPTOR))
            .convertFrom(rows -> rows.map(Records.mapping(FieldProducerMethods.Reference::new)));
    }

    /**
     * The slot every candidate backing class offers under the member name, keyed by class rather than
     * by type: which class the type resolves to is the grounding rule's answer, applied after this arm
     * has returned, and a slot is a fact about a class either way.
     *
     * <p>Exact on the name, never case-insensitive, which is {@link ClassMemberSlots}'s own rule: a
     * member name is a Java identifier the author is naming rather than a coordinate resolved for them.
     */
    private static Field<List<SlotRow>> slotArm(StoreHandle store, Coord coord) {
        String memberName = coord.memberName();
        if (memberName == null) return null;
        return multiset(selectDistinct(INTENT_CLASS_MEMBER_SLOT.CLASS_NAME,
                INTENT_CLASS_MEMBER_SLOT.SLOT_NAME, INTENT_CLASS_MEMBER_SLOT.ACCESSOR_METHOD_NAME,
                INTENT_CLASS_MEMBER_SLOT.ORIGIN)
            .from(INTENT_CLASS_MEMBER_SLOT)
            .where(store.reads(INTENT_CLASS_MEMBER_SLOT.SOURCE_NAME))
            .and(INTENT_CLASS_MEMBER_SLOT.SLOT_NAME.eq(memberName))
            .and(INTENT_CLASS_MEMBER_SLOT.CLASS_NAME.in(backingCandidates(store, coord)))
            .orderBy(INTENT_CLASS_MEMBER_SLOT.CLASS_NAME,
                INTENT_CLASS_MEMBER_SLOT.ACCESSOR_METHOD_NAME))
            .convertFrom(rows -> rows.map(row -> new SlotRow(row.value1(), row.value2(),
                row.value3(), ClassMemberSlots.Origin.of(row.value4()))));
    }

    /** The parsed class declarations of every class the coordinate could resolve to. */
    private static Field<List<ClassRow>> classDeclarationArm(Select<Record1<String>> classes) {
        return multiset(select(JAVA_CLASS_DECLARATION.CLASS_NAME, JAVA_CLASS_DECLARATION.JAVADOC,
                JAVA_CLASS_DECLARATION.FILE, JAVA_CLASS_DECLARATION.SOURCE_LINE,
                JAVA_CLASS_DECLARATION.SOURCE_COLUMN)
            .from(JAVA_CLASS_DECLARATION)
            .where(JAVA_CLASS_DECLARATION.CLASS_NAME.in(classes))
            .orderBy(JAVA_CLASS_DECLARATION.FILE))
            .convertFrom(rows -> rows.map(row -> new ClassRow(row.value1(), text(row.value2()),
                SourceDeclarations.locationOf(row.value3(), row.value4(), row.value5()))));
    }

    /**
     * The parsed field declarations the member name reaches on any candidate class: a record component
     * under its own name, and a generated table class's column constant under whatever the generator
     * named it. The member name is admitted case-insensitively, which covers a column constant that is
     * the SQL name in another case, and the census's own generated spellings are admitted beside it,
     * which covers a naming strategy under which the two names differ by more than case.
     */
    private static Field<List<FieldRow>> fieldDeclarationArm(
        StoreHandle store, Coord coord, Select<Record1<String>> classes
    ) {
        String memberName = coord.memberName();
        if (memberName == null) return null;
        return multiset(select(JAVA_FIELD_DECLARATION.CLASS_NAME, JAVA_FIELD_DECLARATION.FIELD_NAME,
                JAVA_FIELD_DECLARATION.JAVADOC, JAVA_FIELD_DECLARATION.FILE,
                JAVA_FIELD_DECLARATION.SOURCE_LINE, JAVA_FIELD_DECLARATION.SOURCE_COLUMN)
            .from(JAVA_FIELD_DECLARATION)
            .where(JAVA_FIELD_DECLARATION.CLASS_NAME.in(classes))
            .and(JAVA_FIELD_DECLARATION.FIELD_NAME.equalIgnoreCase(memberName)
                .or(JAVA_FIELD_DECLARATION.FIELD_NAME.in(candidateFieldNames(store, coord))))
            .orderBy(JAVA_FIELD_DECLARATION.FILE))
            .convertFrom(rows -> rows.map(row -> new FieldRow(row.value1(), row.value2(),
                text(row.value3()),
                SourceDeclarations.locationOf(row.value4(), row.value5(), row.value6()))));
    }

    /**
     * The parsed method declarations of every method the coordinate could bind to, which is a producer
     * reference's method or a candidate class's bean accessor. Every overload of each, because the
     * arity-then-name rule that picks between them is {@link SourceDeclarations}'s and runs over the
     * rows rather than in the filter.
     */
    private static Field<List<MethodRow>> methodDeclarationArm(
        StoreHandle store, Coord coord, Select<Record1<String>> classes
    ) {
        if (coord.memberName() == null) return null;
        return multiset(select(JAVA_METHOD_DECLARATION.CLASS_NAME, JAVA_METHOD_DECLARATION.METHOD_NAME,
                JAVA_METHOD_DECLARATION.PARAMETER_COUNT, JAVA_METHOD_DECLARATION.JAVADOC,
                JAVA_METHOD_DECLARATION.FILE, JAVA_METHOD_DECLARATION.SOURCE_LINE,
                JAVA_METHOD_DECLARATION.SOURCE_COLUMN)
            .from(JAVA_METHOD_DECLARATION)
            .where(JAVA_METHOD_DECLARATION.CLASS_NAME.in(classes))
            .and(JAVA_METHOD_DECLARATION.METHOD_NAME.in(candidateMethodNames(store, coord)))
            .orderBy(JAVA_METHOD_DECLARATION.FILE, JAVA_METHOD_DECLARATION.ORDINAL))
            .convertFrom(rows -> rows.map(row -> new MethodRow(row.value1(), row.value2(),
                row.value3(), text(row.value4()),
                SourceDeclarations.locationOf(row.value5(), row.value6(), row.value7()))));
    }

    /**
     * The call surfaces the coordinate's {@code @routine} applications resolve to, in written order
     * and then in schema order within one application. The class, the method and the arity all come
     * from the one relation, so the pair a routine-backed field binds to is a row like every other and
     * nothing has to be handed in from outside the statement.
     */
    private static Field<List<RoutineMethod>> routineMethodArm(StoreHandle store, Coord coord) {
        if (coord.memberName() == null) return null;
        return multiset(select(INTENT_FIELD_ROUTINE_METHOD.CLASS_NAME,
                INTENT_FIELD_ROUTINE_METHOD.METHOD_NAME, INTENT_FIELD_ROUTINE_METHOD.PARAMETERS)
            .from(INTENT_FIELD_ROUTINE_METHOD)
            .where(routineCoordinate(store, coord))
            .orderBy(INTENT_FIELD_ROUTINE_METHOD.ORDINAL, INTENT_FIELD_ROUTINE_METHOD.TABLE_SCHEMA,
                INTENT_FIELD_ROUTINE_METHOD.ROUTINE_NAME))
            .convertFrom(rows -> rows.map(Records.mapping(RoutineMethod::new)));
    }

    // ===== The candidate populations the java-source arms are admitted by =====

    /**
     * Every class the coordinate could resolve to: a candidate table's generated class, a candidate
     * backing class, a class a candidate backing is the record of, and at the field grain the class a
     * producer reference names and the {@code Routines} class a {@code @routine} application resolves
     * to. A union rather than a list of arms because the three java-source arms ask about a class name
     * and not about the relation that nominated it.
     */
    private static Select<Record1<String>> candidateClasses(StoreHandle store, Coord coord) {
        var classes = select(SQL_TABLE.CLASS_FQN)
            .from(INTENT_BOUND_TABLE)
            .join(SQL_TABLE).on(tableKeyOf(INTENT_BOUND_TABLE.TABLE_SOURCE_NAME,
                INTENT_BOUND_TABLE.TABLE_SCHEMA, INTENT_BOUND_TABLE.TABLE_NAME))
            .where(INTENT_BOUND_TABLE.GRAPH_NAME.eq(store.graphName()))
            .and(INTENT_BOUND_TABLE.TYPE_NAME.eq(coord.typeName()))
            .and(store.reads(SQL_TABLE.SOURCE_NAME))
            .union(select(SQL_TABLE.CLASS_FQN)
                .from(INTENT_TYPE_BACKING)
                .join(SQL_TABLE).on(SQL_TABLE.RECORD_CLASS_FQN.eq(INTENT_TYPE_BACKING.CLASS_NAME))
                .where(INTENT_TYPE_BACKING.GRAPH_NAME.eq(store.graphName()))
                .and(INTENT_TYPE_BACKING.TYPE_NAME.eq(coord.typeName()))
                .and(store.reads(SQL_TABLE.SOURCE_NAME)))
            .union(backingCandidates(store, coord));
        if (coord.memberName() == null) return classes;
        return classes.union(select(INTENT_FIELD_PRODUCER_REFERENCE.CLASS_NAME)
            .from(INTENT_FIELD_PRODUCER_REFERENCE)
            .where(coordinate(INTENT_FIELD_PRODUCER_REFERENCE.GRAPH_NAME.eq(store.graphName()),
                INTENT_FIELD_PRODUCER_REFERENCE.TYPE_NAME.eq(coord.typeName()),
                INTENT_FIELD_PRODUCER_REFERENCE.FIELD_NAME.eq(coord.memberName()))))
            .union(select(INTENT_FIELD_ROUTINE_METHOD.CLASS_NAME)
                .from(INTENT_FIELD_ROUTINE_METHOD)
                .where(routineCoordinate(store, coord)));
    }

    /** Both backing populations, which is what a class-keyed arm asks about without choosing between them. */
    private static Select<Record1<String>> backingCandidates(StoreHandle store, Coord coord) {
        return select(INTENT_TYPE_BACKING_SEED.CLASS_NAME)
            .from(INTENT_TYPE_BACKING_SEED)
            .where(INTENT_TYPE_BACKING_SEED.GRAPH_NAME.eq(store.graphName()))
            .and(INTENT_TYPE_BACKING_SEED.TYPE_NAME.eq(coord.typeName()))
            .union(select(INTENT_TYPE_BACKING.CLASS_NAME)
                .from(INTENT_TYPE_BACKING)
                .where(INTENT_TYPE_BACKING.GRAPH_NAME.eq(store.graphName()))
                .and(INTENT_TYPE_BACKING.TYPE_NAME.eq(coord.typeName())));
    }

    /**
     * The generated names of the columns the member reaches, which is what a table class declares its
     * constants under where that name is not the SQL one in another case.
     */
    private static Select<Record1<String>> candidateFieldNames(StoreHandle store, Coord coord) {
        return select(SQL_COLUMN.JOOQ_NAME)
            .from(SQL_COLUMN)
            .where(store.reads(SQL_COLUMN.SOURCE_NAME))
            .and(SQL_COLUMN.JOOQ_NAME.equalIgnoreCase(coord.memberName())
                .or(SQL_COLUMN.COLUMN_NAME.equalIgnoreCase(coord.memberName())))
            .and(inScope(store, coord));
    }

    /**
     * Every method name the coordinate could bind to: what a producer reference spells, the accessor a
     * candidate backing class offers under the member name, and the generated call a {@code @routine}
     * application resolves to.
     */
    private static Select<Record1<String>> candidateMethodNames(StoreHandle store, Coord coord) {
        return select(INTENT_FIELD_PRODUCER_REFERENCE.METHOD_NAME)
            .from(INTENT_FIELD_PRODUCER_REFERENCE)
            .where(coordinate(INTENT_FIELD_PRODUCER_REFERENCE.GRAPH_NAME.eq(store.graphName()),
                INTENT_FIELD_PRODUCER_REFERENCE.TYPE_NAME.eq(coord.typeName()),
                INTENT_FIELD_PRODUCER_REFERENCE.FIELD_NAME.eq(coord.memberName())))
            .union(select(INTENT_CLASS_MEMBER_SLOT.ACCESSOR_METHOD_NAME)
                .from(INTENT_CLASS_MEMBER_SLOT)
                .where(store.reads(INTENT_CLASS_MEMBER_SLOT.SOURCE_NAME))
                .and(INTENT_CLASS_MEMBER_SLOT.SLOT_NAME.eq(coord.memberName()))
                .and(INTENT_CLASS_MEMBER_SLOT.CLASS_NAME.in(backingCandidates(store, coord))))
            .union(select(INTENT_FIELD_ROUTINE_METHOD.METHOD_NAME)
                .from(INTENT_FIELD_ROUTINE_METHOD)
                .where(routineCoordinate(store, coord)));
    }

    /**
     * Whether a census column belongs to a table the type's scope could reach, which is either of the
     * two scope arms. A disjunction of existence rather than a join, so a column is admitted once
     * however many candidates name its table.
     */
    private static Condition inScope(StoreHandle store, Coord coord) {
        var bound = exists(select(INTENT_BOUND_TABLE.TYPE_NAME)
            .from(INTENT_BOUND_TABLE)
            .where(INTENT_BOUND_TABLE.GRAPH_NAME.eq(store.graphName()))
            .and(INTENT_BOUND_TABLE.TYPE_NAME.eq(coord.typeName()))
            .and(INTENT_BOUND_TABLE.TABLE_SOURCE_NAME.eq(SQL_COLUMN.SOURCE_NAME))
            .and(INTENT_BOUND_TABLE.TABLE_SCHEMA.eq(SQL_COLUMN.TABLE_SCHEMA))
            .and(INTENT_BOUND_TABLE.TABLE_NAME.eq(SQL_COLUMN.TABLE_NAME)));
        var record = SQL_TABLE.as("scope_table");
        var redirected = exists(select(INTENT_TYPE_BACKING.TYPE_NAME)
            .from(INTENT_TYPE_BACKING)
            .join(record).on(record.RECORD_CLASS_FQN.eq(INTENT_TYPE_BACKING.CLASS_NAME))
            .where(INTENT_TYPE_BACKING.GRAPH_NAME.eq(store.graphName()))
            .and(INTENT_TYPE_BACKING.TYPE_NAME.eq(coord.typeName()))
            .and(record.RECORD_CLASS_FQN.ne(NO_RECORD_CLASS))
            .and(record.SOURCE_NAME.eq(SQL_COLUMN.SOURCE_NAME))
            .and(record.TABLE_SCHEMA.eq(SQL_COLUMN.TABLE_SCHEMA))
            .and(record.TABLE_NAME.eq(SQL_COLUMN.TABLE_NAME)));
        return bound.or(redirected);
    }

    // ===== Shared pieces =====

    /** The census's own sentinel for a table jOOQ generated no record class for. */
    private static final String NO_RECORD_CLASS = "org.jooq.Record";

    private static Condition tableKeyOf(
        TableField<?, String> sourceName, TableField<?, String> schema, TableField<?, String> tableName
    ) {
        return SQL_TABLE.SOURCE_NAME.eq(sourceName)
            .and(SQL_TABLE.TABLE_SCHEMA.eq(schema))
            .and(SQL_TABLE.TABLE_NAME.eq(tableName));
    }

    /** The three-part coordinate every field-grain read here is keyed on. */
    private static Condition coordinate(Condition graph, Condition type, Condition field) {
        return graph.and(type).and(field);
    }

    /** That coordinate on the routine relation, whose fourth key part the field grain does not name. */
    private static Condition routineCoordinate(StoreHandle store, Coord coord) {
        return coordinate(INTENT_FIELD_ROUTINE_METHOD.GRAPH_NAME.eq(store.graphName()),
            INTENT_FIELD_ROUTINE_METHOD.TYPE_NAME.eq(coord.typeName()),
            INTENT_FIELD_ROUTINE_METHOD.FIELD_NAME.eq(coord.memberName()));
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }
}
