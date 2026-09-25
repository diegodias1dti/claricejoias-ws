package br.com.claricejoias_ws.controller;

import br.com.claricejoias_ws.service.CategoriaService;
import br.com.claricejoias_ws.dto.CategoriaDTO;
import br.com.claricejoias_ws.dto.SubcategoriaRequestDTO;
import br.com.claricejoias_ws.model.Categoria;
import br.com.claricejoias_ws.model.Subcategoria;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/categorias")
@Tag(name = "Categorias", description = "Endpoints para gerenciamento das categorias de joias e acessórios")
public class CategoriaController {

    @Autowired
    private CategoriaService service;

    // =========================================================================================
    // ENDPOINT ATUALIZADO: Agora aceita o 'slug' como parâmetro opcional na URL
    // =========================================================================================
    @Operation(summary = "Listar todas as categorias ou vitrine", description = "Retorna o catálogo da Matriz. Se o slug for informado, retorna a maleta exclusiva do revendedor.")
    @GetMapping
    public List<CategoriaDTO> listar(@RequestParam(required = false) String slug) {
        if (slug != null && !slug.trim().isEmpty()) {
            // Se veio /api/categorias?slug=karolbarcelar, filtra na maleta
            return service.listarVitrineRevendedor(slug);
        }
        // Se veio /api/categorias normal, traz a loja matriz
        return service.listarTodas();
    }

    @Operation(summary = "Listar subcategorias de uma categoria", description = "Retorna a lista de subcategorias vinculadas a uma categoria específica através do seu ID")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Lista de subcategorias retornada com sucesso"),
            @ApiResponse(responseCode = "404", description = "Categoria não encontrada")
    })
    @GetMapping("/{id}/subcategorias")
    public ResponseEntity<List<Subcategoria>> listarSubcategoriasPorCategoria(
            @Parameter(description = "ID da categoria pai") @PathVariable Long id) {
        try {
            // O tipo de retorno na lista dependerá do que você usa no seu Service (Subcategoria ou SubcategoriaDTO)
            return ResponseEntity.ok(service.listarSubcategoriasPorCategoriaId(id));
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @Operation(summary = "Cadastrar nova categoria", description = "Cria uma nova categoria no sistema")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Categoria cadastrada com sucesso"),
            @ApiResponse(responseCode = "400", description = "Dados inválidos fornecidos")
    })
    @PostMapping
    public ResponseEntity<Categoria> cadastrar(@RequestBody Categoria categoria) {
        return ResponseEntity.ok(service.salvar(categoria));
    }

    @Operation(summary = "Atualizar categoria", description = "Atualiza o nome ou dados de uma categoria existente via ID")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Categoria atualizada com sucesso"),
            @ApiResponse(responseCode = "404", description = "ID da categoria não encontrado")
    })
    @PutMapping("/{id}")
    public ResponseEntity<Categoria> atualizar(
            @Parameter(description = "ID da categoria a ser atualizada") @PathVariable Long id,
            @RequestBody Categoria categoria) {
        try {
            return ResponseEntity.ok(service.atualizar(id, categoria));
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @Operation(summary = "Criar subcategoria", description = "Cria uma nova subcategoria vinculada a uma categoria existente")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "201", description = "Subcategoria criada com sucesso"),
            @ApiResponse(responseCode = "404", description = "Categoria pai não encontrada")
    })
    @PostMapping("/{id}/subcategorias")
    public ResponseEntity<Subcategoria> criarSubcategoria(
            @Parameter(description = "ID da categoria pai") @PathVariable Long id,
            @RequestBody SubcategoriaRequestDTO dto) {
        try {
            return ResponseEntity.status(HttpStatus.CREATED).body(service.criarSubcategoria(id, dto.getNome()));
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @Operation(summary = "Deletar categoria", description = "Remove uma categoria permanentemente do sistema")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "204", description = "Categoria removida com sucesso"),
            @ApiResponse(responseCode = "404", description = "ID da categoria não encontrado")
    })
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deletar(
            @Parameter(description = "ID da categoria a ser removida") @PathVariable Long id) {
        try {
            service.deletar(id);
            return ResponseEntity.noContent().build();
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        }
    }
}