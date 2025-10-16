package no.sikt.graphitron.queries.fetch;

import no.sikt.graphitron.common.GeneratorTest;
import no.sikt.graphitron.generators.abstractions.ClassGenerator;
import no.sikt.graphitron.reducedgenerators.MapOnlyFetchDBClassGenerator;
import no.sikt.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static no.sikt.graphitron.common.configuration.SchemaComponent.*;

@DisplayName("Query outputs - Row structure and return types")
public class OutputTest extends GeneratorTest {
    @Override
    protected String getSubpath() {
        return "queries/fetch/output";
    }

    @Override
    protected List<ClassGenerator> makeGenerators(ProcessedSchema schema) {
        return List.of(new MapOnlyFetchDBClassGenerator(schema));
    }

    @Test
    @DisplayName("Simple table type return")
    void defaultCase() {
        assertGeneratedContentMatches("default", CUSTOMER_TABLE); //here
    }

    @Test
    @DisplayName("Listed return type")
    void listed() {
        assertGeneratedContentContains(
                "listed", Set.of(CUSTOMER_TABLE),
                "List<CustomerTable> queryForQuery",
                ".select(queryForQuery_customerTable(",
                ".fetch(_iv_it -> _iv_it.into(CustomerTable.class"
        );
    }

    @Test
    @DisplayName("Simple table type return on non-root level")
    void splitQuery() {
        assertGeneratedContentMatches("splitQuery", CUSTOMER_TABLE, SPLIT_QUERY_WRAPPER); //here

      //  assertGeneratedContentContains(
      //          "splitQuery", Set.of(CUSTOMER_TABLE, SPLIT_QUERY_WRAPPER),
        //          "Map<Row1<Long>, CustomerTable> queryForWrapper",
        //         "Set<Row1<Long>> _rk_wrapper",
        //       ".select(DSL.row(_a_address.ADDRESS_ID),DSL.field(",
        //       ".where(DSL.row(_a_address.ADDRESS_ID).in(_rk_wrapper))",
        //        ".fetchMap(_iv_r -> _iv_r.value1().valuesRow(), Record2::value2"
        //);
    }

    @Test
    @DisplayName("Listed return type on non-root level")
    void splitQueryListed() {
        assertGeneratedContentContains(
                "splitQueryListed", Set.of(CUSTOMER_TABLE, SPLIT_QUERY_WRAPPER),
                "Map<Row1<Long>, List<CustomerTable>> queryForWrapper",
                ".select(DSL.row(_a_address.ADDRESS_ID),DSL.multiset(DSL.select",
                ".fetchMap(_iv_r -> _iv_r.value1().valuesRow(), _iv_r -> _iv_r.value2().map(Record1::value1))"
        );
    }

    @Test
    @DisplayName("Implicit splitquery due to argument")
    void implicitSplitQuery() {
        assertFilesAreGenerated(
                "implicitSplitQuery", Set.of(CUSTOMER_TABLE, SPLIT_QUERY_WRAPPER),
                "WrapperDBQueries"
        );
    }

    @Test
    @DisplayName("Containing field annotated with @splitQuery")
    void skipsSplits() {
        resultDoesNotContain("skipsSplits", Set.of(CUSTOMER_TABLE), "CustomerTable::new");
    }

    @Test // TODO: Should result in an error.
    @DisplayName("Containing field annotated with @splitQuery and no other fields")
    void noGeneratedField() {
        assertGeneratedContentContains("noGeneratedField", Set.of(CUSTOMER_TABLE), "return .mapping(Functions.nullOnAllNull(Address::new))");
    }

    @Test
    @DisplayName("Containing field annotated with @field")
    void fieldOverride() {
        assertGeneratedContentContains("fieldOverride", "customer.FIRST_NAME");
    }

