package no.sikt.graphitron.rewrite.classifieddsl;

import graphql.language.AstPrinter;
import graphql.language.Definition;
import graphql.language.Directive;
import graphql.language.Document;
import graphql.language.EnumTypeDefinition;
import graphql.language.Field;
import graphql.language.FieldDefinition;
import graphql.language.FragmentDefinition;
import graphql.language.FragmentSpread;
import graphql.language.InlineFragment;
import graphql.language.InputObjectTypeDefinition;
import graphql.language.InterfaceTypeDefinition;
import graphql.language.ObjectTypeDefinition;
import graphql.language.OperationDefinition;
import graphql.language.Selection;
import graphql.language.SelectionSet;
import graphql.language.TypeDefinition;
import graphql.language.UnionTypeDefinition;
import graphql.parser.Parser;
import graphql.schema.GraphQLArgument;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLFieldsContainer;
import graphql.schema.GraphQLInputObjectField;
import graphql.schema.GraphQLInputObjectType;
import graphql.schema.GraphQLInterfaceType;
import graphql.schema.GraphQLNamedType;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLSchema;
import graphql.schema.GraphQLType;
import graphql.schema.GraphQLTypeUtil;
import graphql.schema.GraphQLUnionType;
import graphql.schema.idl.TypeDefinitionRegistry;
import no.sikt.graphitron.common.configuration.TestConfiguration;
import no.sikt.graphitron.rewrite.GraphitronSchemaBuilder;
import no.sikt.graphitron.rewrite.TestSchemaHelper;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * R281's query-as-view renderer (Spec §"Rendering: queries as views over the corpus"). Prose embeds a
 * GraphQL <em>query</em> (or a fragment {@code on Type}) naming the coordinates it wants to show; the
 * renderer resolves that selection against the assembled corpus schema and regenerates minimal SDL for
 * the touched closure. One mechanism does the three jobs the Spec asks for at once:
 *
 * <ol>
 *   <li><b>Import what's relevant</b>: the selection set is the projection, reusing GraphQL's own
 *       selection mechanism (a pre-order walk of the parsed selection against the assembled schema)
 *       instead of bespoke include-tags.</li>
 *   <li><b>Strip the test directives</b>: regeneration emits only real schema, so {@code @classified}
 *       / {@code @classifiedType} are simply never printed, while real Graphitron directives
 *       ({@code @table}, {@code @field}, {@code @asConnection}, {@code @mutation}, ...) survive.</li>
 *   <li><b>Bound the snippet</b>: each touched field container is pruned to exactly the selected
 *       fields, so a sibling field or type the selection did not name is not dragged in.</li>
 * </ol>
 *
 * <p>The selection-and-prune technique follows {@code no.sikt.fs.app.util.GraphQLSubsetter}
 * (the <em>graphql-scissors</em> library): collect the touched fields per parent, then keep only those
 * fields. Where scissors strips <em>all</em> directives and re-assembles through
 * {@code makeExecutableSchema} + {@code SchemaPrinter}, this renderer prints the pruned <em>AST</em>
 * directly ({@link AstPrinter}). That keeps real directives without forcing the whole
 * directive-definition and enum vocabulary into the pruned subset, and prints the <em>authored</em> SDL
 * from the parsed registry (so {@code @asConnection} renders as written rather than as the generated
 * {@code *Connection} wrapper the schema transform produces).
 *
 * <p><strong>Selection forms.</strong> The walk handles all three GraphQL selection mechanisms against
 * the assembled schema: plain fields, inline fragments ({@code ... on Film}), and named fragment
 * spreads. A document may carry an operation ({@code query}/{@code mutation}) or stand as a bare
 * {@code fragment F on Type { ... }}; the latter is the <em>type-display</em> form the Spec promises,
 * naming an output type directly without routing a query through a field that happens to return it
 * (so {@code ErrorType} and friends, which no query reaches, still render).
 *
 * <p><strong>Closure.</strong> The projection is the selection's reachability, with two expansions
 * beyond the field containers it descends into so the excerpt does not reference types it never shows
 * (the closure-honesty rule, Spec §"Pre-migration hardening" item 3):
 * <ul>
 *   <li><b>Input-object closure</b>: a kept field's argument types (mutation inputs, {@code @lookupKey}
 *       args) are emitted in full, recursing through nested input objects. Input objects are input-field
 *       containers, so a {@code createFilm(in: FilmInput!)} shows {@code FilmInput} rather than dangling.</li>
 *   <li><b>Abstract output types</b>: a union or interface reached by a kept field (or named by a
 *       fragment {@code on}) is emitted, so a polymorphic excerpt shows its {@code union X = A | B} /
 *       {@code interface} declaration. An interface the selection also takes fields off is pruned through
 *       the normal field-container path instead.</li>
 * </ul>
 * Scalars and enums remain unexpanded (they are leaf vocabulary, not containers the selection descends
 * into), as do the generated {@code @asConnection} {@code *Connection} / {@code *Edge} wrappers, the
 * authored field that produces them prints instead. The Spec scopes this to "cheap insurance, not a hard
 * gate."
 */
