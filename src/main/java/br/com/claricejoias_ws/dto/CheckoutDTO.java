package br.com.claricejoias_ws.dto;

import lombok.Data;
import java.math.BigDecimal;

@Data
public class CheckoutDTO {
    // Dados do Cliente
    private String nome;
    private String whatsapp;
    private String email;

    // Se true e o cliente ainda não estiver logado, cria a conta dele no Keycloak
    // (mesmo padrão já usado no cadastro via Lead: e-mail whatsapp@claricejoias.com.br).
    private boolean criarConta;
    private String senha;

    // "ONLINE": gera link de pagamento no Mercado Pago (Pix/cartão) e redireciona.
    // "RETIRADA": reserva o pedido para a cliente combinar pagamento/retirada com a revendedora.
    private String tipoFinalizacao;

    // Dados do Pagamento (com valores padrão para compras via site/whatsapp)
    private String metodoPagamento = "whatsapp";
    private Integer parcelas = 1;
    private BigDecimal valorRecebido;
    private BigDecimal valorEntrada;
}