package br.com.claricejoias_ws.controller;

import br.com.claricejoias_ws.dto.ProdutoDTO;
import br.com.claricejoias_ws.model.Produto;
import br.com.claricejoias_ws.service.ProdutoService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/produtos")
@Slf4j
@Tag(name = "Produtos", description = "Endpoints para gerenciamento do catálogo de joias")
public class ProdutoController {

    @Autowired
    private ProdutoService produtoService;

    @GetMapping
    public ResponseEntity<List<ProdutoDTO>> listarTodos() {
        return ResponseEntity.ok(produtoService.listarTodos());
    }

    @Operation(summary = "Buscar produto por ID interno")
    @GetMapping("/{id}")
    public ResponseEntity<Produto> buscarPorId(@PathVariable Long id) {
        return produtoService.buscarPorId(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/rascunhos")
    public ResponseEntity<List<ProdutoDTO>> listarRascunhosPendentes() {
        return ResponseEntity.ok(produtoService.listarRascunhos());
    }

    @PostMapping("/importar-xml")
    public ResponseEntity<Void> importarXmlNfe(@RequestParam("file") MultipartFile file) {
        try {
            produtoService.processarXmlNfe(file);
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            log.error("Erro ao importar XML de NFe", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    // --- NOVO ENDPOINT: UPLOAD EM MASSA DE IMAGENS ---
    @Operation(summary = "Vincular imagens em lote aos produtos via nome do arquivo")
    @PostMapping(value = "/imagens/upload-massa", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Void> uploadImagensEmMassa(@RequestParam("imagens") List<MultipartFile> imagens) {
        try {
            // Verifica se a lista não está vazia para evitar processamento desnecessário
            if (imagens == null || imagens.isEmpty()) {
                return ResponseEntity.badRequest().build();
            }

            // Chama o método que criamos anteriormente (certifique-se de que ele esteja no ProdutoService)
            produtoService.processarImagensEmMassa(imagens);

            return ResponseEntity.ok().build();
        } catch (Exception e) {
            log.error("Erro ao vincular imagens em lote aos produtos", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @Operation(summary = "Buscar produto pelo Código de Barras/SKU")
    @GetMapping("/codigo/{codigo}")
    public ResponseEntity<ProdutoDTO> buscarPorCodigo(@PathVariable String codigo) {
        return produtoService.buscarPorCodigo(codigo)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @Operation(summary = "Cadastrar novo produto com Múltiplas Imagens")
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Produto> criar(
            @RequestPart("produto") Produto produto,
            @RequestPart(value = "files", required = false) List<MultipartFile> files) {
        try {
            Produto novoProduto = produtoService.salvar(produto, files);
            return ResponseEntity.status(HttpStatus.CREATED).body(novoProduto);
        } catch (Exception e) {
            log.error("Erro ao cadastrar produto", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @Operation(summary = "Atualizar produto com Múltiplas Imagens")
    @PutMapping(value = "/{id}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Produto> atualizar(
            @PathVariable Long id,
            @RequestPart("produto") Produto produto,
            @RequestPart(value = "files", required = false) List<MultipartFile> files) {
        try {
            Produto produtoAtualizado = produtoService.atualizar(id, produto, files);
            return ResponseEntity.ok(produtoAtualizado);
        } catch (Exception e) {
            log.error("Erro ao atualizar produto id={}", id, e);
            return ResponseEntity.notFound().build();
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deletar(@PathVariable Long id) {
        try {
            produtoService.deletar(id);
            return ResponseEntity.noContent().build();
        } catch (Exception e) {
            log.error("Erro ao deletar produto id={}", id, e);
            return ResponseEntity.notFound().build();
        }
    }
}