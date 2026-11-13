package no.sikt.graphitron.emit;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeSpec;
import no.sikt.graphitron.render.CatalogRefs;

import javax.lang.model.element.Modifier;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Writes the {@code Vouchers} class: the fetcher of every {@code @issue} field and the one
 * {@code register} method the schema class calls to wire them. The store owns a voucher field whole,
 * so neither its fetcher nor its registration waits on the classified model.
 *
 * <p>A fetcher signs one voucher per row it is selected on, over the row's components, by the
 * operation's issuer for the field's scope and lifetime. The components' columns are on the row
 * because the type's projection selects them whenever the field is selected, so the fetcher reads the
 * row it is handed and runs no statement. Each value is serialized by its own scalar before it is
 * signed, so the voucher carries what a plain representation of the field would have.
 */
public final class VoucherIssueEmitter {

    private static final ClassName DATA_FETCHING_ENV = ClassName.get("graphql.schema", "DataFetchingEnvironment");
    private static final ClassName GRAPHQL_CONTEXT = ClassName.get("graphql", "GraphQLContext");
    private static final ClassName RECORD = ClassName.get("org.jooq", "Record");

    private static final ClassName CODE_REGISTRY = ClassName.get("graphql.schema", "GraphQLCodeRegistry", "Builder");
    private static final ClassName FIELD_COORDINATES = ClassName.get("graphql.schema", "FieldCoordinates");

    /** The class's simple name. */
    public static final String CLASS_NAME = "Vouchers";

    private VoucherIssueEmitter() {}

    /** The {@code Vouchers} class over {@code rows}: one fetcher per issuing field and {@code register}. */
    public static TypeSpec render(VoucherIssueQuery.Rows rows, String outputPackage) {
        var self = ClassName.get(outputPackage + ".fetchers", CLASS_NAME);
        var register = MethodSpec.methodBuilder("register")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addParameter(CODE_REGISTRY, "codeRegistry")
            .addJavadoc("Wires every voucher field to its fetcher.\n");
        var spec = TypeSpec.classBuilder(self)
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            .addJavadoc("The fetchers of the schema's voucher fields, and their registration.\n")
            .addMethod(MethodSpec.constructorBuilder().addModifiers(Modifier.PRIVATE).build());
        for (var issue : rows.byCoordinate().values().stream()
                .sorted(java.util.Comparator.comparing(VoucherIssueQuery.Issue::coordinate)).toList()) {
            var fetcher = fetcher(issue, outputPackage);
            spec.addMethod(fetcher);
            register.addStatement("codeRegistry.dataFetcher($T.coordinates($S, $S), $T::$N)",
                FIELD_COORDINATES, issue.typeName(), issue.fieldName(), self, fetcher);
        }
        return spec.addMethod(register.build()).build();
    }

    /**
     * The field's fetcher, a {@code public static} method named after its type and the field.
     *
     * @param issue        the field's row
     * @param outputPackage the consumer's root output package, under whose {@code schema} package the codec lands
     */
    static MethodSpec fetcher(VoucherIssueQuery.Issue issue, String outputPackage) {
        var issuers = ClassName.get(outputPackage + ".schema", VoucherRuntime.CODEC).nestedClass(VoucherRuntime.ISSUERS);
        var table = CatalogRefs.className(issue.table().constantsClassName());
        var map = ParameterizedTypeName.get(ClassName.get(Map.class), ClassName.get(String.class), ClassName.get(Object.class));
        var body = CodeBlock.builder()
            .addStatement("$T row = ($T) env.getSource()", RECORD, RECORD)
            .addStatement("$T context = env.getGraphQlContext()", GRAPHQL_CONTEXT)
            .addStatement("$T claims = new $T<>()", map, LinkedHashMap.class)
            .addStatement("$T lookup = new $T<>()", map, LinkedHashMap.class);
        for (var component : issue.components()) {
            body.addStatement("$L.put($S, $T.$L.getCoercing().serialize(row.get($T.$L.$L), context, env.getLocale()))",
                component.claim() ? "claims" : "lookup", component.fieldName(),
                CatalogRefs.className(component.scalar().ownerClassName()), component.scalar().fieldName(),
                table, issue.table().javaFieldName(), component.column().javaName());
        }
        body.addStatement("return context.<$T>get($T.class).issuer($S, $T.parse($S)).sign($S, claims, lookup)",
            issuers, issuers, issue.scope(), Duration.class, issue.ttl().toString(), issue.typeName());
        return MethodSpec.methodBuilder(Character.toLowerCase(issue.typeName().charAt(0))
                + issue.typeName().substring(1) + Character.toUpperCase(issue.fieldName().charAt(0))
                + issue.fieldName().substring(1))
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(Object.class)
            .addParameter(DATA_FETCHING_ENV, "env")
            .addJavadoc("The voucher for this row of {@code $L}, issued under scope {@code $L}.\n",
                issue.typeName(), issue.scope())
            .addCode(body.build())
            .build();
    }
}
