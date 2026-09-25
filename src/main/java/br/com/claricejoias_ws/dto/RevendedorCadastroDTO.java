package br.com.claricejoias_ws.dto;

import lombok.Data;
import java.math.BigDecimal;

@Data
public class RevendedorCadastroDTO {
    private String nome;
    private String email;
    private String senha;
    private String slug;
    private String whatsappContato;
    // Opcional: se não vier, mantém o default definido na entidade Revendedor (30.00)
    private BigDecimal percentualComissao;
}
