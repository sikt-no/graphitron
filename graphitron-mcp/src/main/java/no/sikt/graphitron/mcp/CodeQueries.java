package no.sikt.graphitron.mcp;

import no.sikt.graphitron.model.boot.StoreAnswer;
import no.sikt.graphitron.model.boot.StoreReader;
import no.sikt.graphitron.model.read.StoreHandle;
import org.jooq.Condition;
import org.jooq.Field;
import org.jooq.Records;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import static no.sikt.graphitron.model.Tables.CODE_CONDITION_METHOD;
import static no.sikt.graphitron.model.Tables.CODE_METHOD;
import static no.sikt.graphitron.model.Tables.CODE_METHOD_PARAMETER;
import static no.sikt.graphitron.model.Tables.CODE_CONSTRUCTION;
import static no.sikt.graphitron.model.Tables.CODE_WRITE_SLOT;
import static no.sikt.graphitron.model.Tables.CODE_SERVICE_METHOD;
import static no.sikt.graphitron.model.Tables.CODE_TYPE;
import static no.sikt.graphitron.model.Tables.JAVA_CLASS_DECLARATION;
import static no.sikt.graphitron.model.Tables.JAVA_METHOD_DECLARATION;
import static org.jooq.impl.DSL.exists;
import static org.jooq.impl.DSL.multiset;
import static org.jooq.impl.DSL.noCondition;
import static org.jooq.impl.DSL.select;
import static org.jooq.impl.DSL.selectDistinct;
import static org.jooq.impl.DSL.selectOne;

/**
 * This module's reads over the {@code code_} reading of the classfiles and the {@code java_}
 * declaration family, shaped by what the {@code code} tool puts on the wire.
 *
 * <p>Two reads, and the boundary between them is the store's rather than this module's. What a
 * classfile declares and where a source file writes it are two questions: the reading keys a method by
 * {@code (source_name, class_name, method_name, descriptor)} where the source is a classpath entry and
 * the descriptor a JVM signature, and {@code java_method_declaration} keys a declaration by
 * {@code (file, class_name, method_name, ordinal)} where the file is a source path and the ordinal is
 * declaration order. Neither key is the other's, and the families say so: a class declaration's name is
 * documented as the join key to the census "matched by name and by nothing else", and the method
 * declarations hold "one row per declaration, not one per resolvable name" so that "a consumer asking
 * for a name gets as many rows as the class declares and the count is the resolution outcome". So the
 * pairing happens here, on the key the store names, and the row count is read as the outcome the store
 * says it is. Nothing joins a descriptor to an ordinal, and no name matching several declarations is
 * resolved by picking the first.
 *
 * <p>Each read is one projection at its own grain. The reading's half is a class row carrying a
 * {@code MULTISET} of its methods, each carrying its parameters, beside a {@code MULTISET} of its record
 * components; the declaration read is a class-declaration row carrying a {@code MULTISET} of the method
 * declarations written in that same file. Nothing is grouped afterwards except the two row counts the
 * store defines as resolution outcomes.
 *
 * <p>Every kind here is an arm's own population rather than a predicate this module spells over a
 * general index. That was true of the condition kind first and is now true of all three, which is
 * what let the general index go: what may be written at a directive is the arm's answer, so asking
 * the arm is both cheaper and the only way the admission rule cannot drift from the one that admits.
 * There is no class relation under them and none is needed: a class appears because it declares
 * something the kind asks about, so the population is the distinct classes of the arm and a class
 * that declares nothing was never in any of the three.
 *
 * <p>Every type on this wire is the declared form rather than the erasure, which here is not a
 * choice between two columns but the only form the reading keeps: a type is keyed by how the source
 * wrote it, and {@code code_type.display_name} is that spelling with its packages dropped, which is
 * what an author reads in a signature and what this tool hands an agent.
 */
final class CodeQueries {

    private CodeQueries() {}

    /** Default page size for the {@code code} tool: well under MCP response limits, paged by cursor. */
    static final int DEFAULT_LIMIT = 100;

