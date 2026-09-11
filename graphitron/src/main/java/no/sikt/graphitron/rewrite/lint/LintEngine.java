package no.sikt.graphitron.rewrite.lint;

import graphql.language.Argument;
import graphql.language.ArrayValue;
import graphql.language.DescribedNode;
import graphql.language.Description;
import graphql.language.Directive;
import graphql.language.DirectivesContainer;
import graphql.language.EnumTypeDefinition;
import graphql.language.EnumValueDefinition;
import graphql.language.FieldDefinition;
import graphql.language.InputObjectTypeDefinition;
import graphql.language.InputValueDefinition;
import graphql.language.InterfaceTypeDefinition;
import graphql.language.NamedNode;
import graphql.language.ObjectField;
import graphql.language.ObjectValue;
import graphql.language.Node;
import graphql.language.ObjectTypeDefinition;
import graphql.language.ScalarTypeDefinition;
import graphql.language.SourceLocation;
import graphql.language.StringValue;
import graphql.language.TypeDefinition;
import graphql.language.UnionTypeDefinition;
import graphql.language.Value;
import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;
import no.sikt.graphitron.model.diagnostics.BuildWarning;
import no.sikt.graphitron.model.read.StoreHandle;
import no.sikt.graphitron.model.schema.SchemaLoader;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import no.sikt.graphitron.model.lint.DeprecationRecognizer;
import no.sikt.graphitron.model.lint.LintFix;
import no.sikt.graphitron.model.lint.LintRule;

import static no.sikt.graphitron.model.Tables.GRAPHQL_DIRECTIVE_ARGUMENT;


/**
 * The SDL lint engine: one shared traversal over the parsed graphql-java AST that dispatches
 * each node to the {@link LintVisitor}s subscribed to its {@link LintNodeKind}. ESLint /
 * graphql-schema-linter idiom, build-side: graphitron already parses with graphql-java, and the
 * findings ride the existing {@link BuildWarning} channel into {@code ValidationReport}, so the LSP
 * and MCP project them with no second evaluator.
 *
 * <p>Only consumer-authored types are linted. graphitron's own bundled directive surface
 * ({@code directives.graphqls}: the directive definitions and their support input types like
 * {@code ExternalCodeReference}) is excluded by name, which is robust whether or not the parse
 * tracked source names (the in-memory test harness does not).
 */
public final class LintEngine {

    /** graphitron's own bundled type names; never linted (they are the generator's surface, not author input). */
    private static final Set<String> BUNDLED_TYPE_NAMES = computeBundledTypeNames();

    private final List<LintVisitor> visitors;
    private final Map<LintNodeKind, List<LintVisitor>> byKind;
    /** Author-owned type names matching one of these globs are skipped alongside the bundled surface.*/
    private final List<Pattern> excludedTypeMatchers;

    public LintEngine(List<LintVisitor> visitors) {
        this(visitors, List.of());
    }

    public LintEngine(List<LintVisitor> visitors, List<String> excludedTypePatterns) {
        this.visitors = List.copyOf(visitors);
        var map = new EnumMap<LintNodeKind, List<LintVisitor>>(LintNodeKind.class);
        for (var v : visitors) {
            for (var k : v.kinds()) {
                map.computeIfAbsent(k, ignored -> new ArrayList<>()).add(v);
            }
        }
        this.byKind = map;
        this.excludedTypeMatchers = excludedTypePatterns.stream().map(LintEngine::globToPattern).toList();
    }

    /** The engine wired with the built-in rule set, linting every author-owned type. */
    public static LintEngine builtIn() {
        return new LintEngine(LintRules.builtIn());
    }

    /**
     * The built-in engine that additionally skips author-owned types whose name matches one of
     * {@code excludedTypePatterns} (glob syntax; {@code *} any run, {@code ?} one char). This is the
 * {@code <lint>} block's {@code excludedTypes} axis: it widens the same skip boundary the
     * bundled-type exclusion uses, so the exclusion covers only the engine's AST walk.
     */
    public static LintEngine builtIn(List<String> excludedTypePatterns) {
        return new LintEngine(LintRules.builtIn(), excludedTypePatterns);
    }

