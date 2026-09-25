-- =====================================================================================
-- SEED DE DADOS PARA TESTE MANUAL — claricejoias-ws
-- =====================================================================================
--
-- ⚠️  NÃO RODE ISSO EM PRODUÇÃO. Este script apaga (TRUNCATE) e recria dados nas
--     tabelas de negócio para você poder simular os fluxos do sistema livremente.
--     Use num banco local/dev (o do docker-compose, por exemplo).
--
-- Como rodar:
--   psql -h localhost -p 5433 -U postgres -d claricejoias -f scripts/seed-dados-teste.sql
--   (ajuste host/porta/usuário conforme seu application.properties / docker-compose.yml)
--
-- Pré-requisito: suba a aplicação pelo menos uma vez antes (spring.jpa.hibernate.ddl-auto=update
-- precisa ter criado as tabelas) ou rode as migrations, senão as tabelas abaixo não existem ainda.
--
-- Pode rodar este script quantas vezes quiser: ele limpa e reinsere tudo do zero
-- toda vez (idempotente), e resincroniza as sequences no final para a aplicação
-- não colidir com IDs novos gerados por ela depois.
-- =====================================================================================

BEGIN;

-- =====================================================================================
-- 0. LIMPEZA (ordem inversa de dependência de FK)
-- =====================================================================================
-- Aviso: fila_disparo, fila_cobranca e historico_disparo têm FK para lead/cliente,
-- então o CASCADE abaixo limpa elas também mesmo sem estarem na lista — é proposital
-- (evita registro órfão de fila apontando pra um cliente/lead que acabamos de apagar).
TRUNCATE TABLE
    pagamentos,
    parcelas,
    item_pedido,
    pedido,
    historico_cobranca,
    fila_cobranca,
    fila_disparo,
    historico_disparo,
    lead,
    estoque_revendedor,
    whatsapp_instance,
    clientes,
    revendedor,
    banners,
    produto_imagens,
    produto,
    subcategoria,
    categoria
RESTART IDENTITY CASCADE;

-- =====================================================================================
-- 1. CATÁLOGO — categorias, subcategorias e produtos
--    Regras que isso exercita: cadastro de produto, estoque central (Produto.diminuirEstoqueCentral),
--    catálogo público (GET /produtos, /categorias), rascunho vs ativo.
-- =====================================================================================

INSERT INTO categoria (id, nome, login_usuario) VALUES
    (1, 'Anéis',    'admin.sistema'),
    (2, 'Brincos',  'admin.sistema'),
    (3, 'Colares',  'admin.sistema');

INSERT INTO subcategoria (id, nome, categoria_id) VALUES
    (1, 'Solitário',        1),
    (2, 'Aliança',           1),
    (3, 'Argola',            2),
    (4, 'Ponto de Luz',      2),
    (5, 'Corrente',          3);

-- version=0 é obrigatório: a coluna passou a existir para habilitar lock otimista (@Version)
INSERT INTO produto (id, version, nome, preco, preco_custo, quantidade_estoque_central, codigo, material, rascunho, ativo, subcategoria_id, login_usuario) VALUES
    (1, 0, 'Anel Solitário Zircônia 18k',   450.00, 180.00, 20, 'AN-001', 'Ouro 18k',  false, true, 1, 'admin.sistema'),
    (2, 0, 'Anel Solitário Diamante',      1200.00, 600.00,  5, 'AN-002', 'Ouro 18k',  false, true, 1, 'admin.sistema'),
    (3, 0, 'Aliança Compromisso (par)',     890.00, 420.00, 15, 'AN-010', 'Ouro 18k',  false, true, 2, 'admin.sistema'),
    (4, 0, 'Brinco Argola Média',           220.00,  90.00, 30, 'BR-001', 'Prata 925', false, true, 3, 'admin.sistema'),
    (5, 0, 'Brinco Argola Grande',          280.00, 120.00, 25, 'BR-002', 'Prata 925', false, true, 3, 'admin.sistema'),
    (6, 0, 'Ponto de Luz Zircônia',         150.00,  60.00, 40, 'BR-020', 'Ouro 18k',  false, true, 4, 'admin.sistema'),
    (7, 0, 'Colar Corrente Veneziana',      650.00, 280.00, 12, 'CO-001', 'Ouro 18k',  false, true, 5, 'admin.sistema'),
    (8, 0, 'Colar Corrente Grumet Fina',    320.00, 140.00, 18, 'CO-002', 'Prata 925', false, true, 5, 'admin.sistema'),
    -- rascunho=true e ativo=true: produto "invisível" no catálogo público até publicar (testa a flag)
    (9, 0, 'Anel Rubi (em cadastro)',       780.00, 350.00,  3, 'AN-099', 'Ouro 18k',  true,  true, 1, 'admin.sistema');