    @Test // Not sure if this is allowed. Note, this is actually invalid in our integration tests, since the IDs are jOOQ fields instead of get methods.
    @DisplayName("Containing ID field annotated with @field and has no @nodeId")
    void fieldOverrideID() {
        assertGeneratedContentContains("fieldOverrideID", "payment.getCustomerId()");
    }

    @Test
    @DisplayName("Field annotated with @externalField should use method extended on field's jooq table")
    void externalField() {
        assertGeneratedContentContains("externalField",
                "return DSL.row(no.sikt.graphitron.codereferences.extensionmethods.ClassWithExtensionMethod.name(_a_customer))"
        );
    }

    @Test
    @DisplayName("Field annotated with @externalField should map types correctly even when over 22 fields")
    void externalFieldOver22() {
        assertGeneratedContentContains("externalFieldOver22",
                "return DSL.row(no.sikt.graphitron.codereferences.extensionmethods.ClassWithExtensionMethod.name(_a_customer),",
                "no.sikt.graphitron.codereferences.extensionmethods.ClassWithExtensionMethod.name(_a_customer).getDataType().convert(_iv_r[0]),"
        );
    }

    @Test
    @DisplayName("Returning string without type wrapping")
    void noWrapping() {
        assertGeneratedContentContains(
                "noWrapping", Set.of(CUSTOMER_INPUT_TABLE),
                "String queryForQuery",
                ".select(_a_customer.FIRST_NAME)",
                ".fetchOne(_iv_it -> _iv_it.into(String.class));"
        );
    }

    @Test
    @DisplayName("Returning listed string without type wrapping")
    void noWrappingListed() {
        assertGeneratedContentContains(
                "noWrappingListed", Set.of(CUSTOMER_INPUT_TABLE),
                "List<String> queryForQuery",
                ".fetch(_iv_it -> _iv_it.into(String.class));"
        );
    }

    @Test
    @DisplayName("Query fetching two fields")
    void multipleFields() {
        assertGeneratedContentContains("multipleFields", ".row(_a_customer.getId(),_a_customer.EMAIL)");
    }

    @Test // TODO: Should result in an error.
    @DisplayName("Containing field annotated with incorrect @field")
    void invalidFieldOverride() {
        assertGeneratedContentContains("invalidFieldOverride", "customer.WRONG");
    }

    @Test
    @DisplayName("Type without a table on root level wrapping one that has table set")
    void outerNestedRow() {
        assertGeneratedContentContains(
                "outerNestedRow", Set.of(CUSTOMER_TABLE),
                """
                        public static Wrapper queryForQuery(DSLContext _iv_ctx, SelectionSet _iv_select) {
                            var _a_customer = CUSTOMER.as("customer_2168032777");
                            return _iv_ctx
                                    .select(queryForQuery_wrapper())
                                    .fetchOne(_iv_it -> _iv_it.into(Wrapper.class));
                        }
                        private static SelectField<Wrapper> queryForQuery_wrapper() {
                            var _a_customer = CUSTOMER.as("customer_2168032777");
                            return DSL.row(
                                    DSL.field(
                                            DSL.select(queryForQuery_wrapper_customer(_a_customer))
                                            .from(_a_customer)
                                    )
                            ).mapping(Functions.nullOnAllNull(Wrapper::new));
                        }
                        private static SelectField<CustomerTable> queryForQuery_wrapper_customer(Customer _a_customer) {
                            return DSL.row(_a_customer.getId()).mapping(Functions.nullOnAllNull(CustomerTable::new));
                        }
                        """
        );
    }

