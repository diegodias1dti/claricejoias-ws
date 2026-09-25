package br.com.claricejoias_ws.controller;

import br.com.claricejoias_ws.dto.InstanceCreateRequest;
import br.com.claricejoias_ws.model.Revendedor;
import br.com.claricejoias_ws.service.AutenticacaoService;
import br.com.claricejoias_ws.service.EvolutionApiService;
import br.com.claricejoias_ws.service.RevendedorService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/whatsapp/instances")
public class EvolutionApiController {

    private final EvolutionApiService evolutionApiService;
    private final AutenticacaoService autenticacaoService;
    private final RevendedorService revendedorService;

    @PostMapping
    public ResponseEntity<String> create(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-Visitor-ID", required = false) String visitorId) {
        String usuarioId = jwt.getSubject();
        String username = jwt.getClaimAsString("preferred_username");

        // "Loja matriz"/global só pode ser criada por ADMIN de verdade (via role do Keycloak,
        // nunca por um parâmetro que o cliente controla). Quem não é ADMIN só pode criar a
        // própria instância se já existir um Revendedor vinculado ao seu ID do Keycloak —
        // isso barra clientes comuns de criar/derrubar a instância global.
        boolean isAdmin = autenticacaoService.temRole("ADMIN");
        if (!isAdmin && revendedorService.findById(usuarioId).isEmpty()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body("{\"message\": \"Apenas administradores ou revendedoras cadastradas podem criar uma instância de WhatsApp.\"}");
        }

        return evolutionApiService.createInstanceForUser(usuarioId, username, isAdmin);
    }

    // Criação pelo ADMIN, em nome de uma revendedora específica (não a instância de quem está logado).
    // Sem isso, um admin logado só conseguia criar a própria instância: a segunda revendedora
    // sempre batia no "Usuário já possui uma instância ativa", porque o usuarioId usado era
    // sempre o subject do admin, nunca o da revendedora alvo.
    @PostMapping("/revendedor/{revendedorId}")
    public ResponseEntity<String> createForRevendedor(
            @PathVariable String revendedorId,
            @RequestHeader(value = "X-Visitor-ID", required = false) String visitorId) {
        ResponseEntity<String> negado = exigirAdmin();
        if (negado != null) return negado;

        Revendedor revendedor = revendedorService.findById(revendedorId).orElse(null);
        if (revendedor == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body("{\"message\": \"Revendedor não encontrado.\"}");
        }

        return evolutionApiService.createInstanceForUser(revendedor.getId(), revendedor.getNome(), false);
    }

    @GetMapping("/my-instance/connect")
    public ResponseEntity<String> connectMyInstance(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-Visitor-ID", required = false) String visitorId) {
        String usuarioId = jwt.getSubject();
        return evolutionApiService.connectInstanceByUser(usuarioId);
    }

    @DeleteMapping("/my-instance")
    public ResponseEntity<String> deleteMyInstance(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-Visitor-ID", required = false) String visitorId) {
        String usuarioId = jwt.getSubject();
        return evolutionApiService.deleteInstanceByUser(usuarioId);
    }

    @DeleteMapping("/my-instance/logout")
    public ResponseEntity<String> logoutMyInstance(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-Visitor-ID", required = false) String visitorId) {
        String usuarioId = jwt.getSubject();
        return evolutionApiService.logoutInstanceByUser(usuarioId);
    }

    // Listagem self-scoped: qualquer usuário autenticado vê só a própria instância
    // (é a mesma coisa que a tela "Status da Conexão" da revendedora usa).
    @GetMapping(value = "/my-instance", produces = "application/json")
    public ResponseEntity<String> listarMinhaInstancia(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-Visitor-ID", required = false) String visitorId) {
        String usuarioId = jwt.getSubject();
        return evolutionApiService.fetchInstances(usuarioId);
    }

    // Restrito a ADMIN em SecurityConfigurations (hasRole("ADMIN") em GET /api/whatsapp/instances).
    // Ao contrário de /my-instance, aqui NÃO filtra por usuário: devolve todas as instâncias
    // para o admin conseguir gerenciar a de qualquer revendedora.
    @GetMapping(produces = "application/json")
    public ResponseEntity<String> listarTodas(
            @RequestHeader(value = "X-Visitor-ID", required = false) String visitorId) {
        return evolutionApiService.fetchAllInstancesForAdmin();
    }

    // ------------------------------------------------------------------
    // Ações do ADMIN sobre a instância de UMA revendedora específica
    // (usadas pela tabela "Instâncias Existentes" do painel administrativo)
    // ------------------------------------------------------------------

    @GetMapping("/{instanceName}/connect")
    public ResponseEntity<String> connectInstance(
            @PathVariable String instanceName,
            @RequestHeader(value = "X-Visitor-ID", required = false) String visitorId) {
        ResponseEntity<String> negado = exigirAdmin();
        if (negado != null) return negado;
        return evolutionApiService.connectInstance(instanceName);
    }

    @DeleteMapping("/{instanceName}")
    public ResponseEntity<String> deleteInstance(
            @PathVariable String instanceName,
            @RequestHeader(value = "X-Visitor-ID", required = false) String visitorId) {
        ResponseEntity<String> negado = exigirAdmin();
        if (negado != null) return negado;
        return evolutionApiService.deleteInstance(instanceName);
    }

    @DeleteMapping("/{instanceName}/logout")
    public ResponseEntity<String> logoutInstance(
            @PathVariable String instanceName,
            @RequestHeader(value = "X-Visitor-ID", required = false) String visitorId) {
        ResponseEntity<String> negado = exigirAdmin();
        if (negado != null) return negado;
        return evolutionApiService.logoutInstance(instanceName);
    }

    private ResponseEntity<String> exigirAdmin() {
        if (!autenticacaoService.temRole("ADMIN")) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body("{\"message\": \"Apenas administradores podem gerenciar a instância de outra pessoa.\"}");
        }
        return null;
    }
}