-- =====================================================================================
-- 2. REVENDEDORAS — os IDs abaixo simulam o "sub" (UUID) do Keycloak.
--    Se você quiser testar via login de verdade (não só via SQL/API direto), crie
--    usuários no Keycloak com esses MESMOS UUIDs como Subject.
--    Regras que isso exercita: comissão por revendedora, slug único, vínculo de instância WhatsApp.
-- =====================================================================================

INSERT INTO revendedor (id, nome, email, ativo, slug, whatsapp_contato, percentual_comissao) VALUES
    ('a1111111-1111-1111-1111-111111111111', 'Maria Silva', 'maria.silva@clarice.test', true, 'maria-silva', '11988887777', 30.00),
    ('a2222222-2222-2222-2222-222222222222', 'Ana Souza',   'ana.souza@clarice.test',   true, 'ana-souza',   '11977776666', 25.00);

-- Instância de WhatsApp da Maria (usada por WhatsAppService/EvolutionApiService) e uma global (admin)
INSERT INTO whatsapp_instance (usuario_id, instance_name, unique_token, tipo_instancia, revendedor_id) VALUES
    ('a1111111-1111-1111-1111-111111111111', 'rev_mariasilva_a1111', 'a1a1a1a1-0000-0000-0000-000000000001', 'REVENDEDOR', 'a1111111-1111-1111-1111-111111111111'),
    ('a0000000-0000-0000-0000-000000000000', 'atendimento_matriz',   'a0a0a0a0-0000-0000-0000-000000000000', 'ADM',         NULL);

-- =====================================================================================
-- 3. MALETAS (estoque por revendedora) — testa transferência de estoque central -> maleta,
--    baixa de estoque na venda (EstoqueRevendedor.diminuirEstoque) e "produto não consta na maleta".
-- =====================================================================================

INSERT INTO estoque_revendedor (id, version, revendedor_id, produto_id, quantidade) VALUES
    (1, 0, 'a1111111-1111-1111-1111-111111111111', 1, 5),  -- Maria tem 5 Anéis Solitário Zircônia
    (2, 0, 'a1111111-1111-1111-1111-111111111111', 4, 8),  -- Maria tem 8 Brincos Argola Média
    (3, 0, 'a1111111-1111-1111-1111-111111111111', 6, 10), -- Maria tem 10 Ponto de Luz
    (4, 0, 'a2222222-2222-2222-2222-222222222222', 7, 3);  -- Ana tem 3 Colares Corrente Veneziana
    -- Dica de teste: tente vender o produto 2 (Anel Diamante) pela Maria no PDV — ela NÃO tem
    -- esse produto na maleta, então deve dar o erro "Produto não consta na sua maleta."

-- =====================================================================================
-- 4. CLIENTES — um da matriz (sem revendedora) e três vinculados a revendedoras.
--    Regras: isolamento de clientes por revendedora (findByRevendedorId), saldoDevedor,
--    e a checagem de posse que valida se a revendedora só mexe nos PRÓPRIOS clientes.
-- =====================================================================================

