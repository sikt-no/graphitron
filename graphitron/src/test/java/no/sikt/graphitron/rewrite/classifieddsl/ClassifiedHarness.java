package no.sikt.graphitron.rewrite.classifieddsl;

import graphql.language.Argument;
import graphql.language.Directive;
import graphql.language.EnumTypeDefinition;
import graphql.language.EnumValue;
import graphql.language.EnumValueDefinition;
import graphql.language.FieldDefinition;
import graphql.language.InterfaceTypeDefinition;
import graphql.language.ObjectTypeDefinition;
import graphql.language.TypeDefinition;
import graphql.language.Value;
import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;
import no.sikt.graphitron.common.configuration.TestConfiguration;
import no.sikt.graphitron.rewrite.GraphitronSchema;
import no.sikt.graphitron.rewrite.GraphitronSchemaBuilder;
import no.sikt.graphitron.rewrite.TestSchemaHelper;
import no.sikt.graphitron.rewrite.generators.GeneratorCoverageTest;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.Operation;
import no.sikt.graphitron.rewrite.model.OutputField;
import no.sikt.graphitron.rewrite.model.Source;
import no.sikt.graphitron.rewrite.model.SourceShape;
import no.sikt.graphitron.rewrite.model.Target;
import no.sikt.graphitron.rewrite.model.TargetShape;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Drives R281's spec-by-example corpus: parses an annotated fixture schema, runs <em>today's</em>
 * classifier, and for each {@code @classified} / {@code @classifiedType} coordinate compares the
 * directive's declared verdict against what the classifier produces (read off the field model's
 * {@code source()} / {@code operation()} / {@code target()} accessors for fields, off the sealed leaf's
 * simple name for types).
 *
 * <p>The fixture's test-only directives ({@link ClassifiedDsl#PRELUDE}) are prepended before the
 * classifier runs; the classifier ignores them, and this harness reads them straight off the parsed
 * AST. The SDL is the example, the directive is the assertion. See
 * {@code roadmap/classification-test-dsl.md} §"The shape".
 */
public final class ClassifiedHarness {

    private ClassifiedHarness() {}

    /**
     * One {@code @classified} output-field coordinate: its declared tuple vs. the adapter's, plus the
     * sealed {@code OutputField} leaf the classifier landed on (the corpus's contribution to leaf
     * coverage, see {@link ClassifiedCorpus#coveredLeaves()}).
     */
    public record FieldCase(String parentType, String fieldName, DimensionTuple expected,
                            DimensionTuple actual, Class<? extends GraphitronField> leaf) {}

    /**
     * One {@code @classifiedType} coordinate: its declared verdict vs. the classified leaf's simple
     * name, plus the {@code GraphitronType} leaf itself ({@code null} if the type did not classify).
     */
    public record TypeCase(String typeName, String expectedVerdict, String actualVerdict,
                           Class<? extends GraphitronType> leaf) {}

    /** The full outcome of classifying one fixture: every annotated coordinate, plus the schema. */
    public record Result(List<FieldCase> fields, List<TypeCase> types, GraphitronSchema schema) {}

