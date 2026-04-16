package no.sikt.graphitron.example.datafetchers;

import graphql.schema.TypeResolver;
import org.jooq.Record;

import static no.sikt.graphitron.example.generated.jooq.Tables.ADDRESS;

public class AddressInterfaceTypeResolver {
    public static TypeResolver getName() {
        return _iv_env -> _iv_env.getSchema().getObjectType(getName(_iv_env.getObject()));
    }

    /*
    Eksempel på ny type resolver for interface med diskriminatorkolonne
     */
    public static <T extends Record> String getName(T record) {
        return switch (record.get(ADDRESS.POSTAL_CODE)) {
            case "22474" -> "AddressImplOne";
            case "9668" -> "AddressImplTwo";
            default -> throw new RuntimeException("bla bla");
        };
    }
}