INSERT INTO clientes (id, nome, whatsapp, email, usuario_id, revendedor_id, saldo_devedor) VALUES
    (1, 'João Pereira',    '11999990001', 'joao.pereira@teste.com',   'c1111111-0000-0000-0000-000000000001', NULL,                                       0.00),
    (2, 'Fernanda Lima',   '11999990002', 'fernanda.lima@teste.com',  'c2222222-0000-0000-0000-000000000002', 'a1111111-1111-1111-1111-111111111111',   380.00),
    (3, 'Patrícia Gomes',  '11999990003', 'patricia.gomes@teste.com', 'c3333333-0000-0000-0000-000000000003', 'a1111111-1111-1111-1111-111111111111',     0.00),
    (4, 'Camila Rocha',    '11999990004', 'camila.rocha@teste.com',   'c4444444-0000-0000-0000-000000000004', 'a2222222-2222-2222-2222-222222222222',     0.00);
    -- Dica de teste (IDOR): logada como Maria, tente acessar /clientes/4 (Camila, da Ana).
    -- Deve ser bloqueada — é exatamente a checagem de posse que adicionamos em ClienteService.

-- =====================================================================================
-- 5. PEDIDOS — cobre os principais fluxos: PDV admin, PDV revendedora à vista, PDV
--    revendedora fiado com parcelas, checkout online pendente, e carrinho aberto.
-- =====================================================================================

-- Pedido 1: venda PDV feita pelo ADMIN (sem revendedora), à vista no Pix, cliente da matriz
INSERT INTO pedido (id, cliente_id, origem, status, usuario_id, valor_recebido, troco, total_cobrado, valor_entrada, valor_devido,
                     revendedor_id, metodo_pagamento, parcelas, data_criacao, data_atualizacao, total, login_operador, comissao_revendedor, total_lucro) VALUES
    (1, 1, 'PDV', 'PAGO', NULL, 450.00, 0.00, 450.00, 0.00, 0.00, NULL, 'pix', 1,
     NOW() - INTERVAL '10 days', NOW() - INTERVAL '10 days', 450.00, 'admin.sistema', 0.00, 270.00);

INSERT INTO item_pedido (id, pedido_id, produto_id, quantidade, subtotal, preco_unitario, custo_unitario, lucro) VALUES
    (1, 1, 1, 1, 450.00, 450.00, 180.00, 270.00);

-- Pedido 2: venda PDV da Maria, FIADO, com entrada + 3 parcelas (o caso mais completo pra testar)
INSERT INTO pedido (id, cliente_id, origem, status, usuario_id, valor_recebido, troco, total_cobrado, valor_entrada, valor_devido,
                     revendedor_id, metodo_pagamento, parcelas, data_criacao, data_atualizacao, total, login_operador, comissao_revendedor, total_lucro) VALUES
    -- valor_devido já está líquido da 1ª parcela paga (570,00 originais - 190,00 pagos = 380,00),
    -- coerente com cliente.saldo_devedor da Fernanda e com o Pagamento inserido logo abaixo.
    (2, 2, 'PDV', 'PAGO', NULL, 670.00, 0.00, 670.00, 100.00, 380.00, 'a1111111-1111-1111-1111-111111111111', 'fiado', 3,
     NOW() - INTERVAL '60 days', NOW() - INTERVAL '60 days', 670.00, 'maria.silva', 201.00, 400.00);

INSERT INTO item_pedido (id, pedido_id, produto_id, quantidade, subtotal, preco_unitario, custo_unitario, lucro) VALUES
    (2, 2, 4, 1, 220.00, 220.00,  90.00, 130.00),
    (3, 2, 6, 3, 450.00, 150.00,  60.00, 270.00);

-- 3 parcelas de R$190,00 (570 / 3 exato): uma já paga, uma vencida ainda como PENDENTE
-- (pra você rodar a verificação e ver virar ATRASADA) e uma futura.
INSERT INTO parcelas (id, pedido_id, numero_parcela, valor, data_vencimento, data_pagamento, status) VALUES
    (1, 2, 1, 190.00, (CURRENT_DATE - INTERVAL '32 days')::date, (CURRENT_DATE - INTERVAL '30 days')::date, 'PAGA'),
    (2, 2, 2, 190.00, (CURRENT_DATE - INTERVAL '2 days')::date,  NULL,                                     'PENDENTE'),
    (3, 2, 3, 190.00, (CURRENT_DATE + INTERVAL '28 days')::date, NULL,                                     'PENDENTE');
    -- Dica de teste: chame o job/endpoint que roda ParcelaService.verificarParcelasVencidas
    -- (ou espere a meia-noite) e confira que só a parcela 2 vira ATRASADA.