    /**
     * Classifies {@code fixtureSdl} (the {@link ClassifiedDsl#PRELUDE} prepended automatically) and
     * resolves every {@code @classified} / {@code @classifiedType} coordinate it carries.
     */
    public static Result classify(String fixtureSdl) {
        String full = ClassifiedDsl.PRELUDE + "\n" + fixtureSdl;
        TypeDefinitionRegistry registry = TestSchemaHelper.parseRegistryWithPrelude(full);
        GraphitronSchema schema = GraphitronSchemaBuilder.build(registry, TestConfiguration.testContext());

        var fields = new ArrayList<FieldCase>();
        var types = new ArrayList<TypeCase>();

        for (TypeDefinition<?> def : registry.types().values()) {
            List<FieldDefinition> fieldDefs = switch (def) {
                case ObjectTypeDefinition o -> o.getFieldDefinitions();
                case InterfaceTypeDefinition i -> i.getFieldDefinitions();
                default -> List.of();
            };
            for (var fd : fieldDefs) {
                Directive d = directive(fd.getDirectives(), ClassifiedDsl.CLASSIFIED);
                if (d == null) continue;
                fields.add(fieldCase(schema, def.getName(), fd.getName(), d));
            }
            Directive dt = directive(def.getDirectives(), ClassifiedDsl.CLASSIFIED_TYPE);
            if (dt != null) {
                types.add(typeCase(schema, def.getName(), dt));
            }
        }
        // Scalars live in their own registry map (graphql-java keeps them out of types()), so a
        // @classifiedType on a `scalar` definition is picked up here rather than in the loop above.
        for (var scalarDef : registry.scalars().values()) {
            Directive dt = directive(scalarDef.getDirectives(), ClassifiedDsl.CLASSIFIED_TYPE);
            if (dt != null) {
                types.add(typeCase(schema, scalarDef.getName(), dt));
            }
        }
        return new Result(fields, types, schema);
    }

    private static FieldCase fieldCase(GraphitronSchema schema, String parentType, String fieldName, Directive d) {
        GraphitronField field = schema.field(parentType, fieldName);
        if (!(field instanceof OutputField out)) {
            throw new AssertionError(
                "@classified coordinate " + parentType + "." + fieldName + " did not classify to an "
                + "OutputField (got " + (field == null ? "null" : field.getClass().getSimpleName())
                + "); the corpus asserts successful classification only.");
        }
        DimensionTuple expected = new DimensionTuple(sourceArg(d), operationArg(d), targetArg(d));
        // The arrival arm is the parent-type ancestor-product fold, read through the schema's
        // sourceOf seam (a leaf cannot compute its own arm). operation / target stay leaf-derived.
        DimensionTuple actual = DimensionTuple.of(out, schema.sourceOf(parentType, fieldName));
        return new FieldCase(parentType, fieldName, expected, actual, out.getClass());
    }

    private static TypeCase typeCase(GraphitronSchema schema, String typeName, Directive d) {
        GraphitronType type = schema.type(typeName);
        String actual = type == null ? "<absent>" : type.getClass().getSimpleName();
        return new TypeCase(typeName, enumArg(d, "as"), actual, type == null ? null : type.getClass());
    }

    /**
 * The {@code source:} arrival wrapper, reconstructed in full from the directive: the flat
     * {@code SourceWrapper} enum names the wrapper arm ({@link Source.Root.Query} / {@link Source.Root.Mutation}
     * / {@link Source.OnlyChild} / {@link Source.Child}), and the nested arms take their {@link SourceShape}
     * from {@code sourceShape:}. {@link Source} carries no heavy payload, so the whole value is asserted by
     * structural equality against {@link OutputField#source()}.
     */
    private static Source sourceArg(Directive d) {
        String w = enumArg(d, "source");
        return switch (w) {
            case "Query" -> new Source.Root.Query();
            case "Mutation" -> new Source.Root.Mutation();
            case "OnlyChild" -> new Source.OnlyChild(sourceShapeArg(d));
            case "Child" -> new Source.Child(sourceShapeArg(d));
            default -> throw new AssertionError("@classified: unknown source wrapper '" + w + "'");
        };
    }

    /**
 * The nested-arm source-shape. Defaults to {@link SourceShape#Table} (the common
     * catalog-backed case) when the arg is absent, so only the record-source rows declare
     * {@code sourceShape: Record} explicitly. A row that should be {@code Record} but omits the arg
     * fails loudly: the expected {@code Child(Table)} mismatches the actual {@code Child(Record)}.
     */
    private static SourceShape sourceShapeArg(Directive d) {
        Argument a = d.getArgument("sourceShape");
        return a == null ? SourceShape.Table : SourceShape.valueOf(((EnumValue) a.getValue()).getName());
    }