public final class QueryViewRenderer {

    private QueryViewRenderer() {}

    private static final Set<String> INTERNAL_DIRECTIVES =
        Set.of(ClassifiedDsl.CLASSIFIED, ClassifiedDsl.CLASSIFIED_TYPE);

    /** Renders the SDL closure the {@code selection} (a query/mutation or fragment document) touches over {@code fixtureSdl}. */
    public static String render(String fixtureSdl, String selection) {
        String full = ClassifiedDsl.PRELUDE + "\n" + fixtureSdl;
        TypeDefinitionRegistry registry = TestSchemaHelper.parseRegistryWithPrelude(full);
        GraphQLSchema schema = GraphitronSchemaBuilder.buildBundle(registry, TestConfiguration.testContext()).assembled();

        Document doc = new Parser().parseDocument(selection);
        Touched touched = new Touched();
        new Walk(schema, indexFragments(doc), touched).fromDocument(doc);

        var sb = new StringBuilder();
        // 1. Output field containers, pruned to the selected fields (the field/catalog side; preserved verbatim).
        for (var entry : touched.fieldsByParent.entrySet()) {
            TypeDefinition<?> def = registry.getTypeOrNull(entry.getKey());
            if (def != null) {
                append(sb, prune(def, entry.getValue()));
            }
        }
        // 2. Abstract output types referenced but not field-selected (unions; interfaces reached only via fragments).
        for (String name : touched.abstractTypes) {
            if (!touched.fieldsByParent.containsKey(name)) {
                TypeDefinition<?> def = registry.getTypeOrNull(name);
                if (def != null) {
                    append(sb, stripInternalDirectives(def));
                }
            }
        }
        // 3. Input-object closure reached from the kept fields' arguments.
        for (String name : touched.inputTypes) {
            TypeDefinition<?> def = registry.getTypeOrNull(name);
            if (def != null) {
                append(sb, stripInternalDirectives(def));
            }
        }
        return sb.toString().strip();
    }

    private static void append(StringBuilder sb, TypeDefinition<?> def) {
        String printed = AstPrinter.printAst(def).strip();
        if (!printed.isEmpty()) sb.append(printed).append("\n\n");
    }

    private static Map<String, FragmentDefinition> indexFragments(Document doc) {
        Map<String, FragmentDefinition> fragments = new HashMap<>();
        for (Definition<?> def : doc.getDefinitions()) {
            if (def instanceof FragmentDefinition frag) {
                fragments.put(frag.getName(), frag);
            }
        }
        return fragments;
    }

    /** The coordinates a selection touches: pruned field containers, plus the abstract/input closures to emit whole. */
    private static final class Touched {
        final Map<String, Set<String>> fieldsByParent = new LinkedHashMap<>();
        final Set<String> abstractTypes = new LinkedHashSet<>();
        final Set<String> inputTypes = new LinkedHashSet<>();
    }

    /** Walks a parsed selection against the assembled schema, recording the {@link Touched} closure. */
    private static final class Walk {
        private final GraphQLSchema schema;
        private final Map<String, FragmentDefinition> fragments;
        private final Touched out;
        private final Set<String> inputSeen = new HashSet<>();

        Walk(GraphQLSchema schema, Map<String, FragmentDefinition> fragments, Touched out) {
            this.schema = schema;
            this.fragments = fragments;
            this.out = out;
        }

        void fromDocument(Document doc) {
            for (Definition<?> def : doc.getDefinitions()) {
                if (def instanceof OperationDefinition op) {
                    walk(op.getSelectionSet(), rootType(op));
                } else if (def instanceof FragmentDefinition frag) {
                    walk(frag.getSelectionSet(), schema.getType(frag.getTypeCondition().getName()));
                }
            }
        }

