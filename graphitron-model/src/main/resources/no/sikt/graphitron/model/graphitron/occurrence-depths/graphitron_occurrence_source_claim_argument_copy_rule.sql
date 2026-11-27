SELECT DISTINCT o.graph_name, o.coordinate, 'argumentCopy' AS rule, 'TABLE' AS kind, pt.table_source_name, pt.table_schema, pt.table_name
  FROM graphitron_occurrence o
  JOIN graphitron_occurrence p ON p.graph_name = o.graph_name AND p.coordinate = o.parent_coordinate
  JOIN graphitron_field pf ON pf.graph_name = p.graph_name AND pf.coordinate = p.element_coordinate
  JOIN graphitron_field_target ft
    ON ft.graph_name = pf.graph_name AND ft.type_name = pf.type_name AND ft.field_name = pf.field_name
  JOIN graphitron_occurrence_source_table pt ON pt.graph_name = p.graph_name AND pt.coordinate = p.coordinate
 WHERE o.kind = 'FIELD_ARGUMENT' AND NOT EXISTS (SELECT 1 FROM graphitron_join_path_claim wpc
                 WHERE wpc.graph_name = pf.graph_name AND wpc.element_coordinate = pf.coordinate
                   AND wpc.claim_kind = 'FIELD' AND wpc.last_place >= wpc.first_place)
   AND NOT EXISTS (SELECT 1 FROM graphitron_tabletype tt
                     WHERE tt.graph_name = ft.graph_name AND tt.type_name = ft.target_type_name)
   AND NOT EXISTS (SELECT 1 FROM graphitron_type_member pm
                     JOIN graphitron_tabletype mt
                       ON mt.graph_name = pm.graph_name AND mt.type_name = pm.member_type_name
                    WHERE pm.graph_name = ft.graph_name AND pm.container_name = ft.target_type_name)
