package no.sikt.graphitron.emit;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.render.CatalogRefs;

import javax.lang.model.element.Modifier;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The fetcher of an {@code @issue} field: one voucher per row it is selected on, signed over the row's
 * components by the operation's issuer for the field's scope and lifetime. The components' columns are
 * on the row because the type's projection selects them whenever the field is selected, so the fetcher
 * reads the row it is handed and runs no statement. Each value is serialized by its own scalar before it
 * is signed, so the voucher carries what a plain representation of the field would have.
 */
public final class VoucherIssueEmitter {

    private static final ClassName DATA_FETCHING_ENV = ClassName.get("graphql.schema", "DataFetchingEnvironment");
    private static final ClassName GRAPHQL_CONTEXT = ClassName.get("graphql", "GraphQLContext");
    private static final ClassName RECORD = ClassName.get("org.jooq", "Record");

    private VoucherIssueEmitter() {}

    /**
     * The field's fetcher, a {@code public static} method named after the field.
     *
     * @param issue        the field's row
     * @param outputPackage the consumer's root output package, under whose {@code schema} package the codec lands
     */
    public static MethodSpec fetcher(VoucherIssueQuery.Issue issue, String outputPackage) {
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
        return MethodSpec.methodBuilder(issue.fieldName())
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(Object.class)
            .addParameter(DATA_FETCHING_ENV, "env")
            .addJavadoc("The voucher for this row of {@code $L}, issued under scope {@code $L}.\n",
                issue.typeName(), issue.scope())
            .addCode(body.build())
            .build();
    }
}