    /**
     * Runs every registered visitor over {@code registry} in one traversal, returning the findings
     * as {@link BuildWarning.LintFinding}s in source-walk order. Excludes only graphitron's own
     * bundled surface; use {@link #run(TypeDefinitionRegistry, Set, StoreHandle)} to additionally exclude
     * federation-injected definitions.
     */
    public List<BuildWarning> run(TypeDefinitionRegistry registry, StoreHandle store) {
        return run(registry, Set.of(), store);
    }

    /**
     * As {@link #run(TypeDefinitionRegistry, StoreHandle)}, but also excludes the federation {@code @link}
     * injector's definitions ({@code injectedNames}, from
     * {@link no.sikt.graphitron.rewrite.AttributedRegistry#injectedNames()}). Both {@code injectedNames}
     * and {@code BUNDLED_TYPE_NAMES} are generator-owned surface the author never wrote and cannot
     * rename or document, so linting them is pure noise; the exclusion widens the existing
     * name-set skip to a second contributor rather than adding a new skip mechanism.
     */
    public List<BuildWarning> run(TypeDefinitionRegistry registry, Set<String> injectedNames,
                                  StoreHandle store) {
        var out = new ArrayList<BuildWarning>();
        var recognizer = new DeprecationRecognizer(store);
        var rootOps = rootOperationTypeNames(registry);
        var excluded = new LinkedHashSet<>(BUNDLED_TYPE_NAMES);
        excluded.addAll(injectedNames);

        for (TypeDefinition<?> def : registry.types().values()) {
            if (excluded.contains(def.getName()) || matchesExcludedType(def.getName())) continue;
            visitTypeDefinition(def, store, recognizer, rootOps, out);
        }
        for (ScalarTypeDefinition scalar : registry.scalars().values()) {
            if (excluded.contains(scalar.getName()) || matchesExcludedType(scalar.getName())) continue;
            dispatch(LintNodeKind.SCALAR_TYPE, scalar, scalar.getName(), false, store, recognizer, out);
            visitAppliedDirectives(scalar, scalar.getName(), store, recognizer, out);
        }
        return out;
    }

    /**
 * Whether a type name matches a configured {@code excludedTypes} glob. This widens the same
     * per-type skip boundary the bundled ({@code BUNDLED_TYPE_NAMES}) and federation-injected
     * ({@code injectedNames}) name-set exclusions use, and stays scoped to the engine's AST walk;
     * a classifier advisory on an excluded type still fires.
     */
    private boolean matchesExcludedType(String typeName) {
        for (Pattern matcher : excludedTypeMatchers) {
            if (matcher.matcher(typeName).matches()) return true;
        }
        return false;
    }

