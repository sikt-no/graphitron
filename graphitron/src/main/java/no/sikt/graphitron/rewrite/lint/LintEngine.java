package no.sikt.graphitron.rewrite.lint;

import graphql.language.Argument;
import graphql.language.Directive;
import graphql.language.DirectivesContainer;
import graphql.language.EnumTypeDefinition;
import graphql.language.EnumValueDefinition;
import graphql.language.FieldDefinition;
import graphql.language.InputObjectTypeDefinition;
import graphql.language.InputValueDefinition;
import graphql.language.InterfaceTypeDefinition;
import graphql.language.Node;
import graphql.language.ObjectTypeDefinition;
import graphql.language.ScalarTypeDefinition;
import graphql.language.SourceLocation;
import graphql.language.TypeDefinition;
import graphql.language.UnionTypeDefinition;
import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;
import no.sikt.graphitron.rewrite.BuildWarning;
import no.sikt.graphitron.rewrite.schema.RewriteSchemaLoader;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The SDL lint engine (R398): one shared traversal over the parsed graphql-java AST that dispatches
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
    /** Author-owned type names matching one of these globs are skipped alongside the bundled surface (R408). */
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
     * {@code <lint>} block's {@code excludedTypes} axis (R408): it widens the same skip boundary the
     * bundled-type exclusion uses, so the exclusion covers only the engine's AST walk.
     */
    public static LintEngine builtIn(List<String> excludedTypePatterns) {
        return new LintEngine(LintRules.builtIn(), excludedTypePatterns);
    }

    /**
     * Runs every registered visitor over {@code registry} in one traversal, returning the findings
     * as {@link BuildWarning.LintFinding}s in source-walk order. Excludes only graphitron's own
     * bundled surface; use {@link #run(TypeDefinitionRegistry, Set)} to additionally exclude
     * federation-injected definitions.
     */
    public List<BuildWarning> run(TypeDefinitionRegistry registry) {
        return run(registry, Set.of());
    }

    /**
     * As {@link #run(TypeDefinitionRegistry)}, but also excludes the federation {@code @link}
     * injector's definitions ({@code injectedNames}, from
     * {@link no.sikt.graphitron.rewrite.AttributedRegistry#injectedNames()}). Both {@code injectedNames}
     * and {@code BUNDLED_TYPE_NAMES} are generator-owned surface the author never wrote and cannot
     * rename or document, so linting them is pure noise (R407); the exclusion widens the existing
     * name-set skip to a second contributor rather than adding a new skip mechanism.
     */
    public List<BuildWarning> run(TypeDefinitionRegistry registry, Set<String> injectedNames) {
        var out = new ArrayList<BuildWarning>();
        var recognizer = new DeprecationRecognizer(registry);
        var rootOps = rootOperationTypeNames(registry);
        var excluded = new LinkedHashSet<>(BUNDLED_TYPE_NAMES);
        excluded.addAll(injectedNames);

        for (TypeDefinition<?> def : registry.types().values()) {
            if (excluded.contains(def.getName()) || matchesExcludedType(def.getName())) continue;
            visitTypeDefinition(def, registry, recognizer, rootOps, out);
        }
        for (ScalarTypeDefinition scalar : registry.scalars().values()) {
            if (excluded.contains(scalar.getName()) || matchesExcludedType(scalar.getName())) continue;
            dispatch(LintNodeKind.SCALAR_TYPE, scalar, scalar.getName(), false, registry, recognizer, out);
            visitAppliedDirectives(scalar, scalar.getName(), registry, recognizer, out);
        }
        return out;
    }

    /**
     * Whether a type name matches a configured {@code excludedTypes} glob (R408). This widens the same
     * per-type skip boundary the bundled ({@code BUNDLED_TYPE_NAMES}) and federation-injected
     * ({@code injectedNames}, R407) name-set exclusions use, and stays scoped to the engine's AST walk;
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
        TypeDefinition<?> def, TypeDefinitionRegistry registry, DeprecationRecognizer recognizer,
        Set<String> rootOps, List<BuildWarning> out
    ) {
        String typeName = def.getName();
        boolean isRootOp = rootOps.contains(typeName);
        switch (def) {
            case ObjectTypeDefinition obj -> {
                dispatch(LintNodeKind.OBJECT_TYPE, obj, typeName, isRootOp, registry, recognizer, out);
                visitAppliedDirectives(obj, typeName, registry, recognizer, out);
                for (FieldDefinition f : obj.getFieldDefinitions()) {
                    visitField(f, typeName, isRootOp, registry, recognizer, out);
                }
            }
            case InterfaceTypeDefinition iface -> {
                dispatch(LintNodeKind.INTERFACE_TYPE, iface, typeName, isRootOp, registry, recognizer, out);
                visitAppliedDirectives(iface, typeName, registry, recognizer, out);
                for (FieldDefinition f : iface.getFieldDefinitions()) {
                    visitField(f, typeName, isRootOp, registry, recognizer, out);
                }
            }
            case UnionTypeDefinition union -> {
                dispatch(LintNodeKind.UNION_TYPE, union, typeName, isRootOp, registry, recognizer, out);
                visitAppliedDirectives(union, typeName, registry, recognizer, out);
            }
            case EnumTypeDefinition en -> {
                dispatch(LintNodeKind.ENUM_TYPE, en, typeName, isRootOp, registry, recognizer, out);
                visitAppliedDirectives(en, typeName, registry, recognizer, out);
                for (EnumValueDefinition v : en.getEnumValueDefinitions()) {
                    dispatch(LintNodeKind.ENUM_VALUE_DEFINITION, v, typeName, isRootOp, registry, recognizer, out);
                    visitAppliedDirectives(v, typeName, registry, recognizer, out);
                }
            }
            case InputObjectTypeDefinition input -> {
                dispatch(LintNodeKind.INPUT_OBJECT_TYPE, input, typeName, isRootOp, registry, recognizer, out);
                visitAppliedDirectives(input, typeName, registry, recognizer, out);
                for (InputValueDefinition v : input.getInputValueDefinitions()) {
                    dispatch(LintNodeKind.INPUT_FIELD_DEFINITION, v, typeName, isRootOp, registry, recognizer, out);
                    visitAppliedDirectives(v, typeName, registry, recognizer, out);
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
        TypeDefinitionRegistry registry, DeprecationRecognizer recognizer, List<BuildWarning> out
    ) {
        dispatch(LintNodeKind.FIELD_DEFINITION, field, enclosingType, isRootOp, registry, recognizer, out);
        visitAppliedDirectives(field, enclosingType, registry, recognizer, out);
        for (InputValueDefinition arg : field.getInputValueDefinitions()) {
            dispatch(LintNodeKind.ARGUMENT_DEFINITION, arg, enclosingType, isRootOp, registry, recognizer, out);
            visitAppliedDirectives(arg, enclosingType, registry, recognizer, out);
        }
    }

    private void visitAppliedDirectives(
        DirectivesContainer<?> container, String enclosingType,
        TypeDefinitionRegistry registry, DeprecationRecognizer recognizer, List<BuildWarning> out
    ) {
        for (Directive d : container.getDirectives()) {
            dispatch(LintNodeKind.APPLIED_DIRECTIVE, d, enclosingType, false, registry, recognizer, out);
            // Applied-directive arguments are encountered but deliberately not a dispatch target in
            // v1 (NOT_LINTED); the rules that care read the arguments off the directive node directly.
        }
    }

    private void dispatch(
        LintNodeKind kind, Node<?> node, String enclosingType, boolean isRootOp,
        TypeDefinitionRegistry registry, DeprecationRecognizer recognizer, List<BuildWarning> out
    ) {
        var subscribers = byKind.get(kind);
        if (subscribers == null) return;
        var target = new LintTarget(kind, node, enclosingType, isRootOp);
        for (LintVisitor visitor : subscribers) {
            var ctx = new SinkContext(visitor.rule(), node.getSourceLocation(), registry, recognizer, out);
            visitor.inspect(target, ctx);
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
        var registry = new SchemaParser().parse(RewriteSchemaLoader.directivesSdl());
        var names = new LinkedHashSet<String>();
        names.addAll(registry.types().keySet());
        names.addAll(registry.scalars().keySet());
        return names;
    }

    /** Per-(visitor, node) sink: attributes each finding to the rule and the node's default location. */
    private record SinkContext(
        LintRule rule, SourceLocation defaultLocation, TypeDefinitionRegistry registry,
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
    }
}
