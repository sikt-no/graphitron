SELECT DISTINCT o.graph_name, o.coordinate, 'argumentRoutine' AS rule, 'TABLE' AS kind, rr.table_source_name, rr.table_schema, rr.table_name
  FROM graphitron_occurrence o
  JOIN graphitron_occurrence p ON p.graph_name = o.graph_name AND p.coordinate = o.parent_coordinate AND p.depth = 0
  JOIN graphitron_field pf ON pf.graph_name = p.graph_name AND pf.coordinate = p.element_coordinate
  JOIN graphql_root_operation ro ON ro.graph_name = pf.graph_name AND ro.type_name = pf.type_name
  JOIN graphitron_routine_result rr
    ON rr.graph_name = pf.graph_name AND rr.type_name = pf.type_name AND rr.field_name = pf.field_name
 -- A return a table is bound to steps on from the result to that table, whose rows are filtered.
 WHERE o.kind = 'FIELD_ARGUMENT'
   AND NOT EXISTS (SELECT 1 FROM graphitron_tabletype tt
                    WHERE tt.graph_name = rr.graph_name AND tt.type_name = rr.target_type_name)
