package br.com.claricejoias_ws.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Getter
@Setter
@Entity
// Soft-delete, no mesmo espírito do Produto: uma categoria excluída não pode virar um
// DELETE físico porque as subcategorias dela (também soft-delete agora) ainda existem
// fisicamente enquanto tiverem produtos vinculados — um DELETE de verdade aqui bateria
// na mesma violação de FK.
@SQLDelete(sql = "UPDATE categoria SET ativo = false WHERE id = ?")
@SQLRestriction("ativo = true")
public class Categoria {


    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String nome;
    private String loginUsuario;

    @Column(nullable = false)
    private boolean ativo = true;

    @OneToMany(mappedBy = "categoria", cascade = CascadeType.ALL, orphanRemoval = true,fetch = FetchType.LAZY)
    private Set<Subcategoria> subcategorias = new LinkedHashSet<>();
}
