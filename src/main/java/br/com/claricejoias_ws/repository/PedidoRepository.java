package br.com.claricejoias_ws.repository;

import br.com.claricejoias_ws.enums.StatusPedido;
import br.com.claricejoias_ws.model.Pedido;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface PedidoRepository extends JpaRepository<Pedido, Long> {

    // Busca os pedidos do usuário logado (Mais recentes primeiro)
    List<Pedido> findByUsuarioIdOrderByIdDesc(String usuarioId);

    // Busca os pedidos do visitante anônimo (Mais recentes primeiro)
    List<Pedido> findByVisitorIdOrderByIdDesc(String visitorId);

    // ATENÇÃO: compara p.usuarioId (campo do próprio Pedido, setado pelo CarrinhoService ao
    // criar/mexer no carrinho) — NUNCA p.cliente.usuarioId. O carrinho não tem "cliente"
    // vinculado até o checkout terminar, e navegar por p.cliente em JPQL gera um INNER JOIN
    // implícito que descarta toda linha com cliente_id nulo, ou seja, todo carrinho existente.
    @Query("SELECT p FROM Pedido p WHERE (p.visitorId = :visitorId OR p.usuarioId = :usuarioId) AND p.status = 'CARRINHO'")
    Optional<Pedido> buscarCarrinhoAtivo(@Param("visitorId") String visitorId, @Param("usuarioId") String usuarioId);

    @Query("SELECT p FROM Pedido p WHERE " +
            "(CAST(:revendedorId AS text) IS NULL OR p.revendedor.id = :revendedorId) AND " +
            "(CAST(:loginOperador AS text) IS NULL OR p.loginOperador = :loginOperador) AND " +
            "(CAST(:metodoPagamento AS text) IS NULL OR p.metodoPagamento = :metodoPagamento) AND " +
            "(CAST(:inicioDia AS timestamp) IS NULL OR p.dataCriacao >= :inicioDia) AND " +
            "(CAST(:fimDia AS timestamp) IS NULL OR p.dataCriacao <= :fimDia)")
    Page<Pedido> findComFiltros(
            @Param("revendedorId") String revendedorId,
            @Param("loginOperador") String loginOperador,
            @Param("metodoPagamento") String metodoPagamento,
            @Param("inicioDia") LocalDateTime inicioDia,
            @Param("fimDia") LocalDateTime fimDia,
            Pageable pageable
    );


    // Busca os pedidos de um revendedor específico, em um intervalo de datas e que estejam PAGOS
    List<Pedido> findByRevendedorIdAndDataCriacaoBetweenAndStatus(
            String revendedorId,
            LocalDateTime dataInicio,
            LocalDateTime dataFim,
            StatusPedido status
    );

    Optional<Pedido> findFirstByUsuarioIdAndStatusOrderByIdDesc(String usuarioId, StatusPedido statusPedido);

    Optional<Pedido> findFirstByVisitorIdAndStatusOrderByIdDesc(String visitorId, StatusPedido statusPedido);

    // Busca o carrinho da filial (onde o revendedor tem um ID específico)
    Optional<Pedido> findFirstByUsuarioIdAndStatusAndRevendedorIdOrderByIdDesc(
            String usuarioId,
            StatusPedido status,
            String revendedorId // Mude para Long ou UUID se o ID do seu revendedor não for String
    );

    // Busca o carrinho da Loja Matriz (onde a coluna do revendedor está NULA no banco)
    Optional<Pedido> findFirstByUsuarioIdAndStatusAndRevendedorIsNullOrderByIdDesc(
            String usuarioId,
            StatusPedido status
    );

    // Busca os pedidos de um usuário específico de forma paginada
    Page<Pedido> findByUsuarioId(String usuarioId, Pageable pageable);

    Optional<Pedido> findFirstByVisitorIdAndStatusAndRevendedorIsNullOrderByIdDesc(String visitorId, StatusPedido statusPedido);

    Optional<Pedido> findFirstByVisitorIdAndStatusAndRevendedorIdOrderByIdDesc(String visitorId, StatusPedido statusPedido, String revendedorId);

    Page<Pedido> findByUsuarioIdAndRevendedorIdOrderByIdDesc(String usuarioId, String revendedorId, Pageable pageable);

    Page<Pedido> findFirstByUsuarioIdAndRevendedorIsNullOrderByIdDesc(String usuarioId, Pageable pageable);
}