    /**
     * Translates a type-name glob ({@code *} any run, {@code ?} one char) into an anchored regex,
     * escaping every other regex metacharacter so a pattern like {@code Legacy*} matches literally.
     */
    private static Pattern globToPattern(String glob) {
        var sb = new StringBuilder();
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            switch (c) {
                case '*' -> sb.append(".*");
                case '?' -> sb.append('.');
                default -> {
                    if ("\\.[]{}()+-^$|".indexOf(c) >= 0) sb.append('\\');
                    sb.append(c);
                }
            }
        }
        return Pattern.compile(sb.toString());
    }

    private void visitTypeDefinition(
        TypeDefinition<?> def, StoreHandle store, DeprecationRecognizer recognizer,
        Set<String> rootOps, List<BuildWarning> out
    ) {
        String typeName = def.getName();
        boolean isRootOp = rootOps.contains(typeName);
        switch (def) {
            case ObjectTypeDefinition obj -> {
                dispatch(LintNodeKind.OBJECT_TYPE, obj, typeName, isRootOp, store, recognizer, out);
                visitAppliedDirectives(obj, typeName, store, recognizer, out);
                for (FieldDefinition f : obj.getFieldDefinitions()) {
                    visitField(f, typeName, isRootOp, store, recognizer, out);
                }
            }
            case InterfaceTypeDefinition iface -> {
                dispatch(LintNodeKind.INTERFACE_TYPE, iface, typeName, isRootOp, store, recognizer, out);
                visitAppliedDirectives(iface, typeName, store, recognizer, out);
                for (FieldDefinition f : iface.getFieldDefinitions()) {
                    visitField(f, typeName, isRootOp, store, recognizer, out);
                }
            }
            case UnionTypeDefinition union -> {
                dispatch(LintNodeKind.UNION_TYPE, union, typeName, isRootOp, store, recognizer, out);
                visitAppliedDirectives(union, typeName, store, recognizer, out);
            }
            case EnumTypeDefinition en -> {
                dispatch(LintNodeKind.ENUM_TYPE, en, typeName, isRootOp, store, recognizer, out);
                visitAppliedDirectives(en, typeName, store, recognizer, out);
                for (EnumValueDefinition v : en.getEnumValueDefinitions()) {
                    dispatch(LintNodeKind.ENUM_VALUE_DEFINITION, v, typeName, isRootOp, store, recognizer, out);
                    visitAppliedDirectives(v, typeName, store, recognizer, out);
                }
            }
            case InputObjectTypeDefinition input -> {
                dispatch(LintNodeKind.INPUT_OBJECT_TYPE, input, typeName, isRootOp, store, recognizer, out);
                visitAppliedDirectives(input, typeName, store, recognizer, out);
                for (InputValueDefinition v : input.getInputValueDefinitions()) {
                    dispatch(LintNodeKind.INPUT_FIELD_DEFINITION, v, typeName, isRootOp, store, recognizer, out);
                    visitAppliedDirectives(v, typeName, store, recognizer, out);
                }
            }
            default -> {
                // Other TypeDefinition subtypes (e.g. scalars handled separately; extensions reached
                // via their own registry accessors) are not linted in v1. No silent skip of a linted
                // kind: every linted kind has an explicit arm above.
            }
        }
    }

    private void visitField(
        FieldDefinition field, String enclosingType, boolean isRootOp,
        StoreHandle store, DeprecationRecognizer recognizer, List<BuildWarning> out
    ) {
        dispatch(LintNodeKind.FIELD_DEFINITION, field, enclosingType, isRootOp, store, recognizer, out);
        visitAppliedDirectives(field, enclosingType, store, recognizer, out);
        for (InputValueDefinition arg : field.getInputValueDefinitions()) {
            dispatch(LintNodeKind.ARGUMENT_DEFINITION, arg, enclosingType, isRootOp, store, recognizer, out);
            visitAppliedDirectives(arg, enclosingType, store, recognizer, out);
        }
    }

    private void visitAppliedDirectives(
        DirectivesContainer<?> container, String enclosingType,
        StoreHandle store, DeprecationRecognizer recognizer, List<BuildWarning> out
    ) {
        for (Directive d : container.getDirectives()) {
            dispatch(LintNodeKind.APPLIED_DIRECTIVE, d, enclosingType, false, store, recognizer, out);
            // Applied-directive arguments are encountered but deliberately not a dispatch target in
            // v1 (NOT_LINTED); the rules that care read the arguments off the directive node directly.
        }
    }

    private void dispatch(
        LintNodeKind kind, Node<?> node, String enclosingType, boolean isRootOp,
        StoreHandle store, DeprecationRecognizer recognizer, List<BuildWarning> out
    ) {
        var subscribers = byKind.get(kind);
        if (subscribers == null) return;
        var target = new LintTarget(kind, nameOf(node), descriptionOf(node), argumentsOf(node),
            enclosingType, isRootOp, node.getSourceLocation());
        for (LintVisitor visitor : subscribers) {
            var ctx = new SinkContext(visitor.rule(), node.getSourceLocation(), store, recognizer, out);
            visitor.inspect(target, ctx);
        }
    }

    /**
     * The three readings that turn a parse tree node into the values {@link LintTarget} carries.
     *
     * <p>Here rather than in the rules that used to do them, and that is the point of the move: each
     * is a column the fact store holds, so the day this traversal becomes a query the rules are
     * already reading what the query would hand them.
     */
    private static String nameOf(Node<?> node) {
        return node instanceof NamedNode<?> named ? named.getName() : null;
    }

    /**
     * The node's own description as written; see {@link LintTarget#description()} for why raw.
     *
     * <p>Off the interface rather than a switch over the kinds that have one. A switch would need a
     * default arm, and a default arm here is a claim: it says every kind not listed writes no
     * description, which was wrong for the three input positions and the enum value the moment they
     * were dispatched, and wrong silently, no rule happening to ask them. graphql-java already
     * answers the question for every node that can, so nothing is left to keep in step.
     */
    private static String descriptionOf(Node<?> node) {
        Description description =
            node instanceof DescribedNode<?> described ? described.getDescription() : null;
        return description == null ? null : description.getContent();
    }

    /**
     * An applied directive's arguments: each name against the value where the author wrote a
     * string, and against the object-field names written anywhere inside it. Empty at every other
     * node kind.
     *
     * <p>Both halves are columns the store holds, the value on the applied-argument row and the
     * names in {@code graphql_ast_value_entry}, whose {@code holder_line} is repeated on every node
     * of an expression so that "the names inside this argument" is one predicate rather than a
     * descent. Read off the parse tree here only because this traversal still is one.
     */
    private static Map<String, LintTarget.AppliedArgument> argumentsOf(Node<?> node) {
        if (!(node instanceof Directive directive)) {
            return Map.of();
        }
        var arguments = new LinkedHashMap<String, LintTarget.AppliedArgument>();
        for (Argument argument : directive.getArguments()) {
            var named = new LinkedHashSet<String>();
            collectFieldNames(argument.getValue(), named);
            arguments.put(argument.getName(), new LintTarget.AppliedArgument(
                argument.getValue() instanceof StringValue written ? written.getValue() : null,
                named));
        }
        return arguments;
    }

    /** Every object-field name in a written value, flattened, which is what the store's rows are. */
    private static void collectFieldNames(Value<?> value, Set<String> into) {
        switch (value) {
            case ObjectValue object -> {
                for (ObjectField field : object.getObjectFields()) {
                    into.add(field.getName());
                    collectFieldNames(field.getValue(), into);
                }
            }
            case ArrayValue array -> array.getValues().forEach(v -> collectFieldNames(v, into));
            case null, default -> { /* a scalar names no field */ }
        }
    }

    private static Set<String> rootOperationTypeNames(TypeDefinitionRegistry registry) {
        var names = new LinkedHashSet<String>();
        registry.schemaDefinition().ifPresentOrElse(
            sd -> sd.getOperationTypeDefinitions().forEach(op -> names.add(op.getTypeName().getName())),
            () -> {
                names.add("Query");
                names.add("Mutation");
                names.add("Subscription");
            });
        return names;
    }

    private static Set<String> computeBundledTypeNames() {
        var registry = new SchemaParser().parse(SchemaLoader.directivesSdl());
        var names = new LinkedHashSet<String>();
        names.addAll(registry.types().keySet());
        names.addAll(registry.scalars().keySet());
        return names;
    }

    /** Per-(visitor, node) sink: attributes each finding to the rule and the node's default location. */
    private record SinkContext(
        LintRule rule, SourceLocation defaultLocation, StoreHandle store,
        DeprecationRecognizer recognizer, List<BuildWarning> out
    ) implements LintContext {

        @Override
        public void report(String message) {
            out.add(BuildWarning.LintFinding.of(message, defaultLocation, rule));
        }

        @Override
        public void report(String message, LintFix fix) {
            out.add(BuildWarning.LintFinding.of(message, defaultLocation, rule, fix));
        }

        @Override
        public void reportAt(SourceLocation location, String message) {
            out.add(BuildWarning.LintFinding.of(message, location, rule));
        }

        @Override
        public DeprecationRecognizer deprecation() {
            return recognizer;
        }

        /**
         * The lint engine's own query, named where it is asked. A query belongs to the reader
         * that has the question: the store's schema is the shared contract, and a second
         * reader wanting this would read the same relation rather than this method.
         */
        @Override
        public String namedTypeOfDirectiveArgument(String directive, String argument) {
            var t = GRAPHQL_DIRECTIVE_ARGUMENT;
            return store.dsl().select(t.NAMED_TYPE).from(t)
                .where(t.GRAPH_NAME.eq(store.graphName()))
                .and(t.DIRECTIVE_NAME.eq(directive))
                .and(t.ARGUMENT_NAME.eq(argument))
                .fetchOne(t.NAMED_TYPE);
        }
    }
}
