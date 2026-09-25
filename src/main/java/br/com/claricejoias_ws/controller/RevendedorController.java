package br.com.claricejoias_ws.controller;

import br.com.claricejoias_ws.dto.AcertoRevendedorDTO;
import br.com.claricejoias_ws.dto.RevendedorCadastroDTO;
import br.com.claricejoias_ws.exceptions.RegraNegocioException;
import br.com.claricejoias_ws.model.Revendedor;
import br.com.claricejoias_ws.repository.RevendedorRepository;
import br.com.claricejoias_ws.service.AutenticacaoService;
import br.com.claricejoias_ws.service.FinanceiroRevendedorService;
import br.com.claricejoias_ws.service.RevendedorService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/revendedores")
@Tag(name = "Revendedores", description = "Gerenciamento de vínculos com usuários do Keycloak")
@RequiredArgsConstructor
public class RevendedorController {

    private final RevendedorRepository repository;
    private final FinanceiroRevendedorService financeiroRevendedorService;
    private final AutenticacaoService autenticacaoService;
    private final RevendedorService revendedorService;

    @Operation(summary = "Vincular novo revendedor", description = "Cria um registro local para um usuário já cadastrado no Keycloak utilizando seu UUID.")
    @PostMapping
    public ResponseEntity<Revendedor> vincularRevendedor(@RequestBody Revendedor revendedor) {
        // O ID aqui deve ser o UUID (Subject) que você copiou do painel do Keycloak
        Revendedor novo = repository.save(revendedor);
        return ResponseEntity.status(HttpStatus.CREATED).body(novo);
    }

    @Operation(summary = "Cadastrar revendedora (conta completa)", description = "Cria a conta da revendedora no Keycloak (login por e-mail/senha, com troca obrigatória no 1º acesso) e o vínculo local numa única chamada.")
    @PostMapping("/cadastrar")
    public ResponseEntity<Revendedor> cadastrarComConta(@RequestBody RevendedorCadastroDTO dto) {
        Revendedor novo = revendedorService.cadastrarComConta(dto);
        return ResponseEntity.status(HttpStatus.CREATED).body(novo);
    }

    @Operation(summary = "Listar todos os revendedores", description = "Retorna a lista de revendedores vinculados ao sistema.")
    @GetMapping
    public ResponseEntity<List<Revendedor>> listarTodos() {
        return ResponseEntity.ok(repository.findAll());
    }

    @Operation(summary = "Buscar revendedor por ID", description = "Busca os detalhes de um revendedor pelo seu ID do Keycloak.")
    @GetMapping("/{id}")
    public ResponseEntity<Revendedor> buscarPorId(@PathVariable String id) {
        return repository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @Operation(summary = "Remover vínculo de revendedor", description = "Remove o revendedor do sistema local (não exclui o usuário do Keycloak).")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deletar(@PathVariable String id) {
        if (!repository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }
        repository.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{revendedorId}/acerto")
    public ResponseEntity<AcertoRevendedorDTO> obterAcerto(
            @PathVariable String revendedorId,
            @RequestParam(required = false) Integer mes,
            @RequestParam(required = false) Integer ano,
            @AuthenticationPrincipal Jwt jwt) {

        if (jwt == null || (!revendedorId.equals(jwt.getSubject()) && !autenticacaoService.temRole("ADMIN"))) {
            throw new RegraNegocioException("Você não tem permissão para ver o acerto financeiro deste revendedor.");
        }

        // Se não mandar mês/ano na URL, pega o mês atual por padrão
        if (mes == null) mes = LocalDate.now().getMonthValue();
        if (ano == null) ano = LocalDate.now().getYear();

        AcertoRevendedorDTO acerto = financeiroRevendedorService.calcularAcertoMensal(revendedorId, mes, ano);

        return ResponseEntity.ok(acerto);
    }

    @Operation(summary = "Buscar revendedor por Slug", description = "Busca os detalhes de um revendedor pela sua URL amigável (slug).")
    @GetMapping("/slug/{slug}")
    public ResponseEntity<Revendedor> buscarPorSlug(@PathVariable String slug) {
        return repository.findBySlug(slug)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // =========================================================
    // NOVO ENDPOINT: BUSCAR PERFIL DA REVENDEDORA LOGADA
    // =========================================================
    @Operation(summary = "Buscar meu perfil", description = "Retorna os dados da revendedora logada baseado no token JWT.")
    @GetMapping("/meu-perfil")
    public ResponseEntity<Revendedor> getMeuPerfil(@AuthenticationPrincipal Jwt jwt) {
        if (jwt == null || jwt.getSubject() == null) {
            throw new RegraNegocioException("Usuário não autenticado.");
        }

        String usuarioId = jwt.getSubject(); // Pega o ID (UUID) do usuário do token Keycloak

        Revendedor revendedor = repository.findById(usuarioId)
                .orElseThrow(() -> new RegraNegocioException("Revendedor não encontrado para o usuário logado."));

        return ResponseEntity.ok(revendedor);
    }
}