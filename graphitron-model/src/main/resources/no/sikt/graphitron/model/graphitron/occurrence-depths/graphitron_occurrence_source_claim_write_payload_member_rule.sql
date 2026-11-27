SELECT DISTINCT o.graph_name, o.coordinate, 'writePayloadMember' AS rule, 'TABLE' AS kind, pw.table_source_name, pw.table_schema, pw.table_name
  FROM graphitron_occurrence o
  JOIN graphitron_occurrence p ON p.graph_name = o.graph_name AND p.coordinate = o.parent_coordinate
  JOIN graphitron_field pf ON pf.graph_name = p.graph_name AND pf.coordinate = p.element_coordinate
  JOIN graphql_root_operation ro ON ro.graph_name = pf.graph_name AND ro.type_name = pf.type_name
  JOIN graphitron_producer_write pw
    ON pw.graph_name = pf.graph_name AND pw.type_name = pf.type_name AND pw.field_name = pf.field_name
  JOIN graphitron_field cf ON cf.graph_name = o.graph_name AND cf.coordinate = o.element_coordinate
  JOIN graphitron_field_target ft
    ON ft.graph_name = cf.graph_name AND ft.type_name = cf.type_name AND ft.field_name = cf.field_name
 WHERE o.kind = 'FIELD' AND p.kind = 'FIELD' AND pw.table_name IS NOT NULL
   AND NOT EXISTS (SELECT 1 FROM graphitron_tabletype tt
                     WHERE tt.graph_name = ft.graph_name AND tt.type_name = ft.target_type_name)
   AND EXISTS (SELECT 1 FROM graphitron_type_member pm
                 JOIN graphitron_tabletype mt
                   ON mt.graph_name = pm.graph_name AND mt.type_name = pm.member_type_name
                WHERE pm.graph_name = ft.graph_name AND pm.container_name = ft.target_type_name)
   AND NOT EXISTS (SELECT 1 FROM graphitron_occurrence_source po
                     WHERE po.graph_name = p.graph_name AND po.coordinate = p.coordinate)
   AND NOT EXISTS (SELECT 1 FROM graphitron_errors_field ef
                     WHERE ef.graph_name = cf.graph_name AND ef.type_name = cf.type_name
                       AND ef.field_name = cf.field_name)