    @Test
    @DisplayName("Type without a table on root level wrapping a listed one that has table set")
    void outerNestedListedRow() {
        assertGeneratedContentContains(
                "outerNestedListedRow", Set.of(CUSTOMER_TABLE),
                """
                        public static Wrapper queryForQuery(DSLContext _iv_ctx, SelectionSet _iv_select) {
                            var _a_customer = CUSTOMER.as("customer_2168032777");
                            return _iv_ctx
                                    .select(queryForQuery_wrapper())
                                    .fetchOne(_iv_it -> _iv_it.into(Wrapper.class));
                        }
                        private static SelectField<Wrapper> queryForQuery_wrapper() {
                            var _a_customer = CUSTOMER.as("customer_2168032777");
                            return DSL.row(
                                    DSL.row(
                                            DSL.multiset(
                                                    DSL.select(queryForQuery_wrapper_customer(_a_customer))
                                                    .from(_a_customer)
                                                    .orderBy(_a_customer.fields(_a_customer.getPrimaryKey().getFieldsArray()))
                                            )
                                    ).mapping(_iv_e -> _iv_e.map(Record1::value1))
                            ).mapping(Functions.nullOnAllNull((_iv_it) -> new Wrapper(_iv_it)));
                        }
                        private static SelectField<CustomerTable> queryForQuery_wrapper_customer(Customer _a_customer) {
                            return DSL.row(_a_customer.getId()).mapping(Functions.nullOnAllNull(CustomerTable::new));
                        }
                        """
        );
    }

    @Test  // TODO: Should result in an error. Email has no source.
    @DisplayName("Type without a table on root level wrapping one that has table set with an extra intermediate field")
    void outerNestedRowExtraField() {
        assertGeneratedContentContains(
                "outerNestedRowExtraField", Set.of(CUSTOMER_TABLE),
                ".row(.EMAIL,DSL.field(DSL.select(queryForQuery_wrapper_customer(_a_customer))"
        );
    }

    @Test
    @DisplayName("Two types without a table on root level wrapping one that has table set")
    void outerDoubleNestedRow() {
        assertGeneratedContentContains(
                "outerDoubleNestedRow", Set.of(CUSTOMER_TABLE),
                """
                            public static Wrapper1 queryForQuery(DSLContext _iv_ctx, SelectionSet _iv_select) {
                                var _a_customer = CUSTOMER.as("customer_2168032777");
                                return _iv_ctx
                                        .select(queryForQuery_wrapper1())
                                        .fetchOne(_iv_it -> _iv_it.into(Wrapper1.class));
                            }
                            private static SelectField<Wrapper1> queryForQuery_wrapper1() {
                                var _a_customer = CUSTOMER.as("customer_2168032777");
                                return DSL.row(
                                      DSL.row(
                                              DSL.field(
                                                      DSL.select(DSL.row(_a_customer.getId()).mapping(Functions.nullOnAllNull(CustomerTable::new)))
                                                      .from(_a_customer)
                                              )
                                      ).mapping(Functions.nullOnAllNull(Wrapper2::new))
                                ).mapping(Functions.nullOnAllNull(Wrapper1::new));
                            }
                            """
        );
    }

    @Test
    @DisplayName("Nested output with an argument")
    void outerNestedWithArgument() {
        assertGeneratedContentContains(
                "outerNestedWithArgument", Set.of(CUSTOMER_TABLE),
                """
                        public class QueryDBQueries {
                            public static Outer queryForQuery(DSLContext _iv_ctx, String firstName, SelectionSet _iv_select) {
                                var _a_customer = CUSTOMER.as("customer_2168032777");
                                return _iv_ctx
                                        .select(queryForQuery_outer(firstName))
                                        .fetchOne(_iv_it -> _iv_it.into(Outer.class));
                            }
                            private static SelectField<Outer> queryForQuery_outer(String firstName) {
                                var _a_customer = CUSTOMER.as("customer_2168032777");
                                return DSL.row(
                                        DSL.field(
                                                DSL.select(queryForQuery_outer_customers(_a_customer))
                                                .from(_a_customer)
                                                .where(firstName != null ? _a_customer.FIRST_NAME.eq(firstName) : DSL.noCondition())
                                        )
                                ).mapping(Functions.nullOnAllNull(Outer::new));
                            }
                            private static SelectField<CustomerTable> queryForQuery_outer_customers(Customer _a_customer) {
                                return DSL.row(_a_customer.getId()).mapping(Functions.nullOnAllNull(CustomerTable::new));
                            }
                        }""" // Checks that no outer where statement exists.
        );
    }

