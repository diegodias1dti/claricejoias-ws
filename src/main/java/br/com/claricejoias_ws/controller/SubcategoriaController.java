package br.com.claricejoias_ws.controller;

import br.com.claricejoias_ws.dto.SubcategoriaRequestDTO;
import br.com.claricejoias_ws.model.Subcategoria;
import br.com.claricejoias_ws.service.CategoriaService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/subcategorias")
@Tag(name = "Subcategorias", description = "Renomear e excluir subcategorias (criar é feito via /api/categorias/{id}/subcategorias)")
@RequiredArgsConstructor
public class SubcategoriaController {

    private final CategoriaService categoriaService;

    @Operation(summary = "Renomear subcategoria")
    @PutMapping("/{id}")
    public ResponseEntity<Subcategoria> atualizar(
            @Parameter(description = "ID da subcategoria") @PathVariable Long id,
            @RequestBody SubcategoriaRequestDTO dto) {
        try {
            return ResponseEntity.ok(categoriaService.atualizarSubcategoria(id, dto.getNome()));
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @Operation(summary = "Excluir subcategoria", description = "Soft-delete: também desativa os produtos vinculados a ela.")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deletar(
            @Parameter(description = "ID da subcategoria") @PathVariable Long id) {
        try {
            categoriaService.deletarSubcategoria(id);
            return ResponseEntity.noContent().build();
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        }
    }
}
