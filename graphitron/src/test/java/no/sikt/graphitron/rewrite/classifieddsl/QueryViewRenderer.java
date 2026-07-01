package no.sikt.graphitron.rewrite.classifieddsl;

import graphql.language.AstPrinter;
import graphql.language.Comment;
import graphql.language.Definition;
import graphql.language.Description;
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
import graphql.language.Node;
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
import java.util.function.Consumer;
import java.util.stream.Collectors;

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
 *
 * <p><strong>Descriptions (R309).</strong> The projection query is the per-example place to say <em>why</em>
 * a coordinate exists, which the shared description-free corpus fixture cannot. A {@code # ...} line comment
 * above a selected coordinate renders as that coordinate's SDL {@code Description}: above a field it
 * describes the field, above {@code ... on T} or a top-level {@code fragment f on T} it describes type
 * {@code T}. Multiple comment lines join into a block-string description. Comments are the durable carrier
 * for field prose because {@code Field} is not a {@code DescribedNode} in any graphql-java version;
 * {@link #descriptionOf(Node)} is the single source seam where native executable descriptions fold in once
 * graphql-java is bumped past the pinned 25.0 (Spec §"Future evolution"). A projection with no comments
 * renders exactly as before.
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
                append(sb, prune(def, entry.getValue(), touched));
            }
        }
        // 2. Abstract output types referenced but not field-selected (unions; interfaces reached only via fragments).
        for (String name : touched.abstractTypes) {
            if (!touched.fieldsByParent.containsKey(name)) {
                TypeDefinition<?> def = registry.getTypeOrNull(name);
                if (def != null) {
                    append(sb, stripInternalDirectives(def, touched));
                }
            }
        }
        // 3. Input-object closure reached from the kept fields' arguments.
        for (String name : touched.inputTypes) {
            TypeDefinition<?> def = registry.getTypeOrNull(name);
            if (def != null) {
                append(sb, stripInternalDirectives(def, touched));
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

    /**
     * The coordinates a selection touches: pruned field containers, plus the abstract/input closures to
     * emit whole, plus the description prose authored as comments next to the selected coordinates. A
     * comment above a selected field lands under {@link #fieldDescriptions} keyed by {@code (parent,
     * field)}; a comment above an {@code ... on T} inline fragment or a top-level {@code fragment f on T}
     * lands under {@link #typeDescriptions} keyed by type name. The emit loop reads these back as it
     * rebuilds each {@code DescribedNode} and stamps them on as SDL descriptions.
     */
    private static final class Touched {
        final Map<String, Set<String>> fieldsByParent = new LinkedHashMap<>();
        final Set<String> abstractTypes = new LinkedHashSet<>();
        final Set<String> inputTypes = new LinkedHashSet<>();
        final Map<String, String> typeDescriptions = new LinkedHashMap<>();
        final Map<String, Map<String, String>> fieldDescriptions = new LinkedHashMap<>();
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
                    String typeName = frag.getTypeCondition().getName();
                    recordTypeDescription(typeName, frag);
                    walk(frag.getSelectionSet(), schema.getType(typeName));
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
                    case InlineFragment inline -> {
                        String typeName = inline.getTypeCondition().getName();
                        recordTypeDescription(typeName, inline);
                        walk(inline.getSelectionSet(), schema.getType(typeName));
                    }
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
            String description = descriptionOf(field);
            if (description != null) {
                out.fieldDescriptions
                    .computeIfAbsent(container.getName(), k -> new LinkedHashMap<>())
                    .put(field.getName(), description);
            }
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

        private void recordTypeDescription(String typeName, Node<?> carrier) {
            String description = descriptionOf(carrier);
            if (description != null) {
                out.typeDescriptions.put(typeName, description);
            }
        }
    }

    /** Keeps only the touched fields of {@code def} and strips the internal directives from what remains. */
    private static TypeDefinition<?> prune(TypeDefinition<?> def, Set<String> keep, Touched touched) {
        String typeDescription = touched.typeDescriptions.get(def.getName());
        return switch (def) {
            case ObjectTypeDefinition o -> o.transform(b -> {
                b.fieldDefinitions(keptFields(o.getName(), o.getFieldDefinitions(), keep, touched))
                    .directives(realDirectives(o.getDirectives()));
                applyDescription(b::description, typeDescription);
            });
            case InterfaceTypeDefinition i -> i.transform(b -> {
                b.definitions(keptFields(i.getName(), i.getFieldDefinitions(), keep, touched))
                    .directives(realDirectives(i.getDirectives()));
                applyDescription(b::description, typeDescription);
            });
            default -> stripInternalDirectives(def, touched);
        };
    }

    /** Emits a type whole (all fields kept), stripping only the internal test directives at every level. */
    private static TypeDefinition<?> stripInternalDirectives(TypeDefinition<?> def, Touched touched) {
        String typeDescription = touched.typeDescriptions.get(def.getName());
        return switch (def) {
            case ObjectTypeDefinition o -> o.transform(b -> {
                b.directives(realDirectives(o.getDirectives()));
                applyDescription(b::description, typeDescription);
            });
            case InterfaceTypeDefinition i -> i.transform(b -> {
                b.directives(realDirectives(i.getDirectives()));
                applyDescription(b::description, typeDescription);
            });
            case UnionTypeDefinition u -> u.transform(b -> {
                b.directives(realDirectives(u.getDirectives()));
                applyDescription(b::description, typeDescription);
            });
            case EnumTypeDefinition e -> e.transform(b -> {
                b.directives(realDirectives(e.getDirectives()));
                applyDescription(b::description, typeDescription);
            });
            case InputObjectTypeDefinition io -> io.transform(b -> {
                b.directives(realDirectives(io.getDirectives()))
                    .inputValueDefinitions(io.getInputValueDefinitions().stream()
                        .map(iv -> iv.transform(vb -> vb.directives(realDirectives(iv.getDirectives()))))
                        .toList());
                applyDescription(b::description, typeDescription);
            });
            default -> def;
        };
    }

    private static List<FieldDefinition> keptFields(String parentName, List<FieldDefinition> fields, Set<String> keep, Touched touched) {
        Map<String, String> descriptions = touched.fieldDescriptions.getOrDefault(parentName, Map.of());
        return fields.stream()
            .filter(f -> keep.contains(f.getName()))
            .map(f -> f.transform(b -> {
                b.directives(realDirectives(f.getDirectives()));
                applyDescription(b::description, descriptions.get(f.getName()));
            }))
            .toList();
    }

    private static List<Directive> realDirectives(List<Directive> directives) {
        return directives.stream().filter(d -> !INTERNAL_DIRECTIVES.contains(d.getName())).toList();
    }

    /**
     * The single source seam (Spec §"Future evolution"). Today every description text comes from
     * {@code # ...} comments on the selection AST; when graphql-java is bumped past 25.0, native
     * executable {@code getDescription()} reads on {@code FragmentDefinition} / {@code VariableDefinition}
     * fold in here, and the output side below does not change. {@code Field} prose stays comment-sourced
     * regardless, since {@code Field} never becomes a {@code DescribedNode}.
     */
    private static String descriptionOf(Node<?> node) {
        List<Comment> comments = node.getComments();
        if (comments == null || comments.isEmpty()) {
            return null;
        }
        String joined = comments.stream()
            .map(comment -> comment.getContent().strip())
            .collect(Collectors.joining("\n"));
        return joined.isEmpty() ? null : joined;
    }

    /** Stamps a recorded description onto a builder, as a block string when the text spans lines. */
    private static void applyDescription(Consumer<Description> setter, String text) {
        if (text != null) {
            setter.accept(new Description(text, null, text.contains("\n")));
        }
    }
}