    /**
 * The {@code operation:} verb, as the {@link Operation} arm type token. The arm carries a
     * payload the directive cannot express, so the corpus asserts arm identity only; the token is
     * resolved from the seal's leaf set by simple name, so a directive value that names no arm fails.
     */
    private static Class<? extends Operation> operationArg(Directive d) {
        String name = enumArg(d, "operation");
        Class<? extends Operation> arm = OPERATION_ARMS.get(name);
        if (arm == null) {
            throw new AssertionError("@classified: unknown operation '" + name + "'");
        }
        return arm;
    }

    /**
 * The {@code target:} / {@code targetShape:} projection coordinate: the {@link Target} wrapper
     * arm token and the outer {@link TargetShape} arm token, both resolved from their seals' leaf sets by
     * simple name.
     */
    private static DimensionTuple.TargetVerdict targetArg(Directive d) {
        Class<? extends Target> wrapper = TARGET_WRAPPERS.get(enumArg(d, "target"));
        if (wrapper == null) {
            throw new AssertionError("@classified: unknown target wrapper '" + enumArg(d, "target") + "'");
        }
        Class<? extends TargetShape> shape = TARGET_SHAPES.get(enumArg(d, "targetShape"));
        if (shape == null) {
            throw new AssertionError("@classified: unknown targetShape '" + enumArg(d, "targetShape") + "'");
        }
        return new DimensionTuple.TargetVerdict(wrapper, shape);
    }

    /**
     * The arm type tokens of a sealed hierarchy, keyed by simple name (the SDL enum constant). Built from
     * the seal's recursive leaf set via {@code sealedLeaves}, so the SDL-vs-Java mirror is what pins the
     * name set; {@code toMap} additionally fails fast on a duplicate simple name within the seal.
     */
    @SuppressWarnings("unchecked")
    private static <T> Map<String, Class<? extends T>> armsByName(Class<T> seal) {
        return GeneratorCoverageTest.sealedLeaves(seal).stream()
            .collect(Collectors.toMap(Class::getSimpleName, c -> (Class<? extends T>) c));
    }

    private static final Map<String, Class<? extends Operation>> OPERATION_ARMS = armsByName(Operation.class);
    private static final Map<String, Class<? extends Target>> TARGET_WRAPPERS = armsByName(Target.class);
    private static final Map<String, Class<? extends TargetShape>> TARGET_SHAPES = armsByName(TargetShape.class);

    private static String enumArg(Directive d, String argName) {
        return ((EnumValue) argValue(d, argName)).getName();
    }

    private static Value<?> argValue(Directive d, String argName) {
        Argument a = d.getArgument(argName);
        if (a == null) {
            throw new AssertionError("@" + d.getName() + " is missing required argument '" + argName + "'");
        }
        return a.getValue();
    }

    private static Directive directive(List<Directive> directives, String name) {
        return directives.stream().filter(x -> x.getName().equals(name)).findFirst().orElse(null);
    }

    // ----- meta-test support: the SDL-vs-Java enum mirrors -----

    /** The {@code TypeVerdict} enum constants as declared in {@link ClassifiedDsl#PRELUDE}. */
    public static Set<String> typeVerdictEnumConstants() {
        return preludeEnumConstants("TypeVerdict");
    }

    /** The {@code SourceWrapper} enum constants as declared in {@link ClassifiedDsl#PRELUDE}. */
    public static Set<String> sourceWrapperEnumConstants() {
        return preludeEnumConstants("SourceWrapper");
    }

    /**
     * The simple names of the sealed {@link Source} leaf arms (the live arrival-wrapper set). Uses the
     * recursive leaf walker so {@link Source.Root} flattens to its {@code Query} / {@code Mutation} leaves,
     * matching the flat {@code SourceWrapper} SDL enum.
     */
    public static List<String> sourceWrapperArmSimpleNames() {
        return sealedLeafSimpleNames(Source.class);
    }

    /** The {@code Operation} enum constants as declared in {@link ClassifiedDsl#PRELUDE}. */
    public static Set<String> operationEnumConstants() {
        return preludeEnumConstants("Operation");
    }