INSERT INTO pagamentos (id, cliente_id, valor_pago, forma_pagamento, data_pagamento, observacao, pedido_id) VALUES
    (1, 2, 190.00, 'dinheiro', (CURRENT_DATE - INTERVAL '30 days')::date, 'Pagamento direto da 1ª parcela.', 2);

-- Cobrança recente pra Fernanda: testa o cooldown de 24h (app.whatsapp.cooldown-horas) —
-- tentar cobrar de novo agora deve ser bloqueado.
INSERT INTO historico_cobranca (id, data_hora, funcionario, cliente_id) VALUES
    (1, NOW() - INTERVAL '3 hours', 'Maria Silva', 2);

-- Pedido 3: venda PDV da Maria, à vista no cartão, cliente já quitado (Patrícia)
INSERT INTO pedido (id, cliente_id, origem, status, usuario_id, valor_recebido, troco, total_cobrado, valor_entrada, valor_devido,
                     revendedor_id, metodo_pagamento, parcelas, data_criacao, data_atualizacao, total, login_operador, comissao_revendedor, total_lucro) VALUES
    (3, 3, 'PDV', 'PAGO', NULL, 280.00, 0.00, 280.00, 0.00, 0.00, 'a1111111-1111-1111-1111-111111111111', 'cartao', 1,
     NOW() - INTERVAL '5 days', NOW() - INTERVAL '5 days', 280.00, 'maria.silva', 84.00, 160.00);

INSERT INTO item_pedido (id, pedido_id, produto_id, quantidade, subtotal, preco_unitario, custo_unitario, lucro) VALUES
    (4, 3, 5, 1, 280.00, 280.00, 120.00, 160.00);
    -- Dica de teste: GET /revendedores/a1111111-1111-1111-1111-111111111111/acerto no mês atual
    -- deve somar os pedidos 2 e 3 (os PAGO da Maria) — não deve incluir os pedidos da Ana.

-- Pedido 4: checkout ONLINE na loja da Ana, ainda pendente de pagamento (Pix)
-- Este é DE PROPÓSITO o pedido que reproduz a lacuna que reportei: comissão zerada e
-- sem parcelas geradas mesmo tendo revendedora vinculada, porque o checkout online
-- ainda não replica a lógica do PDV.
INSERT INTO pedido (id, cliente_id, origem, status, usuario_id, valor_recebido, troco, total_cobrado, valor_entrada, valor_devido,
                     revendedor_id, metodo_pagamento, parcelas, data_criacao, data_atualizacao, total, login_operador, comissao_revendedor, total_lucro) VALUES
    (4, 4, 'ECOMMERCE', 'PENDENTE_PAGAMENTO', NULL, NULL, NULL, 650.00, 0.00, 0.00, 'a2222222-2222-2222-2222-222222222222', 'pix', 1,
     NOW() - INTERVAL '1 day', NOW() - INTERVAL '1 day', 650.00, NULL, 0.00, 0.00);

INSERT INTO item_pedido (id, pedido_id, produto_id, quantidade, subtotal, preco_unitario, custo_unitario, lucro) VALUES
    (5, 4, 7, 1, 650.00, 650.00, 280.00, 370.00);

-- Pedido 5: carrinho ABERTO e anônimo na loja MATRIZ (visitante, sem login, sem revendedora)
-- Serve pra reproduzir o bug do carrinho da matriz que não recarrega ao voltar ao site.
INSERT INTO pedido (id, cliente_id, origem, status, usuario_id, visitor_id, valor_recebido, troco, total_cobrado, valor_entrada, valor_devido,
                     revendedor_id, metodo_pagamento, parcelas, data_criacao, data_atualizacao, total, login_operador, comissao_revendedor, total_lucro) VALUES
    (5, NULL, 'ECOMMERCE', 'CARRINHO', NULL, 'visitor-demo-0001', NULL, NULL, 1200.00, 0.00, 0.00, NULL, 'PENDENTE', NULL,
     NOW(), NOW(), 1200.00, NULL, 0.00, 0.00);

