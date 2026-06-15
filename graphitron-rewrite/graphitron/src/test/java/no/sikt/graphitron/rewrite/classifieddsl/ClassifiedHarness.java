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
import no.sikt.graphitron.rewrite.model.Carrier;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.Intent;
import no.sikt.graphitron.rewrite.model.Mapping;
import no.sikt.graphitron.rewrite.model.OutputField;
import no.sikt.graphitron.rewrite.model.SourceCardinality;
import no.sikt.graphitron.rewrite.model.SourceShape;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Drives R281's spec-by-example corpus: parses an annotated fixture schema, runs <em>today's</em>
 * classifier, and for each {@code @classified} / {@code @classifiedType} coordinate compares the
 * directive's declared verdict against what the classifier produces (read off the field model's
 * {@code carrier()} / {@code intent()} / {@code mapping()} accessors for fields, off the sealed leaf's
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
        DimensionTuple expected = new DimensionTuple(carrierArg(d), intentArg(d), mappingArg(d));
        DimensionTuple actual = new DimensionTuple(out.carrier(), out.intent(), out.mapping());
        return new FieldCase(parentType, fieldName, expected, actual, out.getClass());
    }

    private static TypeCase typeCase(GraphitronSchema schema, String typeName, Directive d) {
        GraphitronType type = schema.type(typeName);
        String actual = type == null ? "<absent>" : type.getClass().getSimpleName();
        return new TypeCase(typeName, enumArg(d, "as"), actual, type == null ? null : type.getClass());
    }

    private static Carrier carrierArg(Directive d) {
        String c = enumArg(d, "carrier");
        return switch (c) {
            case "Query" -> new Carrier.Query();
            case "Mutation" -> new Carrier.Mutation();
            case "Source" -> new Carrier.Source(sourceShapeArg(d), sourceCardinalityArg(d));
            default -> throw new AssertionError("@classified: unknown carrier '" + c + "'");
        };
    }

    /**
     * The {@code Source}-arm source-shape (R305). Defaults to {@link SourceShape#Table} (the common
     * catalog-backed case) when the arg is absent, so only the record-source rows declare
     * {@code sourceShape: Record} explicitly. A row that should be {@code Record} but omits the arg
     * fails loudly: the expected {@code Source(Table, ...)} mismatches the actual {@code Source(Record, ...)}.
     */
    private static SourceShape sourceShapeArg(Directive d) {
        Argument a = d.getArgument("sourceShape");
        return a == null ? SourceShape.Table : SourceShape.valueOf(((EnumValue) a.getValue()).getName());
    }

    /** The {@code Source}-arm source-cardinality (R305); defaults to {@link SourceCardinality#Many} (R305 conservatively hard-codes the absorbing element for every Source field). */
    private static SourceCardinality sourceCardinalityArg(Directive d) {
        Argument a = d.getArgument("sourceCardinality");
        return a == null ? SourceCardinality.Many : SourceCardinality.valueOf(((EnumValue) a.getValue()).getName());
    }

    private static Intent intentArg(Directive d) {
        return Intent.valueOf(enumArg(d, "intent"));
    }

    private static Mapping mappingArg(Directive d) {
        return Mapping.valueOf(enumArg(d, "mapping"));
    }

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

    /** The {@code Carrier} enum constants as declared in {@link ClassifiedDsl#PRELUDE}. */
    public static Set<String> carrierEnumConstants() {
        return preludeEnumConstants("Carrier");
    }

    /** The simple names of the sealed {@link Carrier} arms (the live carrier-category set). */
    public static Set<String> carrierArmNames() {
        return java.util.Arrays.stream(Carrier.class.getPermittedSubclasses())
            .map(Class::getSimpleName)
            .collect(java.util.stream.Collectors.toSet());
    }

    /** The {@code SourceShape} enum constants as declared in {@link ClassifiedDsl#PRELUDE}. */
    public static Set<String> sourceShapeEnumConstants() {
        return preludeEnumConstants("SourceShape");
    }

    /** The {@code SourceCardinality} enum constants as declared in {@link ClassifiedDsl#PRELUDE}. */
    public static Set<String> sourceCardinalityEnumConstants() {
        return preludeEnumConstants("SourceCardinality");
    }

    /** The {@code Intent} enum constants as declared in {@link ClassifiedDsl#PRELUDE}. */
    public static Set<String> intentEnumConstants() {
        return preludeEnumConstants("Intent");
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