    @Test
    @DisplayName("Nested output with a listed argument")
    void outerNestedWithArgumentListed() {
        assertGeneratedContentContains(
                "outerNestedWithArgumentListed", Set.of(CUSTOMER_TABLE),
                """
                            public static Outer queryForQuery(DSLContext _iv_ctx, List<String> firstName, SelectionSet _iv_select) {
                                var _a_customer = CUSTOMER.as("customer_2168032777");
                                return _iv_ctx
                                        .select(queryForQuery_outer(firstName))
                                        .fetchOne(_iv_it -> _iv_it.into(Outer.class));
                            }
                            private static SelectField<Outer> queryForQuery_outer(List<String> firstName) {
                                var _a_customer = CUSTOMER.as("customer_2168032777");
                                return DSL.row(
                                        DSL.row(
                                                DSL.multiset(
                                                        DSL.select(queryForQuery_outer_customers(_a_customer))
                                                        .from(_a_customer)
                                                        .where(firstName.size() > 0 ? _a_customer.FIRST_NAME.in(firstName) : DSL.noCondition())
                                                        .orderBy(_a_customer.fields(_a_customer.getPrimaryKey().getFieldsArray()))
                                                )
                                        ).mapping(_iv_e -> _iv_e.map(Record1::value1))
                                ).mapping(Functions.nullOnAllNull((_iv_it) -> new Outer(_iv_it)));
                            }
                            private static SelectField<CustomerTable> queryForQuery_outer_customers(Customer _a_customer) {
                                return DSL.row(_a_customer.getId()).mapping(Functions.nullOnAllNull(CustomerTable::new));
                            }
                        """
        );
    }

    @Test
    @DisplayName("Double nested output with an argument")
    void outerDoubleNestedWithArgument() {
        assertGeneratedContentContains(
                "outerDoubleNestedWithArgument", Set.of(CUSTOMER_TABLE),
                """
                        public static Wrapper1 queryForQuery(DSLContext _iv_ctx, String firstName, SelectionSet _iv_select) {
                            var _a_customer = CUSTOMER.as("customer_2168032777");
                            return _iv_ctx
                                    .select(queryForQuery_wrapper1(firstName))
                                    .fetchOne(_iv_it -> _iv_it.into(Wrapper1.class));
                        }
                        private static SelectField<Wrapper1> queryForQuery_wrapper1(String firstName) {
                            var _a_customer = CUSTOMER.as("customer_2168032777");
                            return DSL.row(
                                    DSL.row(
                                            DSL.field(
                                                    DSL.select(DSL.row(_a_customer.getId()).mapping(Functions.nullOnAllNull(CustomerTable::new)))
                                                    .from(_a_customer)
                                                    .where(firstName != null ? _a_customer.FIRST_NAME.eq(firstName) : DSL.noCondition())
                                            )
                                    ).mapping(Functions.nullOnAllNull(Wrapper2::new))
                            ).mapping(Functions.nullOnAllNull(Wrapper1::new));
                        }""" //TODO extract the inner row as well?
        );
    }

    @Test
    @DisplayName("Type without a table on root level wrapping a list of errors")
    void outerNestedWithListedError() {
        assertGeneratedContentContains(
                "outerNestedWithListedError", Set.of(CUSTOMER_TABLE, VALIDATION_ERROR),
                ".mapping(Functions.nullOnAllNull((_iv_it) -> new Wrapper(_iv_it)"
        );
    }

    @Test
    @DisplayName("Type without a table inside one that has table set")
    void innerNestedRow() {
        assertGeneratedContentContains(
                "innerNestedRow",
                ".row(DSL.row(_a_customer.getId()).mapping(Functions.nullOnAllNull(Wrapper::new))).mapping(Functions.nullOnAllNull(Customer::new"
        );
    }

