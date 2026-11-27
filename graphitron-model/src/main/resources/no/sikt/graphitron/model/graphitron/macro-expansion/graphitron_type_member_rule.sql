SELECT u.graph_name, u.union_name AS container_name, c.kind AS container_kind, u.member_type_name, m.kind AS member_kind, u.position
  FROM graphql_union_member u
  JOIN graphitron_type c
    ON c.graph_name = u.graph_name AND c.type_name = u.union_name AND c.kind = 'UNION'
  JOIN graphitron_type m
    ON m.graph_name = u.graph_name AND m.type_name = u.member_type_name
   AND m.kind IN ('OBJECT', 'INTERFACE')
UNION ALL
SELECT i.graph_name, i.interface_name, c.kind, i.type_name, m.kind, i.position
  FROM graphql_implements_interface i
  JOIN graphitron_type c
    ON c.graph_name = i.graph_name AND c.type_name = i.interface_name AND c.kind = 'INTERFACE'
  JOIN graphitron_type m
    ON m.graph_name = i.graph_name AND m.type_name = i.type_name
   AND m.kind IN ('OBJECT', 'INTERFACE')
