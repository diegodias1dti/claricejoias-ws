package br.com.claricejoias_ws.model;

import br.com.claricejoias_ws.enums.TipoInstancia;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
public class WhatsappInstance {

    @Id
    private String usuarioId;

    @Column(unique = true, nullable = false)
    private String instanceName;

    // Segredo da Evolution API para esta instância — nunca deve sair em uma resposta JSON.
    @JsonIgnore
    private String uniqueToken;

    @Enumerated(EnumType.STRING) // Recomendado adicionar se TipoInstancia for um enum
    private TipoInstancia tipoInstancia;

    // Lado inverso do OneToOne com Revendedor.whatsappInstance. Sem @JsonIgnore aqui, serializar
    // um Revendedor cru (ex: RevendedorController.listarTodos/buscarPorId) entra em loop infinito:
    // Revendedor -> whatsappInstance -> revendedor -> whatsappInstance -> ... até estourar o
    // limite de profundidade do Jackson (era o erro "Document nesting depth exceeds maximum").
    @JsonIgnore
    @OneToOne
    @JoinColumn(name = "revendedor_id", unique = true) // referencedColumnName removido (usa o default)
    private Revendedor revendedor;

}