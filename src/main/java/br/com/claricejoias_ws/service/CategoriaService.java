package br.com.claricejoias_ws.service;

import br.com.claricejoias_ws.dto.CategoriaDTO;
import br.com.claricejoias_ws.dto.ProdutoCatalogoDTO;
import br.com.claricejoias_ws.dto.ProdutoDTO;
import br.com.claricejoias_ws.dto.SubcategoriaDTO;
import br.com.claricejoias_ws.model.Categoria;
import br.com.claricejoias_ws.model.Subcategoria;
import br.com.claricejoias_ws.repository.CategoriaRepository;
import br.com.claricejoias_ws.repository.SubCategoriaRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.modelmapper.ModelMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CategoriaService {

    private final CategoriaRepository repository;
    private final SubCategoriaRepository subCategoriaRepository;
    private final ModelMapper modelMapper;
    private final AutenticacaoService autenticacaoService;




        public List<CategoriaDTO> listarTodas() {
            return repository.findAll().stream()
                    // Chamamos um método próprio em vez do modelMapper direto
                    .map(this::converterCategoriaParaDTO)
                    .collect(Collectors.toList());
        }


    private CategoriaDTO converterCategoriaParaDTO(Categoria categoria) {
        CategoriaDTO dto = new CategoriaDTO();
        dto.setId(categoria.getId());
        dto.setNome(categoria.getNome());
        // (Se houver outros campos simples na categoria, adicione aqui)

        // Mapeamento à prova de balas para o Set de Subcategorias
        if (categoria.getSubcategorias() != null) {
            dto.setSubcategorias(categoria.getSubcategorias().stream()
                    .map(sub -> {
                        SubcategoriaDTO subDto = new SubcategoriaDTO();
                        subDto.setId(sub.getId());
                        subDto.setNome(sub.getNome());

                        // Se o seu SubcategoriaDTO tiver a lista de produtos, mapeie aqui:
                        if (sub.getItens() != null) {
                            subDto.setItens(sub.getItens().stream()
                                    // Como a joia não tem mais listas complexas dentro dela, o modelMapper puro funciona bem aqui!
                                    .map(prod -> modelMapper.map(prod, ProdutoDTO.class))
                                    .collect(Collectors.toCollection(LinkedHashSet::new)));
                        }

                        return subDto;
                    })
                    .collect(Collectors.toCollection(LinkedHashSet::new)));
        }

        return dto;
    }


    // Importe sua classe/DTO de Subcategoria
    public List<Subcategoria> listarSubcategoriasPorCategoriaId(Long categoriaId) {
        // Verifica se a categoria existe para evitar erro
        if (!repository.existsById(categoriaId)) {
            throw new RuntimeException("Categoria não encontrada com o ID: " + categoriaId);
        }

        return subCategoriaRepository.findByCategoriaId(categoriaId);
    }

    public Optional<Categoria> buscarPorId(Long id) {
        return repository.findById(id);
    }

    @Transactional
    public Categoria salvar(Categoria categoria) {

        if (categoria.getSubcategorias() != null) {
            for (Subcategoria sub : categoria.getSubcategorias()) {
                sub.setCategoria(categoria);
            }
        }
        return repository.save(categoria);
    }

    @Transactional
    public Categoria atualizar(Long id, Categoria novosDados) {
        Categoria categoriaExistente = repository.findById(id)
                .orElseThrow(() -> new RuntimeException("Categoria não encontrada com o ID: " + id));

        // NUNCA sobrescrever "subcategorias" aqui: como o relacionamento tem
        // orphanRemoval=true, um cliente que só manda {nome} (como o modal de edição do
        // front-end faz) chega com a coleção vazia por padrão — e "vazia" faz o Hibernate
        // apagar de verdade todas as subcategorias (e, em cascata, desativar os produtos
        // delas). Esse endpoint só atualiza o nome; edição de subcategorias tem que ser
        // um fluxo próprio e explícito, não um efeito colateral de renomear a categoria.
        categoriaExistente.setNome(novosDados.getNome());
        categoriaExistente.setLoginUsuario(autenticacaoService.getUsername());

        return repository.save(categoriaExistente);
    }

    @Transactional
    public void deletar(Long id) {
        if (!repository.existsById(id)) {
            throw new RuntimeException("Não é possível deletar: Categoria inexistente.");
        }
        repository.deleteById(id);
    }

    // ==========================================================
    // GESTÃO DE SUBCATEGORIAS (ações próprias e explícitas — nunca mais um efeito
    // colateral do PUT de categoria, que foi exatamente o bug anterior)
    // ==========================================================

    @Transactional
    public Subcategoria criarSubcategoria(Long categoriaId, String nome) {
        Categoria categoria = repository.findById(categoriaId)
                .orElseThrow(() -> new RuntimeException("Categoria não encontrada com o ID: " + categoriaId));

        Subcategoria nova = new Subcategoria();
        nova.setNome(nome);
        nova.setCategoria(categoria);
        return subCategoriaRepository.save(nova);
    }

    @Transactional
    public Subcategoria atualizarSubcategoria(Long id, String nome) {
        Subcategoria existente = subCategoriaRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Subcategoria não encontrada com o ID: " + id));
        existente.setNome(nome);
        return subCategoriaRepository.save(existente);
    }

    @Transactional
    public void deletarSubcategoria(Long id) {
        if (!subCategoriaRepository.existsById(id)) {
            throw new RuntimeException("Não é possível deletar: Subcategoria inexistente.");
        }
        // Soft-delete (via @SQLDelete) — cascateia pros produtos dela, também soft-delete.
        subCategoriaRepository.deleteById(id);
    }

    // Adicione no seu CategoriaService.java
    public List<CategoriaDTO> listarVitrineRevendedor(String slug) {
        // Busca as categorias cruzadas com a maleta do revendedor
        List<Categoria> categoriasRevendedor = repository.findVitrineDoRevendedor(slug);

        // Converte as entidades para DTO (Use a mesma lógica/mapper que você já usa no listarTodas)
        return categoriasRevendedor.stream()
                .map(categoria -> modelMapper.map(categoria, CategoriaDTO.class))
                .collect(Collectors.toList());
    }
}