    @Test
    @DisplayName("Type without a table inside one that has table set with an extra intermediate field")
    void innerNestedRowExtraField() {
        assertGeneratedContentContains(
                "innerNestedRowExtraField",
                ".row(_a_customer.EMAIL,DSL.row(_a_customer.getId()).mapping(Functions.nullOnAllNull(Wrapper::new"
        );
    }

    @Test
    @DisplayName("Two types without a table inside one that has table set")
    void innerDoubleNestedRow() {
        assertGeneratedContentContains(
                "innerDoubleNestedRow",
                ".row(DSL.row(DSL.row(_a_customer.getId())" +
                        ".mapping(Functions.nullOnAllNull(Wrapper2::new)))" +
                        ".mapping(Functions.nullOnAllNull(Wrapper1::new)))" +
                        ".mapping(Functions.nullOnAllNull(Customer::new"
        );
    }

    @Test // jOOQ stops type checking at 22 fields.
    @DisplayName("Row with more than 22 fields")
    void over22Fields() {
        assertGeneratedContentMatches("over22Fields");
    }

    @Test
    @DisplayName("Row with more than 22 fields where some have types other than string")
    void over22FieldsWithVariousTypes() {
        assertGeneratedContentContains(
                "over22FieldsWithVariousTypes", Set.of(DUMMY_ENUM),
                "film.LENGTH",
                "film.RATING.convert",
                "film.LENGTH.getDataType().convert(_iv_r[21",
                "(DummyEnum) _iv_r[22"
        );
    }

    @Test
    @DisplayName("Row with more than 22 fields including an inner row")
    void over22FieldsWithInnerRow() {
        assertGeneratedContentContains("over22FieldsWithInnerRow", "Wrapper::new", "(Wrapper) _iv_r[22");
    }

    @Test
    @DisplayName("Row with more than 22 fields including multiset")
    void over22FieldsWithMultiset() {
        assertGeneratedContentContains("over22FieldsWithMultiset", "Wrapper::new", "(List<Wrapper>) _iv_r[22]");
    }

    @Test
    @DisplayName("Row with more than 22 fields including key for splitQuery field")
    void over22FieldsWithSplitQuery() {
        assertGeneratedContentContains("over22FieldsWithSplitQuery",
                "new Film( (Record1<Long>) _iv_r[0], _a_film.TITLE.getDataType().convert(_iv_r[1])",
                "_iv_r[22]))"
        );
    }

    @Test // Enhanced null check treats empty objects as null.
    @DisplayName("Required row with optional fields")
    void requiredRowWithOptionalFields() {
        assertGeneratedContentContains(
                "requiredRowWithOptionalFields",
                ".row(DSL.row(_a_customer.EMAIL).mapping(Wrapper::new))" +
                        ".mapping((_iv_e0) -> (_iv_e0 == null || new Wrapper().equals(_iv_e0)) ? null : new Customer(_iv_e0)"
        );
    }

    @Test
    @DisplayName("Required row with optional field and a neighbour field")
    void requiredRowWithOptionalFieldsAndNeighbourField() {
        assertGeneratedContentContains(
                "requiredRowWithOptionalFieldsAndNeighbourField",
                ".mapping((_iv_e0, _iv_e1) -> _iv_e0 == null && (_iv_e1 == null || new Wrapper().equals(_iv_e1)) ? null : new Customer(_iv_e0, _iv_e1"
        );
    }

    @Test
    @DisplayName("Required row with optional field and a neighbour row")
    void requiredRowWithOptionalFieldsAndNeighbourRow() {
        assertGeneratedContentContains(
                "requiredRowWithOptionalFieldsAndNeighbourRow",
                ".mapping(Wrapper::new",
                ".mapping(Functions.nullOnAllNull(Wrapper::new",
                ".mapping((_iv_e0, _iv_e1) -> (_iv_e0 == null || new Wrapper().equals(_iv_e0)) && (_iv_e1 == null || new Wrapper().equals(_iv_e1)) ? null : new Customer(_iv_e0, _iv_e1"
        );
    }