    /**
     * What an agent is looking for, which is the whole of what the old three tools were: one reading,
     * three arms of it.
     *
     * <p>Each kind is a relation rather than a predicate, and each of those relations answers the
     * question an author asks at a directive: not "what does the classpath contain" but "what may I
     * name here". So the populations are the reactor's, which is where something nameable lives, and
     * a method that is already an answer to another directive is not offered as an answer to this
     * one. An agent asking what it can write is told what it can write.
     */
    enum Kind {

        /**
         * Classes declaring at least one candidate for {@code @service}. Narrower than "declares a
         * public method", and deliberately: a condition method and a lifter are the contracts of
         * other directives, so neither is a service candidate, and a library on the classpath is not
         * something this reactor's schema may name.
         */
        SERVICE,

        /** Classes declaring at least one method admissible at {@code @condition(condition:)}. */
        CONDITION,

        /**
         * Classes a value of which this generator can make, with the members that go in and the
         * call each goes in through.
         *
         * <p>Named for what it answers rather than for the directive it replaces. {@code @record}
         * is deprecated and ignored, a class-backed type's class being reflected from the field
         * that produces it, and "record" names three different things across a jOOQ record, a Java
         * record and a row, so a kind carrying that word would say the least useful of the three.
         * What an author is actually asking is what can be on the receiving end of an input, and
         * that is a question about construction.
         */
        CONSTRUCTIBLE;

