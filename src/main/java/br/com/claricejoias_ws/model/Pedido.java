package br.com.claricejoias_ws.model;

import br.com.claricejoias_ws.enums.OrigemPedido;
import br.com.claricejoias_ws.enums.StatusPedido;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@Entity
public class Pedido {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "cliente_id")
    private Cliente cliente; // Se o cliente for nulo, pode ser uma venda anônima de balcão no PDV

    @Enumerated(EnumType.STRING)
    private OrigemPedido origem;

    @Enumerated(EnumType.STRING)
    private StatusPedido status;

    private String usuarioId;
    private String visitorId;

    private String cupomDesconto;
    private BigDecimal valorDesconto;

    @ManyToOne
    @JoinColumn(name = "lead_id")
    private Lead lead; // Fica NULO se for uma venda direta no PDV


    @Column(name = "valor_recebido")
    private BigDecimal valorRecebido;

    private BigDecimal troco;

    private BigDecimal totalCobrado;

    // CORREÇÃO 1: Trocado de Double para BigDecimal
    @Column(name = "valor_entrada")
    private BigDecimal valorEntrada = BigDecimal.ZERO;

    @Column(name = "valor_devido")
    private BigDecimal valorDevido = BigDecimal.ZERO;

    @ManyToOne
    @JoinColumn(name = "revendedor_id")
    private Revendedor revendedor;


    @Column(name = "metodo_pagamento", nullable = false)
    private String metodoPagamento; // pix, cartao, especie, fiado

    private Integer parcelas;

    @OneToMany(mappedBy = "pedido", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ItemPedido> itens = new ArrayList<>();

    private LocalDateTime dataCriacao;
    private LocalDateTime dataAtualizacao;
    private BigDecimal total;
    private String loginOperador;

    @OneToMany(mappedBy = "pedido", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Parcela> parcelasDetalhadas = new ArrayList<>();

    @OneToMany(mappedBy = "pedido", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Pagamento> pagamentos = new ArrayList<>();

    @Column(name = "comissao_revendedor", precision = 10, scale = 2)
    private BigDecimal comissaoRevendedor = BigDecimal.ZERO;


    @Column(name = "total_lucro", precision = 10, scale = 2)
    private BigDecimal totalLucro = BigDecimal.ZERO;

    // Correlaciona esse pedido com o pagamento no Mercado Pago (Checkout Pro).
    // preferenceId é gerado na hora de criar o link de pagamento; paymentId só chega
    // depois, via webhook, quando o cliente efetivamente paga.
    @Column(name = "mercado_pago_preference_id")
    private String mercadoPagoPreferenceId;

    @Column(name = "mercado_pago_payment_id")
    private String mercadoPagoPaymentId;

    public void addItem(ItemPedido item) {
        itens.add(item);
        item.setPedido(this);
        this.calcularTotal(); // Atualiza o carrinho imediatamente ao adicionar um item
    }

    @PrePersist
    public void calcularValores() {
        // 1. Garante que o total está calculado com base nos itens
        this.calcularTotal();

        // 2. Calcula regras de Fiado e valores devidos
        if ("fiado".equalsIgnoreCase(this.metodoPagamento)) {
            BigDecimal entrada = (this.valorEntrada != null) ? this.valorEntrada : BigDecimal.ZERO;
            BigDecimal totalVenda = (this.total != null) ? this.total : BigDecimal.ZERO;

            this.valorDevido = totalVenda.subtract(entrada);
        } else {
            this.valorDevido = BigDecimal.ZERO;
        }
    }

    // =======================================================
    // MÉTODO PARA CALCULAR O TOTAL DO CARRINHO/PEDIDO
    // =======================================================
    public void calcularTotal() {
        BigDecimal soma = BigDecimal.ZERO;

        if (this.itens != null && !this.itens.isEmpty()) {
            for (ItemPedido item : this.itens) {
                // Supondo que ItemPedido tenha precoUnitario (BigDecimal) e quantidade (Integer/int)
                BigDecimal quantidade = BigDecimal.valueOf(item.getQuantidade());
                BigDecimal subtotalItem = item.getPrecoUnitario().multiply(quantidade);

                soma = soma.add(subtotalItem);
            }
        }

        this.total = soma;
        this.totalCobrado = soma; // Se no futuro você tiver "Desconto" ou "Frete", a matemática entra aqui!
    }
}