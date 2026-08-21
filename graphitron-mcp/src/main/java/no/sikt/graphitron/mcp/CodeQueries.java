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

import static no.sikt.graphitron.model.Tables.JAVA_CLASS_DECLARATION;
import static no.sikt.graphitron.model.Tables.JAVA_METHOD_DECLARATION;
import static no.sikt.graphitron.model.Tables.JVM_CLASS;
import static no.sikt.graphitron.model.Tables.JVM_METHOD;
import static no.sikt.graphitron.model.Tables.JVM_METHOD_PARAMETER;
import static no.sikt.graphitron.model.Tables.JVM_RECORD_COMPONENT;
import static org.jooq.impl.DSL.exists;
import static org.jooq.impl.DSL.multiset;
import static org.jooq.impl.DSL.noCondition;
import static org.jooq.impl.DSL.select;
import static org.jooq.impl.DSL.selectOne;

/**
 * This module's reads over the {@code jvm_} classpath census and the {@code java_} declaration family,
 * shaped by what the {@code code} tool puts on the wire.
 *
 * <p>Two reads, and the boundary between them is the store's rather than this module's. What a
 * classfile declares and where a source file writes it are two questions: the census keys a method by
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
 * <p>Each read is one projection at its own grain. The census read is a class row carrying a
 * {@code MULTISET} of its methods, each carrying its parameters, beside a {@code MULTISET} of its record
 * components; the declaration read is a class-declaration row carrying a {@code MULTISET} of the method
 * declarations written in that same file. Nothing is grouped afterwards except the two row counts the
 * store defines as resolution outcomes.
 *
 * <p>Every type on this wire is the declared form rather than the erasure. The census carries both
 * deliberately, neither being a function of the other, and the two answer different questions: the
 * erasure is what a check on a type's identity compares, which is a store-side job, and the declared
 * form is what an author reads in a signature, which is what this tool hands an agent.
 */
final class CodeQueries {

    private CodeQueries() {}

    /** Default page size for the {@code code} tool: well under MCP response limits, paged by cursor. */
    static final int DEFAULT_LIMIT = 100;

    /**
     * What an agent is looking for, which is the whole of what the old three tools were: one census,
     * three predicates over it.
     *
     * <p>Each kind names a population the census can answer for and nothing more. In particular
     * {@link #SERVICE} is classes carrying callable methods, which is what {@code jvm_method}'s
     * presence says; whether the schema wires to one is a classification fact and lives nowhere in this
     * census. A record satisfies it too, its accessors being public methods, so the answer is the
     * classpath rather than a guess at intent.
     */
    enum Kind {

        /** Classes declaring at least one public method. */
        SERVICE,

        /** Classes declaring at least one method whose return type is a jOOQ {@code Condition}. */
        CONDITION,

        /**
         * Classes declaring at least one record component. A record with no components at all has
         * nothing to bind a type to and is absent, which is the population this replaces.
         */
        RECORD;

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

    /** One record component, in the position the record declaration gives it. */
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

    // ---- the census read ----

    /**
     * The census classes of the requested kind, ordered by class name, optionally narrowed to a
     * case-insensitive substring of it and bounded by {@code limit} in SQL.
     *
     * <p>Paging is keyset on the class name, which is both the order and the cursor. The name
     * identifies a row within a graph: a class present under more than one classpath entry is captured
     * once within a run, at the entry a classloader would resolve it from, so two rows under one name
     * inside one graph's scope would mean two runs' entries had been folded into one graph.
     *
     * <p>The bound is fetched as {@code limit + 1} rows so the last page is recognised by what came
     * back rather than by a second count, and the extra row is dropped before it reaches the wire.
     */
    private static ClassPage classes(
        StoreHandle store, Kind kind, Optional<String> nameSubstring, Optional<String> cursor, int limit
    ) {
        var filters = new ArrayList<Condition>();
        filters.add(store.reads(JVM_CLASS.SOURCE_NAME));
        filters.add(declaring(kind));
        nameSubstring.ifPresent(n -> filters.add(JVM_CLASS.CLASS_NAME.containsIgnoreCase(n)));

        int total = store.dsl().fetchCount(JVM_CLASS, filters);

        var page = new ArrayList<>(filters);
        McpWire.decodeKeysetCursor(cursor.orElse(null), 1)
            .ifPresent(key -> page.add(JVM_CLASS.CLASS_NAME.gt(key.getFirst())));

        var rows = store.dsl()
            .select(JVM_CLASS.CLASS_NAME, methods(narrowing(kind)), components())
            .from(JVM_CLASS)
            .where(page)
            .orderBy(JVM_CLASS.CLASS_NAME.asc())
            .limit(limit + 1)
            .fetch(Records.mapping(ClassEntry::new));

        var entries = List.copyOf(rows.subList(0, Math.min(limit, rows.size())));
        var nextCursor = rows.size() > entries.size() && !entries.isEmpty()
            ? Optional.of(McpWire.encodeKeysetCursor(List.of(entries.getLast().className())))
            : Optional.<String>empty();
        return new ClassPage(entries, total, nextCursor);
    }