        /** The kind {@code value} names, absent when it names none; the wire spells these lower-case. */
        static Optional<Kind> parse(String value) {
            if (value == null) return Optional.empty();
            try {
                return Optional.of(valueOf(value.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ignored) {
                return Optional.empty();
            }
        }

        /** The accepted spellings, for the message a bad argument gets. */
        static String spellings() {
            return String.join(", ", Arrays.stream(values())
                .map(k -> k.name().toLowerCase(Locale.ROOT)).toList());
        }
    }

    /**
     * One class on the {@code code} wire: what the census says the class declares.
     *
     * <p>Both lists are carried whatever the {@link Kind} asked for, so one call answers for a class
     * that is more than one thing where the three tools this replaced each answered half of it. The
     * kind selects which classes appear, and narrows {@code methods} only where it names a method
     * population rather than a class one, which {@link Kind#CONDITION} is and the other two are not.
     */
    record ClassEntry(String className, List<MethodEntry> methods, List<ComponentEntry> components) {}

    /**
     * One public method, with its declared return type and its parameters in position order.
     *
     * @param returnType the declared form, so a container's element type survives
     */
    record MethodEntry(String name, String returnType, List<ParameterEntry> parameters) {}

    /**
     * One parameter.
     *
     * @param name {@code null} where the class was compiled without {@code -parameters}, which the
     *     census records as absence rather than synthesising a positional stand-in
     * @param type the declared form, on {@link MethodEntry#returnType}'s terms
     */
    record ParameterEntry(String name, String type) {}

    /** One member that goes into the class when one is made, and what it is filled with. */
    record ComponentEntry(String name, String type) {}

    /**
     * A page of census classes, the size of the filtered census it was drawn from, and the cursor for
     * the next page, absent on the last one.
     *
     * @param total the whole filtered census rather than what is left after the cursor, which is what
     *     the summary line reports and what tells an agent whether paging is worth starting
     */
    record ClassPage(List<ClassEntry> classes, int total, Optional<String> nextCursor) {}

    /** A page of the census beside what the declaration family says about the classes on it. */
    record CodeAnswer(ClassPage page, Declarations declarations) {}

    /**
     * Reads one page of the census and the declarations for exactly the classes on it, through
     * {@code reader} rather than the handle the single-query tools use.
     *
     * <p>The reader for the plain reason and not for a consistency one: an answer here is two
     * statements, and a second statement on the session writer's connection is a savepoint rather
     * than a transaction boundary. There is nothing to hold consistent between them. The two families
     * refresh on independent cadences by design, so a declaration family that lags the census is the
     * ordinary case and surfaces as {@link Declaration.NotIndexed} rather than as an inconsistency.
     *
     * @param graphName the graph whose partition the census read is confined to, named by the handle
     *     the host gave this module alongside the reader. Passed rather than held because the scope is
     *     rebuilt per transaction: the {@code DSLContext} a read is handed is valid for that call only
     */
    static StoreAnswer<CodeAnswer> read(
        StoreReader reader, String graphName, Kind kind, Optional<String> nameSubstring,
        Optional<String> cursor, int limit
    ) {
        return reader.read(dsl -> {
            var store = new StoreHandle(dsl, graphName);
            var page = classes(store, kind, nameSubstring, cursor, limit);
            return new CodeAnswer(page, declarations(store,
                page.classes().stream().map(ClassEntry::className).toList()));
        });
    }

    // ---- the reading's half ----

    /**
     * The classes of the requested kind, ordered by class name, optionally narrowed to a
     * case-insensitive substring of it and bounded by {@code limit} in SQL.
     *
     * <p>The population is the distinct classes of the kind's own arm, which is what the three kinds
     * are: a class appears because it declares something the kind asks about. Distinct because an arm
     * holds methods and a class declaring three of them is still one class, and keyed on the entry
     * beside the name because a name is not an identity across classpath entries.
     *
     * <p>Paging is keyset on the class name, which is both the order and the cursor. The name
     * identifies a row within a graph: a class present under more than one classpath entry is read
     * once within a run, at the entry a classloader would resolve it from, so two rows under one name
     * inside one graph's scope would mean two runs' entries had been folded into one graph.
     *
     * <p>The bound is fetched as {@code limit + 1} rows so the last page is recognised by what came
     * back rather than by a second count, and the extra row is dropped before it reaches the wire.
     */
    private static ClassPage classes(
        StoreHandle store, Kind kind, Optional<String> nameSubstring, Optional<String> cursor, int limit
    ) {
        var arm = armOf(kind);
        var filters = new ArrayList<Condition>();
        filters.add(store.reads(arm.source()));
        filters.add(arm.narrowing());
        nameSubstring.ifPresent(n -> filters.add(arm.className().containsIgnoreCase(n)));

        var population = selectDistinct(arm.source().as(SOURCE), arm.className().as(CLASS))
            .from(arm.relation())
            .where(filters)
            .asTable("population");
        var source = population.field(SOURCE, String.class);
        var className = population.field(CLASS, String.class);

        int total = store.dsl().fetchCount(population);

        var page = new ArrayList<Condition>();
        McpWire.decodeKeysetCursor(cursor.orElse(null), 1)
            .ifPresent(key -> page.add(className.gt(key.getFirst())));

        var rows = store.dsl()
            .select(className, methods(source, className, narrowing(kind)),
                components(source, className))
            .from(population)
            .where(page)
            .orderBy(className.asc())
            .limit(limit + 1)
            .fetch(Records.mapping(ClassEntry::new));

        var entries = List.copyOf(rows.subList(0, Math.min(limit, rows.size())));
        var nextCursor = rows.size() > entries.size() && !entries.isEmpty()
            ? Optional.of(McpWire.encodeKeysetCursor(List.of(entries.getLast().className())))
            : Optional.<String>empty();
        return new ClassPage(entries, total, nextCursor);
    }

    /** The two columns the population projects, named so the correlations can find them. */
    private static final String SOURCE = "source_name";
    private static final String CLASS = "class_name";

    /**
     * The relation whose rows are the kind's population, and how a class is named on it.
     *
     * <p>Each kind is a relation rather than a predicate this module spells over a general index,
     * which is the whole of what the kinds are: what may be written at a directive is the store's
     * own answer, so the admission rule is not stated here and cannot drift from the one that
     * admits, and the population is the reactor, which is where something an author may name lives.
     *
     * <p>The third names a class by its type rather than by a method's owner, because what it is
     * about is the class and not a member of one. It is also the one whose population is reached
     * rather than admitted: a class is constructible here because something is passed it, so a
     * class nothing takes has no row however makeable it looks.
     */
    private record Population(org.jooq.Table<?> relation, Field<String> source,
                              Field<String> className, Condition narrowing) {}

    private static Population armOf(Kind kind) {
        return switch (kind) {
            case SERVICE -> new Population(CODE_SERVICE_METHOD, CODE_SERVICE_METHOD.SOURCE_NAME,
                CODE_SERVICE_METHOD.CLASS_NAME, noCondition());
            case CONDITION -> new Population(CODE_CONDITION_METHOD,
                CODE_CONDITION_METHOD.SOURCE_NAME, CODE_CONDITION_METHOD.CLASS_NAME,
                noCondition());
            case CONSTRUCTIBLE -> new Population(CODE_CONSTRUCTION, CODE_CONSTRUCTION.SOURCE_NAME,
                CODE_CONSTRUCTION.TYPE_NAME, noCondition());
        };
    }

    /**
     * How the projected method list is narrowed for one kind. Only {@link Kind#CONDITION} names a
     * method population, so it is the only kind that narrows: an agent that asked for condition
     * methods is answered with the condition methods, where one that asked for a class population is
     * answered with the whole class.
     */
    private static Condition narrowing(Kind kind) {
        return kind == Kind.CONDITION
            ? exists(selectOne().from(CODE_CONDITION_METHOD).where(memberOf(CODE_CONDITION_METHOD)))
            : noCondition();
    }

    /**
     * The correlation from a projected method to an arm that admits it, on the whole method key so
     * one overload of a name cannot stand in for another.
     */
    private static Condition memberOf(org.jooq.Table<?> arm) {
        return arm.field(CODE_METHOD.SOURCE_NAME).eq(CODE_METHOD.SOURCE_NAME)
            .and(arm.field(CODE_METHOD.CLASS_NAME).eq(CODE_METHOD.CLASS_NAME))
            .and(arm.field(CODE_METHOD.METHOD_NAME).eq(CODE_METHOD.METHOD_NAME))
            .and(arm.field(CODE_METHOD.DESCRIPTOR).eq(CODE_METHOD.DESCRIPTOR));
    }

    /**
     * The class's public methods narrowed by {@code narrowing}, each carrying its own parameters and
     * the rendering of what it hands back.
     *
     * <p>The type is a join rather than a column because that is the relation's shape: a method's
     * result names a type, and how a type renders is the type's own property, stated once per type
     * rather than once per method returning one. Both joins are on whole primary keys.
     *
     * <p>Ordered by name then descriptor. The reading gives a method no declaration order to carry,
     * the classfile's method order being an encoding detail, so the order is the one thing that keys
     * the method: overloads sort under their shared name by the descriptor that tells them apart.
     */
    private static Field<List<MethodEntry>> methods(
        Field<String> source, Field<String> className, Condition narrowing
    ) {
        var result = CODE_TYPE.as("result_type");
        return multiset(
            select(CODE_METHOD.METHOD_NAME, result.DISPLAY_NAME, parameters())
                .from(CODE_METHOD)
                .join(result).on(result.SOURCE_NAME.eq(CODE_METHOD.SOURCE_NAME)
                    .and(result.TYPE_NAME.eq(CODE_METHOD.RESULT_TYPE)))
                .where(CODE_METHOD.SOURCE_NAME.eq(source))
                .and(CODE_METHOD.CLASS_NAME.eq(className))
                .and(narrowing)
                .orderBy(CODE_METHOD.METHOD_NAME.asc(), CODE_METHOD.DESCRIPTOR.asc()))
            .convertFrom(r -> r.map(Records.mapping(MethodEntry::new)));
    }

    /** One method's parameters in position order, correlated to the method being projected. */
    private static Field<List<ParameterEntry>> parameters() {
        var bound = CODE_TYPE.as("parameter_type");
        return multiset(
            select(CODE_METHOD_PARAMETER.PARAMETER_NAME, bound.DISPLAY_NAME)
                .from(CODE_METHOD_PARAMETER)
                .join(bound).on(bound.SOURCE_NAME.eq(CODE_METHOD_PARAMETER.SOURCE_NAME)
                    .and(bound.TYPE_NAME.eq(CODE_METHOD_PARAMETER.PARAMETER_TYPE)))
                .where(CODE_METHOD_PARAMETER.SOURCE_NAME.eq(CODE_METHOD.SOURCE_NAME)
                    .and(CODE_METHOD_PARAMETER.CLASS_NAME.eq(CODE_METHOD.CLASS_NAME))
                    .and(CODE_METHOD_PARAMETER.METHOD_NAME.eq(CODE_METHOD.METHOD_NAME))
                    .and(CODE_METHOD_PARAMETER.DESCRIPTOR.eq(CODE_METHOD.DESCRIPTOR)))
                .orderBy(CODE_METHOD_PARAMETER.POSITION.asc()))
            .convertFrom(r -> r.map(Records.mapping(ParameterEntry::new)));
    }

    /**
     * The members that go into the class when one is made, and what each is filled with.
     *
     * <p>Ordered by the argument position each goes in at, which is the order a caller passes them
     * where they all go in at once and is arbitrary but stable where they go in one at a time. The
     * shape says which of the two a reader is looking at, so the ordering does not have to carry
     * that as well.
     *
     * <p>Empty for a class nothing constructs, which is the same silence a class with no readable
     * members gives on the other side.
     */
    private static Field<List<ComponentEntry>> components(
        Field<String> source, Field<String> className
    ) {
        var declared = CODE_TYPE.as("slot_type");
        return multiset(
            select(CODE_WRITE_SLOT.SLOT_NAME, declared.DISPLAY_NAME)
                .from(CODE_WRITE_SLOT)
                .join(declared).on(declared.SOURCE_NAME.eq(CODE_WRITE_SLOT.SOURCE_NAME)
                    .and(declared.TYPE_NAME.eq(CODE_WRITE_SLOT.SLOT_TYPE)))
                .where(CODE_WRITE_SLOT.SOURCE_NAME.eq(source))
                .and(CODE_WRITE_SLOT.TYPE_NAME.eq(className))
                .orderBy(CODE_WRITE_SLOT.POSITION.asc(), CODE_WRITE_SLOT.SLOT_NAME.asc()))
            .convertFrom(r -> r.map(Records.mapping(ComponentEntry::new)));
    }

    // ---- the declaration read ----

    /**
     * What the declaration family says about one census coordinate: where a source writes it and what
     * doc comment it carries, or which of three ways the question has no single answer.
     *
     * <p>The three absent arms are three different facts and the wire keeps them apart. A family that
     * never reached the class is a source cadence that has not caught up, and re-walking fixes it. A
     * class it reached that declares no such method is a source that genuinely does not write what the
     * classfile carries, which the families are documented as allowed to disagree about. A name several
     * declarations answer to is the resolution outcome the store defines, not a pick to be made here.
     */
    sealed interface Declaration {

        /**
         * Exactly one declaration answers, at {@code position} where the parse positioned it.
         *
         * @param position absent where the parse read a declaration it could not position, which the
         *     family keeps room for precisely so the doc comment survives without one
         */
        record Declared(Optional<McpWire.Position> position, String javadoc) implements Declaration {}

        /** More than one declaration answers to the name, so the count is the answer. */
        record Ambiguous() implements Declaration {}

        /** The family holds the class and no declaration of this method on it. */
        record NotDeclared() implements Declaration {}

        /** The family holds no declaration of the class at all. */
        record NotIndexed() implements Declaration {}
    }

    /**
     * The declaration family's answer for the classes of one page, keyed by class name because that is
     * the key the family documents for reaching the census.
     *
     * <p>Unscoped by graph, deliberately and on the family's own terms: it partitions on the source
     * file rather than on a source membership, so a declaration is a fact about a file every graph in
     * the session shares.
     */
    static final class Declarations {

        private static final Declarations EMPTY = new Declarations(Map.of());

        private final Map<String, List<DeclaredFile>> byClassName;

        private Declarations(Map<String, List<DeclaredFile>> byClassName) {
            this.byClassName = byClassName;
        }

        /** Where a source writes the class, and the doc comment on that declaration. */
        Declaration ofClass(String className) {
            var files = byClassName.get(className);
            if (files == null || files.isEmpty()) return new Declaration.NotIndexed();
            if (files.size() > 1) return new Declaration.Ambiguous();
            var file = files.getFirst();
            return new Declaration.Declared(McpWire.position(file.file(), file.line(), file.column()),
                text(file.javadoc()));
        }

        /**
         * Where a source writes the overload of {@code methodName} declaring {@code arity} parameters.
         *
         * <p>Arity rather than the descriptor, because arity is the only ground the two families share:
         * a parse reads parameter types as written where the classfile carries erased ones, so the
         * declaration relation counts parameters and the census spells a descriptor. An arity the
         * source does not declare is {@link Declaration.NotDeclared} rather than a fallback to another
         * overload; the census asked about one method.
         *
         * <p>A class name several files declare makes every method on it ambiguous too, there being no
         * basis for reading a method declaration out of one of them.
         */
        Declaration ofMethod(String className, String methodName, int arity) {
            var files = byClassName.get(className);
            if (files == null || files.isEmpty()) return new Declaration.NotIndexed();
            if (files.size() > 1) return new Declaration.Ambiguous();
            var file = files.getFirst();
            var matches = file.methods().stream()
                .filter(m -> m.methodName().equals(methodName) && m.parameterCount() == arity)
                .toList();
            if (matches.isEmpty()) return new Declaration.NotDeclared();
            if (matches.size() > 1) return new Declaration.Ambiguous();
            var method = matches.getFirst();
            return new Declaration.Declared(McpWire.position(file.file(), method.line(), method.column()),
                text(method.javadoc()));
        }

        private static String text(String javadoc) {
            return javadoc == null ? "" : javadoc;
        }
    }

    /** One file's declaration of a class, with the method declarations written in that same file. */
    private record DeclaredFile(
        String className, String file, Integer line, Integer column, String javadoc,
        List<DeclaredMethod> methods
    ) {}

    /** One method declaration, at the arity the parse counted. */
    private record DeclaredMethod(
        String methodName, Integer parameterCount, Integer line, Integer column, String javadoc
    ) {}

    /**
     * Every declaration of the named classes, projected at the class-declaration grain with each
     * declaration carrying the method declarations written in its own file.
     *
     * <p>Nesting rather than a second read per class, and correlated on the whole
     * {@code (file, class_name)} key rather than on the name alone: a method declaration then cannot
     * be reported under a class declaration in a different file, which is exactly the pairing a name
     * that two files declare would otherwise invite.
     *
     * <p>No read at all for an empty page, an {@code IN} predicate over nothing being a query whose
     * answer is known.
     */
    private static Declarations declarations(StoreHandle store, List<String> classNames) {
        if (classNames.isEmpty()) return Declarations.EMPTY;

        var rows = store.dsl()
            .select(JAVA_CLASS_DECLARATION.CLASS_NAME, JAVA_CLASS_DECLARATION.FILE,
                JAVA_CLASS_DECLARATION.SOURCE_LINE, JAVA_CLASS_DECLARATION.SOURCE_COLUMN,
                JAVA_CLASS_DECLARATION.JAVADOC, methodDeclarations())
            .from(JAVA_CLASS_DECLARATION)
            .where(JAVA_CLASS_DECLARATION.CLASS_NAME.in(classNames))
            .orderBy(JAVA_CLASS_DECLARATION.CLASS_NAME.asc(), JAVA_CLASS_DECLARATION.FILE.asc())
            .fetch(Records.mapping(DeclaredFile::new));

        var byClassName = new LinkedHashMap<String, List<DeclaredFile>>();
        for (var row : rows) {
            byClassName.computeIfAbsent(row.className(), ignored -> new ArrayList<>()).add(row);
        }
        return new Declarations(byClassName);
    }

    /** One class declaration's method declarations, correlated on the declaring file and class. */
    private static Field<List<DeclaredMethod>> methodDeclarations() {
        return multiset(
            select(JAVA_METHOD_DECLARATION.METHOD_NAME, JAVA_METHOD_DECLARATION.PARAMETER_COUNT,
                JAVA_METHOD_DECLARATION.SOURCE_LINE, JAVA_METHOD_DECLARATION.SOURCE_COLUMN,
                JAVA_METHOD_DECLARATION.JAVADOC)
                .from(JAVA_METHOD_DECLARATION)
                .where(JAVA_METHOD_DECLARATION.FILE.eq(JAVA_CLASS_DECLARATION.FILE)
                    .and(JAVA_METHOD_DECLARATION.CLASS_NAME.eq(JAVA_CLASS_DECLARATION.CLASS_NAME)))
                .orderBy(JAVA_METHOD_DECLARATION.METHOD_NAME.asc(),
                    JAVA_METHOD_DECLARATION.ORDINAL.asc()))
            .convertFrom(r -> r.map(Records.mapping(DeclaredMethod::new)));
    }
}
