-- A scalar whose candidates disagree has no class: the constant is named in two entries that
-- coerce differently, and nothing says which the generator is handed. A scalar carrying both
-- @scalarType and @generatedScalar has no class either: each arm stands aside where the other
-- directive is applied, so neither is silently picked and the two claims are the defect.
SELECT graph_name, type_name, 'SCALAR' AS kind, MIN(java_class) AS java_class, MIN(source) AS source
  FROM (SELECT t.graph_name, t.type_name, b.java_class, 'BUILT_IN' AS source
          FROM graphitron_type t
          JOIN (VALUES ('ID', 'java.lang.String'), ('String', 'java.lang.String'),
                       ('Int', 'java.lang.Integer'), ('Float', 'java.lang.Double'),
                       ('Boolean', 'java.lang.Boolean')) b (scalar, java_class)
            ON b.scalar = t.type_name
         WHERE t.kind = 'SCALAR'
        UNION ALL
        SELECT t.graph_name, t.type_name, k.input_type, 'SCALAR_TYPE'
          FROM graphql_directive_application da
          JOIN graphitron_ast_scalar_type_entry e
            ON e.graph_name = da.graph_name AND e.source_name = da.source_name
           AND e.source_line = da.source_line AND e.source_column = da.source_column
          JOIN graphitron_type t
            ON t.graph_name = da.graph_name AND t.type_name = da.coordinate AND t.kind = 'SCALAR'
          JOIN code_graph_class g
            ON g.graph_name = da.graph_name AND g.class_name = e.scalar_ref_class_part
          JOIN code_scalar_constant k
            ON k.source_name = g.source_name AND k.class_name = g.class_name
           AND k.field_name = e.scalar_ref_field_part
         WHERE da.directive_name = 'scalarType' AND da.ordinal = 0 AND k.input_type IS NOT NULL
           AND NOT EXISTS (SELECT 1 FROM graphql_directive_application g
                            WHERE g.graph_name = da.graph_name AND g.coordinate = da.coordinate
                              AND g.directive_name = 'generatedScalar')
        UNION ALL
        SELECT t.graph_name, t.type_name, 'java.lang.String', 'GENERATED'
          FROM graphql_directive_application da
          JOIN graphitron_ast_generated_scalar_entry e
            ON e.graph_name = da.graph_name AND e.source_name = da.source_name
           AND e.source_line = da.source_line AND e.source_column = da.source_column
          JOIN graphitron_type t
            ON t.graph_name = da.graph_name AND t.type_name = da.coordinate AND t.kind = 'SCALAR'
         WHERE da.directive_name = 'generatedScalar' AND da.ordinal = 0
           AND NOT EXISTS (SELECT 1 FROM graphql_directive_application s
                            WHERE s.graph_name = da.graph_name AND s.coordinate = da.coordinate
                              AND s.directive_name = 'scalarType')) c
 GROUP BY graph_name, type_name
HAVING COUNT(DISTINCT java_class) = 1
