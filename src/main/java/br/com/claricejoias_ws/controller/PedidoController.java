package br.com.claricejoias_ws.controller;

import br.com.claricejoias_ws.dto.CheckoutDTO;
import br.com.claricejoias_ws.dto.PedidoDTO;
import br.com.claricejoias_ws.dto.PedidoRequestDTO;
import br.com.claricejoias_ws.model.Pedido;
import br.com.claricejoias_ws.service.AutenticacaoService;
import br.com.claricejoias_ws.service.PedidoService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/pedidos")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Pedidos", description = "Endpoints unificados para gerenciamento de Vendas (PDV) e E-commerce")
public class PedidoController {

    private final PedidoService pedidoService;
    private final AutenticacaoService autenticacaoService;

    @Operation(summary = "Registrar nova venda (PDV)", description = "Venda manual por Admin ou Revendedor.")
    @PostMapping("/pdv")
    public ResponseEntity<?> registrarPedidoPDV(@RequestBody PedidoRequestDTO dto, JwtAuthenticationToken token) {
        try {
            // Extrai o UUID (sub) do Keycloak
            String userId = token.getToken().getSubject();

            // Verifica se o usuário tem a role de ADMIN
            // Verifica se a autoridade é "ROLE_ADMIN" ou apenas "ADMIN"
            boolean isAdmin = token.getAuthorities().stream()
                    .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN") || a.getAuthority().equals("ADMIN"));

            Pedido pedidoSalvo = pedidoService.registrarPedidoPDV(dto, userId, isAdmin, autenticacaoService.getUsername());
            return ResponseEntity.status(HttpStatus.CREATED).body(pedidoSalvo);
        } catch (Exception e) {
            log.error("Erro ao registrar pedido PDV", e);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(e.getMessage());
        }
    }

    @Operation(summary = "Finalizar pedido online (Checkout)", description = "Transforma um carrinho ativo do e-commerce em um pedido finalizado. Devolve { tipo: 'RETIRADA', pedido } ou { tipo: 'ONLINE', redirectUrl } conforme o tipoFinalizacao escolhido.")
    @PostMapping("/checkout")
    public ResponseEntity<?> finalizarPedido(
            @RequestHeader(value = "X-Visitor-ID", required = false) String visitorId,
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody CheckoutDTO checkoutDTO) {

        try {
            String usuarioId = (jwt != null) ? jwt.getSubject() : null;
            Map<String, Object> resultado = pedidoService.realizarCheckoutOnline(visitorId, usuarioId, checkoutDTO);
            return ResponseEntity.ok(resultado);

        } catch (RuntimeException e) {
            log.error("Erro ao finalizar checkout online", e);
            return ResponseEntity.badRequest().body(Map.of("erro", e.getMessage()));
        }
    }

    @Operation(summary = "Confirmar retirada", description = "A revendedora (ou admin) confirma que o cliente apareceu, pagou e retirou o pedido combinado.")
    @PutMapping("/{id}/confirmar-retirada")
    public ResponseEntity<?> confirmarRetirada(@PathVariable Long id, @AuthenticationPrincipal Jwt jwt) {
        try {
            String usuarioId = jwt.getSubject();
            boolean isAdmin = verificarSeAdmin(jwt);
            Pedido pedido = pedidoService.confirmarRetirada(id, usuarioId, isAdmin);
            return ResponseEntity.ok(pedido);
        } catch (RuntimeException e) {
            log.error("Erro ao confirmar retirada do pedido {}", id, e);
            return ResponseEntity.badRequest().body(Map.of("erro", e.getMessage()));
        }
    }

    @Operation(summary = "Cancelar pedido pendente", description = "Cancela um pedido aguardando retirada ou pagamento (ex: cliente não apareceu) e devolve o estoque reservado.")
    @PutMapping("/{id}/cancelar")
    public ResponseEntity<?> cancelarPedido(@PathVariable Long id, @AuthenticationPrincipal Jwt jwt) {
        try {
            String usuarioId = jwt.getSubject();
            boolean isAdmin = verificarSeAdmin(jwt);
            Pedido pedido = pedidoService.cancelarPedidoPendente(id, usuarioId, isAdmin);
            return ResponseEntity.ok(pedido);
        } catch (RuntimeException e) {
            log.error("Erro ao cancelar pedido {}", id, e);
            return ResponseEntity.badRequest().body(Map.of("erro", e.getMessage()));
        }
    }

    @Operation(summary = "Listar pedidos", description = "Retorna o histórico de pedidos consolidados.")
    @GetMapping
    public ResponseEntity<Page<PedidoDTO>> listarPedidos(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) String loginOperador,
            @RequestParam(required = false) String metodoPagamento,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dataInicio,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dataFim,
            @PageableDefault(page = 0, size = 20, sort = "id", direction = Sort.Direction.DESC) Pageable pageable) {

        // 1. Extrai ID e Perfil do Token
        String userId = jwt.getSubject();
        boolean isAdmin = verificarSeAdmin(jwt); // Use aquele mesmo método auxiliar que criamos no ClienteController

        // 2. Passa para o Service
        return ResponseEntity.ok(pedidoService.listarPedidos(
                userId, isAdmin, loginOperador, metodoPagamento, dataInicio, dataFim, pageable
        ));
    }

    private boolean verificarSeAdmin(Jwt jwt) {
        // Estrutura padrão quando se usa Keycloak
        if (jwt.hasClaim("realm_access")) {
            Map<String, Object> realmAccess = jwt.getClaim("realm_access");
            if (realmAccess != null && realmAccess.containsKey("roles")) {
                List<String> roles = (List<String>) realmAccess.get("roles");
                // Verifique o nome exato da sua role (pode ser "admin", "ROLE_ADMIN", etc)
                return roles.contains("ROLE_ADMIN") || roles.contains("admin") || roles.contains("ADMIN");
            }
        }

        // Caso você mapeie as authorities de outra forma, pode verificar assim:
        // jwt.getClaimAsStringList("roles").contains("ROLE_ADMIN");

        return false;
    }

    @Operation(summary = "Listar meus pedidos", description = "Retorna o histórico de pedidos do cliente logado de forma paginada.")
    @GetMapping("/meus-pedidos")
    public ResponseEntity<Page<PedidoDTO>> listarMeusPedidos(
            @AuthenticationPrincipal Jwt jwt,
            @PageableDefault(page = 0, size = 10, sort = "id", direction = Sort.Direction.DESC)
            @RequestParam(required = false) String revendedorId, Pageable pageable) {

        String usuarioId = jwt.getSubject();

        Page<PedidoDTO> meusPedidos = pedidoService.listarMeusPedidos(usuarioId,revendedorId, pageable);
        return ResponseEntity.ok(meusPedidos);
    }
}