INSERT INTO item_pedido (id, pedido_id, produto_id, quantidade, subtotal, preco_unitario, custo_unitario, lucro) VALUES
    (6, 5, 2, 1, 1200.00, 1200.00, 600.00, 600.00);
    -- Dica de teste: mande X-Visitor-ID: visitor-demo-0001 num GET no carrinho da matriz e
    -- confira se ele aparece — depois compare com o comportamento real do front (que hoje
    -- não busca esse carrinho porque exige uma revendedora ativa).

-- Pedido 6: fiado ANTIGO da Patrícia com a parcela CANCELADA (renegociação) — testa que
-- o job de vencidas NÃO reativa uma parcela cancelada e que ela não pode ser paga.
INSERT INTO pedido (id, cliente_id, origem, status, usuario_id, valor_recebido, troco, total_cobrado, valor_entrada, valor_devido,
                     revendedor_id, metodo_pagamento, parcelas, data_criacao, data_atualizacao, total, login_operador, comissao_revendedor, total_lucro) VALUES
    -- valor_devido=0 porque a única parcela foi cancelada/perdoada (renegociação) — coerente
    -- com o saldo_devedor=0 da Patrícia.
    (6, 3, 'PDV', 'PAGO', NULL, 0.00, 0.00, 150.00, 0.00, 0.00, 'a1111111-1111-1111-1111-111111111111', 'fiado', 1,
     NOW() - INTERVAL '90 days', NOW() - INTERVAL '90 days', 150.00, 'maria.silva', 45.00, 90.00);

INSERT INTO item_pedido (id, pedido_id, produto_id, quantidade, subtotal, preco_unitario, custo_unitario, lucro) VALUES
    (7, 6, 6, 1, 150.00, 150.00, 60.00, 90.00);

INSERT INTO parcelas (id, pedido_id, numero_parcela, valor, data_vencimento, data_pagamento, status) VALUES
    (4, 6, 1, 150.00, (CURRENT_DATE - INTERVAL '80 days')::date, NULL, 'CANCELADA');
    -- Dica de teste: rode ParcelaService.verificarParcelasVencidas e confira que esta
    -- parcela CONTINUA CANCELADA (não vira ATRASADA). Depois tente pagar (POST
    -- /clientes/3/pagamentos com parcelaId=4) e confira que é rejeitada.

-- =====================================================================================
-- 6. BANNERS — testa listagem pública (só ativos) vs listagem admin (todos).
-- =====================================================================================

INSERT INTO banners (id, titulo, object_name, link_acao, ordem, ativo) VALUES
    (1, 'Coleção Verão',    'seed/banner-verao.jpg',  '/colecao-verao',  1, true),
    (2, 'Dia das Mães',     'seed/banner-maes.jpg',   '/dia-das-maes',   2, true),
    (3, 'Campanha Antiga',  'seed/banner-antigo.jpg', '/promo-antiga',   3, false);
    -- object_name aqui é fictício (não existe de verdade no MinIO) — sirva só pra testar
    -- a listagem/CRUD; pra ver a imagem de verdade, suba um arquivo real via /api/arquivos/upload
    -- e troque o object_name pelo que a API devolver.

-- =====================================================================================
-- 7. LEADS — funil de marketing: um pendente, um já convertido em cliente, um da matriz.
-- =====================================================================================

INSERT INTO lead (id, nome, whatsapp, email, visitor_id, usuario_id, cliente_id, ativo, comprou, cupom_gerado, data_cadastro, revendedor_id) VALUES
    (1, 'Beatriz Alves', '11999991111', NULL, 'visitor-lead-01', NULL, NULL, true, false, NULL, NOW() - INTERVAL '3 days', 'a1111111-1111-1111-1111-111111111111'),
    (2, 'Fernanda Lima', '11999990002', 'fernanda.lima@teste.com', 'visitor-lead-02', 'c2222222-0000-0000-0000-000000000002', 2, true, true, 'BEMVINDA10', NOW() - INTERVAL '65 days', 'a1111111-1111-1111-1111-111111111111'),
    (3, 'Juliana Prado', '11999993333', NULL, 'visitor-lead-03', NULL, NULL, true, false, NULL, NOW() - INTERVAL '1 day', NULL);

