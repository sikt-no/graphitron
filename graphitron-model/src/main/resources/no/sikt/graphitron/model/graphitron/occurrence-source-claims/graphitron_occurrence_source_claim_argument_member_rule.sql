SELECT DISTINCT o.graph_name, o.coordinate, 'argumentMember' AS rule, 'TABLE' AS kind, mt.table_source_name, mt.table_schema, mt.table_name
  FROM graphitron_occurrence o
  JOIN graphitron_occurrence p ON p.graph_name = o.graph_name AND p.coordinate = o.parent_coordinate
  JOIN graphitron_field pf ON pf.graph_name = p.graph_name AND pf.coordinate = p.element_coordinate
  JOIN graphitron_field_target ft
    ON ft.graph_name = pf.graph_name AND ft.type_name = pf.type_name AND ft.field_name = pf.field_name
  JOIN graphitron_type_member pm ON pm.graph_name = ft.graph_name AND pm.container_name = ft.target_type_name
  JOIN graphitron_tabletype mt ON mt.graph_name = pm.graph_name AND mt.type_name = pm.member_type_name
 WHERE o.kind = 'FIELD_ARGUMENT' AND NOT EXISTS (SELECT 1 FROM graphitron_join_path_claim wpc
                 WHERE wpc.graph_name = pf.graph_name AND wpc.element_coordinate = pf.coordinate
                   AND wpc.claim_kind = 'FIELD' AND wpc.last_place >= wpc.first_place)
   AND NOT EXISTS (SELECT 1 FROM graphitron_tabletype tt
                     WHERE tt.graph_name = ft.graph_name AND tt.type_name = ft.target_type_name)