    @Test
    @DisplayName("Required row with optional field and more than 22 fields")
    void requiredRowWithOptionalFieldsAndOver22Fields() {
        assertGeneratedContentContains(
                "requiredRowWithOptionalFieldsAndOver22Fields",
                ".mapping(Wrapper::new",
                ".mapping(Customer.class, _iv_r -> (_iv_r[0] == null || new Wrapper().equals(_iv_r[0]))" +
                        "&& _iv_r[1] == null && _iv_r[2] == null && _iv_r[3] == null && _iv_r[4] == null && _iv_r[5] == null && _iv_r[6] == null" +
                        "&& _iv_r[7] == null && _iv_r[8] == null && _iv_r[9] == null && _iv_r[10] == null && _iv_r[11] == null" +
                        "&& _iv_r[12] == null && _iv_r[13] == null && _iv_r[14] == null && _iv_r[15] == null && _iv_r[16] == null" +
                        "&& _iv_r[17] == null && _iv_r[18] == null && _iv_r[19] == null && _iv_r[20] == null && _iv_r[21] == null" +
                        "&& _iv_r[22] == null && _iv_r[23] == null ? null : new Customer("
        );
    }

    @Test
    @DisplayName("Type containing a table type (field subquery)")
    void innerTable() {
        assertGeneratedContentMatches("innerTable"); //here

//        assertGeneratedContentContains(
//                "innerTable",
//                ".row(DSL.field(DSL.select(DSL.row(_a_customer_",
//                ".mapping(Functions.nullOnAllNull(Address::new)))",
//                "))).mapping(Functions.nullOnAllNull(Customer::new");
    }

    @Test
    @DisplayName("Type containing a listed table type (multiset subquery)")
    void innerTableListed() {
        assertGeneratedContentContains(
                "innerTableListed",
                ".row(DSL.multiset(DSL.select(queryForQuery_customer_address(_a_customer_2168032777_address))",
                ".mapping(Functions.nullOnAllNull(Address::new))",
                "))).mapping(Functions.nullOnAllNull(Customer::new");
    }

    @Test
    @DisplayName("Type with a query that references itself")
    void innerTableSelfReference() {
        assertGeneratedContentContains(
                "innerTableSelfReference",
                ".row(_a_film.FILM_ID),DSL.field(",
                ".mapping(Functions.nullOnAllNull(Film::new");
    }

    @Test
    @DisplayName("Simple table type return from multiple schemas")
    void typesFromMultipleSchemas() {
        assertGeneratedContentContains("fromMultipleSchemas",
                Set.of(CUSTOMER_TABLE),
                "fromSchema1ForQuery",
                "fromSchema2ForQuery",
                "pguser.getId()",
                ".from(_a_pguser)");
    }

    @Test
    @DisplayName("Type implementing a table with a subtype collecting some of the table's fields.")
    void subtype() {
        assertGeneratedContentContains(
                "subtype",
                """
                        public class QueryDBQueries {
                            public static Customer queryForQuery(DSLContext _iv_ctx, SelectionSet _iv_select) {
                                var _a_customer = CUSTOMER.as("customer_2168032777");
                                return _iv_ctx
                                        .select(queryForQuery_customer())
                                        .from(_a_customer)
                                        .fetchOne(_iv_it -> _iv_it.into(Customer.class));
                            }
                            private static SelectField<Customer> queryForQuery_customer() {
                                var _a_customer = CUSTOMER.as("customer_2168032777");
                                return DSL.row(
                                        _a_customer.getId(),
                                        DSL.row(
                                                _a_customer.FIRST_NAME,
                                                _a_customer.LAST_NAME
                                        ).mapping(Functions.nullOnAllNull(CustomerName::new))
                                ).mapping(Functions.nullOnAllNull(Customer::new));
                            }
                        }
                        """);
    }
}