-- =====================================================================================
-- 8. RESSINCRONIZA AS SEQUENCES (essencial: sem isso, o próximo INSERT feito pela
--    aplicação pode tentar reusar um ID que você acabou de inserir manualmente e
--    quebrar com erro de chave duplicada).
-- =====================================================================================

SELECT setval(pg_get_serial_sequence('categoria', 'id'),          (SELECT COALESCE(MAX(id), 1) FROM categoria));
SELECT setval(pg_get_serial_sequence('subcategoria', 'id'),       (SELECT COALESCE(MAX(id), 1) FROM subcategoria));
SELECT setval(pg_get_serial_sequence('produto', 'id'),            (SELECT COALESCE(MAX(id), 1) FROM produto));
SELECT setval(pg_get_serial_sequence('estoque_revendedor', 'id'), (SELECT COALESCE(MAX(id), 1) FROM estoque_revendedor));
SELECT setval(pg_get_serial_sequence('clientes', 'id'),           (SELECT COALESCE(MAX(id), 1) FROM clientes));
SELECT setval(pg_get_serial_sequence('pedido', 'id'),             (SELECT COALESCE(MAX(id), 1) FROM pedido));
SELECT setval(pg_get_serial_sequence('item_pedido', 'id'),        (SELECT COALESCE(MAX(id), 1) FROM item_pedido));
SELECT setval(pg_get_serial_sequence('parcelas', 'id'),           (SELECT COALESCE(MAX(id), 1) FROM parcelas));
SELECT setval(pg_get_serial_sequence('pagamentos', 'id'),         (SELECT COALESCE(MAX(id), 1) FROM pagamentos));
SELECT setval(pg_get_serial_sequence('historico_cobranca', 'id'), (SELECT COALESCE(MAX(id), 1) FROM historico_cobranca));
SELECT setval(pg_get_serial_sequence('banners', 'id'),            (SELECT COALESCE(MAX(id), 1) FROM banners));
SELECT setval(pg_get_serial_sequence('lead', 'id'),               (SELECT COALESCE(MAX(id), 1) FROM lead));

COMMIT;

-- =====================================================================================
-- ROTEIRO RÁPIDO DE TESTES (o que cada cenário acima permite validar)
-- =====================================================================================
-- • Estoque insuficiente na maleta:        vender produto 2 pela Maria no PDV (ela não tem).
-- • Comissão da revendedora:                GET /revendedores/{id-maria}/acerto?mes=X&ano=Y.
-- • IDOR bloqueado (posse de cliente):      logada como Maria, tentar acessar cliente 4 (da Ana).
-- • IDOR bloqueado (acerto de outra):       logada como Maria, tentar ver o acerto da Ana.
-- • Cooldown de cobrança (24h):             tentar cobrar a Fernanda (cliente 2) de novo agora.
-- • Parcela vencida virando ATRASADA:       rodar o job noturno / ParcelaService, checar parcela 2.
-- • Parcela CANCELADA não reativa/paga:     mesmo job, checar que a parcela 4 continua CANCELADA;
--                                            tentar pagar a parcela 4 e ver a rejeição.
-- • Rateio de parcelas sem sobra de centavo: some as 3 parcelas do pedido 2 e confira que bate
--                                            exatamente com o saldo devedor (570,00).
-- • Gap do checkout online (comissão zero): consultar o pedido 4 e ver comissao_revendedor=0
--                                            mesmo tendo revendedor_id preenchido.
-- • Carrinho da matriz não recarrega:       usar o front com X-Visitor-ID=visitor-demo-0001 na
--                                            loja matriz e comparar com uma consulta direta à API.
-- • Banners público vs admin:               GET /banners/ativos (só ids 1 e 2) vs GET /banners
--                                            (os 3, exige OPERADOR/ADMIN).
-- =====================================================================================
