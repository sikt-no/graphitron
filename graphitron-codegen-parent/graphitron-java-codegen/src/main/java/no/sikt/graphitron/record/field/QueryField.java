package no.sikt.graphitron.record.field;

/**
 * A field on the {@code Query} type. Read-only. All create a new scope or enter private service scope.
 */
public sealed interface QueryField extends RootField
    permits LookupQueryField, TableQueryField, TableMethodQueryField,
            NodeQueryField, EntityQueryField,
            TableInterfaceQueryField, InterfaceQueryField, UnionQueryField,
            ServiceQueryField {}
