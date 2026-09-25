package br.com.claricejoias_ws.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Data
public class PedidoRequestDTO {
    private List<ItemVendaRequestDTO> itens;
    private BigDecimal total;
    private PagamentoRequestDTO pagamento;
    private ClienteRequestDTO cliente;

    // Opcional: para lançar uma venda de uma data passada (ex: venda feita no PDV físico
    // e cadastrada no sistema só depois). Se vier nulo, usa a data/hora atual como sempre.
    private LocalDate dataVenda;
}