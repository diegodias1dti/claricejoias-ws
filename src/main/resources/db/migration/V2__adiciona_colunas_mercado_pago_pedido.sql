-- Correlaciona um pedido com o pagamento no Mercado Pago (Checkout Pro).
-- preference_id é gerado ao criar o link de pagamento; payment_id só chega depois,
-- via webhook, quando o cliente efetivamente conclui o pagamento.
ALTER TABLE pedido ADD COLUMN mercado_pago_preference_id VARCHAR(255);
ALTER TABLE pedido ADD COLUMN mercado_pago_payment_id VARCHAR(255);
