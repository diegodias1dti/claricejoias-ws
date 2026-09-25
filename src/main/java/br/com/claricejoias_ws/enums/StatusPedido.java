package br.com.claricejoias_ws.enums;

public enum StatusPedido {
    AGUARDANDO_WHATSAPP,
    CONCLUIDO,
    CANCELADO,
    CARRINHO,            // Lead montando o carrinho
    CARRINHO_ABANDONADO, // Passou X tempo e ele não comprou (ideal para marketing)
    PENDENTE_PAGAMENTO,  // Fechou pedido no site, aguardando confirmação do Mercado Pago
    AGUARDANDO_RETIRADA, // Cliente optou por combinar com a revendedora e retirar/pagar na loja
    PAGO,                // Venda concretizada
    ENVIADO,             // Produto a caminho (Ecommerce)
    ENTREGUE,



}
