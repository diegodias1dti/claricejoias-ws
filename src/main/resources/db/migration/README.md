# Migrations (Flyway)

O schema atual (producao) foi assumido como baseline (V1) — nao existe um V1 script porque
o Flyway so passou a controlar o banco a partir daqui, com as tabelas ja existentes.

Para qualquer mudanca de schema futura (nova coluna, nova tabela, novo indice, nova
constraint), crie um arquivo `V<numero>__descricao.sql` nesta pasta, por exemplo:

```
V2__adiciona_fk_pedido_cliente.sql
V3__adiciona_coluna_ativo_em_clientes.sql
```

O numero da versao deve ser sempre maior que o ultimo aplicado (confira a tabela
`flyway_schema_history` no banco). Nunca edite um script ja aplicado em producao —
crie um novo.