    /**
     * The simple names of the sealed {@link Operation} arms (the live verb set). The {@code Operation}
     * arms are all direct records, so the recursive walker stops at them; the per-arm transitional holders
     * (e.g. {@code ServiceCall.Call}) are payload components, not permitted subclasses, so they never enter
     * this set.
     */
    public static List<String> operationArmSimpleNames() {
        return sealedLeafSimpleNames(Operation.class);
    }

    /** The {@code TargetWrapper} enum constants as declared in {@link ClassifiedDsl#PRELUDE}. */
    public static Set<String> targetWrapperEnumConstants() {
        return preludeEnumConstants("TargetWrapper");
    }

    /** The simple names of the sealed {@link Target} wrapper arms ({@code Single} / {@code List}). */
    public static List<String> targetWrapperArmSimpleNames() {
        return sealedLeafSimpleNames(Target.class);
    }

    /** The {@code TargetShape} enum constants as declared in {@link ClassifiedDsl#PRELUDE}. */
    public static Set<String> targetShapeEnumConstants() {
        return preludeEnumConstants("TargetShape");
    }

    /** The simple names of the sealed {@link TargetShape} arms (the live projection-shape set). */
    public static List<String> targetShapeArmSimpleNames() {
        return sealedLeafSimpleNames(TargetShape.class);
    }

    /** The {@code SourceShape} enum constants as declared in {@link ClassifiedDsl#PRELUDE}. */
    public static Set<String> sourceShapeEnumConstants() {
        return preludeEnumConstants("SourceShape");
    }

    /**
     * The simple names of every concrete sealed leaf of {@code seal}, in discovery order and
     * <em>preserving duplicates</em>, so a mirror that compares by simple name can assert the set has no
     * duplicate names before relying on the name-based comparison (the discipline
     * {@link #graphitronTypeNonFailureLeafSimpleNames()} already applies for {@code GraphitronType}).
     */
    private static List<String> sealedLeafSimpleNames(Class<?> seal) {
        return GeneratorCoverageTest.sealedLeaves(seal).stream()
            .map(Class::getSimpleName)
            .toList();
    }

    /** The constant names of an enum declared in {@link ClassifiedDsl#PRELUDE}, in declaration order. */
    private static Set<String> preludeEnumConstants(String enumName) {
        TypeDefinitionRegistry registry = new SchemaParser().parse(ClassifiedDsl.PRELUDE);
        EnumTypeDefinition def = registry.getTypeOrNull(enumName, EnumTypeDefinition.class);
        if (def == null) {
            throw new AssertionError(enumName + " enum missing from the DSL prelude");
        }
        var names = new LinkedHashSet<String>();
        for (EnumValueDefinition v : def.getEnumValueDefinitions()) {
            names.add(v.getName());
        }
        return names;
    }

    /**
     * The simple names of every concrete {@code GraphitronType} sealed leaf except the failure leaf
     * {@code UnclassifiedType}, in discovery order and <em>preserving duplicates</em>. The mirror
     * compares {@code TypeVerdict} against these by simple name, so two leaves sharing a simple name
     * would silently collapse in the set form below; {@code ClassifiedDslTest} asserts this list has
     * no duplicates to keep the name-based comparison sound.
     */
    public static List<String> graphitronTypeNonFailureLeafSimpleNames() {
        return GeneratorCoverageTest.sealedLeaves(GraphitronType.class).stream()
            .map(Class::getSimpleName)
            .filter(n -> !n.equals("UnclassifiedType"))
            .toList();
    }

    /**
     * The simple names of every concrete {@code GraphitronType} sealed leaf except the failure leaf
     * {@code UnclassifiedType}. This is the set {@code TypeVerdict} must mirror.
     */
    public static Set<String> graphitronTypeNonFailureLeafNames() {
        return new LinkedHashSet<>(graphitronTypeNonFailureLeafSimpleNames());
    }
}
