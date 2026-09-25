package br.com.claricejoias_ws.controller;

import br.com.claricejoias_ws.dto.EstoqueRevendedorDTO;
import br.com.claricejoias_ws.dto.TransferenciaEstoqueDTO;
import br.com.claricejoias_ws.model.EstoqueRevendedor;
import br.com.claricejoias_ws.service.EstoqueRevendedorService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/estoque-revendedor")
@Slf4j
@Tag(name = "Estoque Revendedor", description = "Gerenciamento de maletas e distribuição de joias")
public class EstoqueRevendedorController {

    @Autowired
    private EstoqueRevendedorService service;

    @Operation(summary = "Distribuir produtos para maleta", description = "Transfere uma quantidade do estoque central para a maleta de um revendedor.")
    @PostMapping("/transferir")
    public ResponseEntity<?> transferirParaMaleta(@RequestBody TransferenciaEstoqueDTO dto) {
        try {
            service.transferirParaMaleta(dto);
            return ResponseEntity.ok("Transferência realizada com sucesso.");
        } catch (Exception e) {
            log.error("Erro ao transferir produtos para a maleta", e);
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    // =========================================================================
    // NOVO ENDPOINT: Devolução de não vendidos
    // =========================================================================
    @Operation(summary = "Devolver produtos não vendidos", description = "Retira itens da maleta do revendedor e os devolve para o estoque central.")
    @PostMapping("/devolver")
    public ResponseEntity<?> devolverParaEstoqueCentral(@RequestBody TransferenciaEstoqueDTO dto) {
        try {
            service.devolverParaEstoqueCentral(dto);
            return ResponseEntity.ok("Devolução dos itens não vendidos realizada com sucesso.");
        } catch (Exception e) {
            log.error("Erro ao devolver produtos para o estoque central", e);
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @Operation(summary = "Listar produtos da maleta", description = "Retorna todos os itens e quantidades que um revendedor possui atualmente.")
    @GetMapping("/maleta/{revendedorId}")
    public ResponseEntity<List<EstoqueRevendedorDTO>> listarMaleta(@PathVariable String revendedorId) {
        return ResponseEntity.ok(service.listarEstoquePorRevendedor(revendedorId));
    }
}