    /**
     * The predicate selecting the classes of one kind: what the class declares, as an {@code EXISTS}
     * over the relation that declares it. A semi-join rather than a join, so a class with several
     * methods is still one row.
     */
    private static Condition declaring(Kind kind) {
        return switch (kind) {
            case SERVICE -> exists(selectOne().from(JVM_METHOD).where(methodOfClass()));
            case CONDITION -> exists(selectOne().from(JVM_METHOD).where(methodOfClass())
                .and(JVM_METHOD.RETURNS_CONDITION.isTrue()));
            case RECORD -> exists(selectOne().from(JVM_RECORD_COMPONENT).where(componentOfClass()));
        };
    }

    /**
     * How the projected method list is narrowed for one kind. Only {@link Kind#CONDITION} names a
     * method population, so it is the only kind that narrows: an agent that asked for condition
     * methods is answered with the condition methods, where one that asked for a class population is
     * answered with the whole class.
     */
    private static Condition narrowing(Kind kind) {
        return kind == Kind.CONDITION ? JVM_METHOD.RETURNS_CONDITION.isTrue() : noCondition();
    }

    /**
     * The predicate correlating a census child to the class row being projected, which is the foreign
     * key that relation declares against {@code jvm_class} spelled as a join. Every nested list hangs
     * off this rather than off a copied-down class name: a correlation the census guarantees cannot
     * pair a child with the wrong parent.
     */
    private static Condition methodOfClass() {
        return JVM_METHOD.SOURCE_NAME.eq(JVM_CLASS.SOURCE_NAME)
            .and(JVM_METHOD.CLASS_NAME.eq(JVM_CLASS.CLASS_NAME));
    }

    /** The same, for the record components. */
    private static Condition componentOfClass() {
        return JVM_RECORD_COMPONENT.SOURCE_NAME.eq(JVM_CLASS.SOURCE_NAME)
            .and(JVM_RECORD_COMPONENT.CLASS_NAME.eq(JVM_CLASS.CLASS_NAME));
    }

    /**
     * The class's public methods narrowed by {@code narrowing}, each carrying its own parameters.
     *
     * <p>Ordered by name then descriptor. The census gives a method no declaration order to carry, the
     * classfile's method order being an encoding detail, so the order is the one thing that keys the
     * method: overloads sort under their shared name by the descriptor that tells them apart.
     */
    private static Field<List<MethodEntry>> methods(Condition narrowing) {
        return multiset(
            select(JVM_METHOD.METHOD_NAME, JVM_METHOD.DECLARED_RETURN_TYPE, parameters())
                .from(JVM_METHOD)
                .where(methodOfClass())
                .and(narrowing)
                .orderBy(JVM_METHOD.METHOD_NAME.asc(), JVM_METHOD.DESCRIPTOR.asc()))
            .convertFrom(r -> r.map(Records.mapping(MethodEntry::new)));
    }

    /** One method's parameters in position order, correlated to the method being projected. */
    private static Field<List<ParameterEntry>> parameters() {
        return multiset(
            select(JVM_METHOD_PARAMETER.PARAMETER_NAME, JVM_METHOD_PARAMETER.DECLARED_PARAMETER_TYPE)
                .from(JVM_METHOD_PARAMETER)
                .where(JVM_METHOD_PARAMETER.SOURCE_NAME.eq(JVM_METHOD.SOURCE_NAME)
                    .and(JVM_METHOD_PARAMETER.CLASS_NAME.eq(JVM_METHOD.CLASS_NAME))
                    .and(JVM_METHOD_PARAMETER.METHOD_NAME.eq(JVM_METHOD.METHOD_NAME))
                    .and(JVM_METHOD_PARAMETER.DESCRIPTOR.eq(JVM_METHOD.DESCRIPTOR)))
                .orderBy(JVM_METHOD_PARAMETER.POSITION.asc()))
            .convertFrom(r -> r.map(Records.mapping(ParameterEntry::new)));
    }

    /** The class's record components in declaration order, empty for anything but a record. */
    private static Field<List<ComponentEntry>> components() {
        return multiset(
            select(JVM_RECORD_COMPONENT.COMPONENT_NAME, JVM_RECORD_COMPONENT.DECLARED_TYPE)
                .from(JVM_RECORD_COMPONENT)
                .where(componentOfClass())
                .orderBy(JVM_RECORD_COMPONENT.POSITION.asc()))
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
