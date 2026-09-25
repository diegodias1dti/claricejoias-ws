package br.com.claricejoias_ws.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Data
@Entity
// Soft-delete, igual ao Produto: os produtos dessa subcategoria também são só
// desativados (nunca removidos de verdade, pra não quebrar o histórico de pedidos
// que os referenciam) — então um DELETE físico da subcategoria sempre bateria na
// FK deles. Ver Produto.java para o mesmo raciocínio.
@SQLDelete(sql = "UPDATE subcategoria SET ativo = false WHERE id = ?")
@SQLRestriction("ativo = true")
public class Subcategoria {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String nome;

    @Column(nullable = false)
    private boolean ativo = true;

    // @JsonIgnore quebra o ciclo Subcategoria -> Categoria -> subcategorias -> Subcategoria
    // -> ... que estourava "Document nesting depth exceeds the maximum allowed" ao devolver
    // uma Subcategoria crua (ex: POST /categorias/{id}/subcategorias, PUT /subcategorias/{id}).
    @JsonIgnore
    @ManyToOne
    private Categoria categoria;

    @OneToMany(mappedBy = "subcategoria", cascade = CascadeType.ALL, orphanRemoval = true)
    private Set<Produto> itens = new LinkedHashSet<>();
}