        private GraphQLType rootType(OperationDefinition op) {
            return switch (op.getOperation()) {
                case MUTATION -> schema.getMutationType();
                case SUBSCRIPTION -> schema.getSubscriptionType();
                case QUERY -> schema.getQueryType();
            };
        }

        private void walk(SelectionSet selectionSet, GraphQLType parent) {
            if (selectionSet == null || parent == null) {
                return;
            }
            if (parent instanceof GraphQLUnionType union) {
                out.abstractTypes.add(union.getName());
            }
            for (Selection<?> selection : selectionSet.getSelections()) {
                switch (selection) {
                    case Field field -> visitField(field, parent);
                    case InlineFragment inline ->
                        walk(inline.getSelectionSet(), schema.getType(inline.getTypeCondition().getName()));
                    case FragmentSpread spread -> {
                        FragmentDefinition frag = fragments.get(spread.getName());
                        if (frag != null) {
                            walk(frag.getSelectionSet(), schema.getType(frag.getTypeCondition().getName()));
                        }
                    }
                    default -> { /* directives-only / introspection selections carry no coordinate */ }
                }
            }
        }

        private void visitField(Field field, GraphQLType parent) {
            if (!(parent instanceof GraphQLFieldsContainer container)) {
                return;
            }
            GraphQLFieldDefinition fieldDef = container.getFieldDefinition(field.getName());
            if (fieldDef == null) {
                return;
            }
            out.fieldsByParent.computeIfAbsent(container.getName(), k -> new LinkedHashSet<>()).add(field.getName());
            for (GraphQLArgument arg : fieldDef.getArguments()) {
                collectInputClosure(GraphQLTypeUtil.unwrapAll(arg.getType()));
            }
            GraphQLType target = GraphQLTypeUtil.unwrapAll(fieldDef.getType());
            if (target instanceof GraphQLUnionType || target instanceof GraphQLInterfaceType) {
                out.abstractTypes.add(((GraphQLNamedType) target).getName());
            }
            walk(field.getSelectionSet(), target);
        }

        private void collectInputClosure(GraphQLType type) {
            if (type instanceof GraphQLInputObjectType input && inputSeen.add(input.getName())) {
                out.inputTypes.add(input.getName());
                for (GraphQLInputObjectField inputField : input.getFieldDefinitions()) {
                    collectInputClosure(GraphQLTypeUtil.unwrapAll(inputField.getType()));
                }
            }
        }
    }

    /** Keeps only the touched fields of {@code def} and strips the internal directives from what remains. */
    private static TypeDefinition<?> prune(TypeDefinition<?> def, Set<String> keep) {
        return switch (def) {
            case ObjectTypeDefinition o -> o.transform(b -> b
                .fieldDefinitions(keptFields(o.getFieldDefinitions(), keep))
                .directives(realDirectives(o.getDirectives())));
            case InterfaceTypeDefinition i -> i.transform(b -> b
                .definitions(keptFields(i.getFieldDefinitions(), keep))
                .directives(realDirectives(i.getDirectives())));
            default -> stripInternalDirectives(def);
        };
    }

    /** Emits a type whole (all fields kept), stripping only the internal test directives at every level. */
    private static TypeDefinition<?> stripInternalDirectives(TypeDefinition<?> def) {
        return switch (def) {
            case ObjectTypeDefinition o -> o.transform(b -> b.directives(realDirectives(o.getDirectives())));
            case InterfaceTypeDefinition i -> i.transform(b -> b.directives(realDirectives(i.getDirectives())));
            case UnionTypeDefinition u -> u.transform(b -> b.directives(realDirectives(u.getDirectives())));
            case EnumTypeDefinition e -> e.transform(b -> b.directives(realDirectives(e.getDirectives())));
            case InputObjectTypeDefinition io -> io.transform(b -> b
                .directives(realDirectives(io.getDirectives()))
                .inputValueDefinitions(io.getInputValueDefinitions().stream()
                    .map(iv -> iv.transform(vb -> vb.directives(realDirectives(iv.getDirectives()))))
                    .toList()));
            default -> def;
        };
    }

    private static List<FieldDefinition> keptFields(List<FieldDefinition> fields, Set<String> keep) {
        return fields.stream()
            .filter(f -> keep.contains(f.getName()))
            .map(f -> f.transform(b -> b.directives(realDirectives(f.getDirectives()))))
            .toList();
    }

    private static List<Directive> realDirectives(List<Directive> directives) {
        return directives.stream().filter(d -> !INTERNAL_DIRECTIVES.contains(d.getName())).toList();
    }
}
