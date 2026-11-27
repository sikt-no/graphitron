-- Each facet a carrier reaches through its filter arguments, a facet field reached through two of them
-- kept at the first: one no earlier argument, or earlier facet field, reaches under the same name.
WITH reached (graph_name, type_name, field_name, argument_ordinal, facet_ordinal, filter_argument_name,
              facet_type_name, facet_field_name, column_name, value_type_name, value_nullable,
              source_name, source_line, source_column) AS (
  SELECT a.graph_name, a.type_name, a.field_name, a.ordinal, fb.ordinal, a.argument_name,
         fb.type_name, fb.field_name, fb.column_name, fb.value_type_name, fb.value_nullable,
         fb.source_name, fb.source_line, fb.source_column
    FROM graphitron_facet_binding fb
    JOIN graphql_argument a
      ON a.graph_name = fb.graph_name AND a.named_type = fb.type_name
    -- The carriers, asked for by name. This used to infer them from the expansion's own
    -- output, a minted field whose coining coordinate was its own, which made a reading of
    -- the carriers depend on the mint having already run.
    JOIN graphitron_connection_carrier c
      ON c.graph_name = a.graph_name AND c.type_name = a.type_name
     AND c.field_name = a.field_name
),
kept AS (
  SELECT r.* FROM reached r
   WHERE NOT EXISTS (SELECT 1 FROM reached e
                      WHERE e.graph_name = r.graph_name AND e.type_name = r.type_name
                        AND e.field_name = r.field_name AND e.facet_field_name = r.facet_field_name
                        AND (e.argument_ordinal, e.facet_ordinal) < (r.argument_ordinal, r.facet_ordinal))
)
-- Placed by how many of the carrier's kept facets come before it, argument first: dense from one.
SELECT k.graph_name, k.type_name, k.field_name,
       CAST(1 + (SELECT COUNT(*) FROM kept b
                  WHERE b.graph_name = k.graph_name AND b.type_name = k.type_name
                    AND b.field_name = k.field_name
                    AND (b.argument_ordinal, b.facet_ordinal) < (k.argument_ordinal, k.facet_ordinal)) AS INT) AS position,
       k.filter_argument_name, k.facet_type_name, k.facet_field_name, k.column_name, k.value_type_name,
       k.value_nullable, k.source_name, k.source_line, k.source_column
  FROM kept k
