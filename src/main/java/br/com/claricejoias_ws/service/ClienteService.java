package br.com.claricejoias_ws.service;

import br.com.claricejoias_ws.dto.*;
import br.com.claricejoias_ws.enums.StatusParcela;
import br.com.claricejoias_ws.exceptions.RegraNegocioException;
import br.com.claricejoias_ws.model.*;
import br.com.claricejoias_ws.repository.*;
import lombok.RequiredArgsConstructor;
import org.hibernate.StaleObjectStateException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ClienteService {

    private final ClienteRepository clienteRepository;
    private final HistoricoCobrancaRepository historicoCobrancaRepository;
    private final AutenticacaoService autenticacaoService;
    private final PagamentoRepository pagamentoRepository;
    private final WhatsAppService whatsAppService;
    private final PedidoRepository pedidoRepository; // Alterado de VendaRepository
    private final ParcelaRepository parcelaRepository;
    private final LeadRepository leadRepository;

    @Value("${evolution.api.instance}")
    private String instanciaGlobal;

    @Transactional(readOnly = true)
    public Page<ClienteResponseDTO> listarTodos(String userId, boolean isAdmin, Pageable pageable) {
        Page<Cliente> clientesPage;

        if (isAdmin) {
            // Se for Admin, puxa o banco inteiro paginado
            clientesPage = clienteRepository.findAll(pageable);
        } else {
            // Se for Revendedor, puxa APENAS os clientes atrelados ao ID dele, paginado
            clientesPage = clienteRepository.findByRevendedorId(userId, pageable);
        }

        // O método .map() do Page substitui o stream().map().collect()
        return clientesPage.map(this::converterParaDTO);
    }

    @Transactional(readOnly = true)
    public Page<ClienteResponseDTO> listarPendentes(String userId, boolean isAdmin, Pageable pageable) {
        Page<Cliente> clientesPage;

        if (isAdmin) {
            // Busca todos os inadimplentes do sistema de forma paginada
            clientesPage = clienteRepository.findClientesInadimplentes(StatusParcela.PENDENTE, pageable);
        } else {
            // Busca apenas os inadimplentes vinculados ao revendedor logado
            clientesPage = clienteRepository.findClientesInadimplentesPorRevendedor(StatusParcela.PENDENTE, userId, pageable);
        }

        // Converte a página de entidades diretamente para a página de DTOs
        return clientesPage.map(this::converterParaDTO);
    }

    @Transactional
    @Retryable(
            retryFor = {
                    DataIntegrityViolationException.class,
                    ObjectOptimisticLockingFailureException.class,
                    StaleObjectStateException.class
            },
            maxAttempts = 3,
            backoff = @Backoff(delay = 150)
    )
    public Cliente sincronizarClienteComKeycloak(Jwt jwt, String visitorId) {
        String usuarioId = jwt.getSubject();

        return clienteRepository.findByUsuarioId(usuarioId).orElseGet(() -> {

            boolean ehCliente = false;
            Map<String, Object> realmAccess = jwt.getClaimAsMap("realm_access");

            if (realmAccess != null && realmAccess.containsKey("roles")) {
                List<String> roles = (List<String>) realmAccess.get("roles");
                ehCliente = roles.contains("cliente");
            }

            if (!ehCliente) {
                System.out.println("Usuário " + usuarioId + " logou, mas não possui a Role 'cliente'. Ignorando sincronização.");
                return null;
            }

            String email = jwt.getClaimAsString("email");
            String nome = jwt.getClaimAsString("name");

            Cliente novoCliente = new Cliente();
            novoCliente.setUsuarioId(usuarioId);
            novoCliente.setEmail(email);
            novoCliente.setNome(nome);

            Lead leadDoMarketing = null;

            if (visitorId != null && !visitorId.trim().isEmpty()) {
                Optional<Lead> leadOpt = leadRepository.findFirstByVisitorIdOrderByIdDesc(visitorId);

                if (leadOpt.isPresent()) {
                    Lead leadEncontrado = leadOpt.get();
                    if (leadEncontrado.getUsuarioId() == null || leadEncontrado.getUsuarioId().equals(usuarioId)) {
                        leadDoMarketing = leadEncontrado;
                    }
                }
            }

            if (leadDoMarketing == null) {
                leadDoMarketing = new Lead();
                leadDoMarketing.setVisitorId(visitorId != null ? visitorId : UUID.randomUUID().toString());
                leadDoMarketing.setUsuarioId(usuarioId);
                leadDoMarketing.setNome(nome);
                leadDoMarketing.setEmail(email);
                leadDoMarketing.setAtivo(true);
                leadDoMarketing.setComprou(false);
            } else {
                leadDoMarketing.setUsuarioId(usuarioId);
                leadDoMarketing.setEmail(email);

                if (nome != null && !nome.isEmpty()) {
                    leadDoMarketing.setNome(nome);
                }

                if (leadDoMarketing.getWhatsapp() != null) {
                    novoCliente.setWhatsapp(leadDoMarketing.getWhatsapp());
                }

                if (novoCliente.getNome() == null && leadDoMarketing.getNome() != null) {
                    novoCliente.setNome(leadDoMarketing.getNome());
                }
            }
            Cliente clienteSalvo = clienteRepository.save(novoCliente);
            leadDoMarketing.setCliente(clienteSalvo);
            leadRepository.save(leadDoMarketing);
            return clienteSalvo;
        });
    }

    @Transactional
    public void registrarCobranca(Long clienteId, String funcionario, String usuarioId, boolean isAdmin) {
        Cliente cliente = clienteRepository.findById(clienteId)
                .orElseThrow(() -> new RuntimeException("Cliente não encontrado!"));

        validarPosseDoCliente(cliente, usuarioId, isAdmin);

        // A cobrança deve sair pelo WhatsApp da revendedora DONA DO CLIENTE, não de quem
        // clicou em "Cobrar" no painel — por isso usamos cliente.getRevendedor(), e não
        // revendedorService.findById(usuarioId). Um admin (que normalmente não tem um
        // Revendedor vinculado ao próprio usuarioId) sempre caía num erro vazio aqui antes.
        // Cliente sem revendedor (loja matriz) ou revendedora sem instância conectada caem
        // no fallback global dentro de enviarCobrancaCliente.
        Revendedor revendedorDoCliente = cliente.getRevendedor();
        String instanciaWhatsapp = (revendedorDoCliente != null && revendedorDoCliente.getWhatsappInstance() != null)
                ? revendedorDoCliente.getWhatsappInstance().getInstanceName()
                : null;
        // Conta como "vencida" tanto quem já está com status ATRASADA (job noturno já rodou)
        // quanto quem ainda está PENDENTE mas já passou (ou é hoje) a data de vencimento — o
        // ParcelaService só atualiza o status uma vez por dia, então uma parcela que vence
        // hoje pode ainda estar PENDENTE no banco na hora em que a revendedora tenta cobrar.
        // Isso mantém a regra igual à usada no painel de Gestão de Clientes (habilita o botão
        // "Cobrar" a partir do próprio dia do vencimento).
        LocalDate hoje = LocalDate.now();
        BigDecimal totalDevido = cliente.getPedidos().stream()
                .flatMap(pedido -> pedido.getParcelasDetalhadas().stream())
                .filter(parcela -> StatusParcela.ATRASADA.equals(parcela.getStatus())
                        || (StatusParcela.PENDENTE.equals(parcela.getStatus())
                                && parcela.getDataVencimento() != null
                                && !parcela.getDataVencimento().isAfter(hoje)))
                .map(parcela -> parcela.getValor() != null ? parcela.getValor() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        if (totalDevido.compareTo(BigDecimal.ZERO) <= 0) {
            throw new RuntimeException("Este cliente não possui pedidos com parcelas pendentes.");
        }

        String valorFormatado = String.format(new Locale("pt", "BR"), "%.2f", totalDevido);

        String mensagem = "Olá *" + cliente.getNome() + "*, tudo bem?\n\n" +
                "Aqui é da *Clarice Joias* 💎.\n" +
                "Consta em nosso sistema um saldo pendente no valor de *R$ " + valorFormatado + "*.\n\n" +
                "Gostaria de verificar uma previsão de pagamento para podermos dar baixa no sistema? Qualquer dúvida, estamos à disposição!";

        // Só enfileira aqui — NÃO grava HistoricoCobranca ainda. O histórico (que trava o
        // reenvio por cooldownHoras) só é criado pelo WhatsAppWorker quando a Evolution API
        // confirmar que a mensagem foi ENVIADA de verdade. Se a instância estiver errada/offline
        // e o envio falhar, o cliente não pode ficar "protegido" por 24h de um envio que nunca
        // aconteceu.
        whatsAppService.enviarCobrancaCliente(cliente, mensagem, funcionario, instanciaWhatsapp);
    }

    public List<MovimentacaoDTO> buscarHistoricoCompras(Long clienteId, String usuarioId, boolean isAdmin) {
        Cliente cliente = clienteRepository.findById(clienteId)
                .orElseThrow(() -> new RuntimeException("Cliente não encontrado!"));

        validarPosseDoCliente(cliente, usuarioId, isAdmin);

        List<MovimentacaoDTO> extrato = new ArrayList<>();

        if (cliente.getPedidos() != null) {
            cliente.getPedidos().forEach(pedido -> {

                List<PagamentoDTO> pagamentosDoPedido = new ArrayList<>();
                if (pedido.getPagamentos() != null) {
                    pedido.getPagamentos().forEach(p -> pagamentosDoPedido.add(
                            PagamentoDTO.builder()
                                    .data(p.getDataPagamento().atStartOfDay())
                                    .valor(p.getValorPago())
                                    .metodo(p.getFormaPagamento())
                                    .observacao(p.getObservacao())
                                    .build()
                    ));
                }
                pagamentosDoPedido.sort(Comparator.comparing(PagamentoDTO::getData).reversed());

                List<ParcelaDTO> listaParcelasDTO = new ArrayList<>();
                if (pedido.getParcelasDetalhadas() != null && !pedido.getParcelasDetalhadas().isEmpty()) {
                    listaParcelasDTO = pedido.getParcelasDetalhadas().stream().map(p ->
                            ParcelaDTO.builder()
                                    .id(p.getId())
                                    .numeroParcela(p.getNumeroParcela())
                                    .valor(p.getValor())
                                    .dataVencimento(p.getDataVencimento())
                                    .dataPagamento(p.getDataPagamento())
                                    .status(p.getStatus())
                                    .build()
                    ).collect(Collectors.toList());
                }

                extrato.add(
                        MovimentacaoDTO.builder()
                                .tipo("COMPRA")
                                .data(pedido.getDataCriacao()) // Alterado de dataVenda
                                .valor(pedido.getTotal())
                                .metodo(pedido.getMetodoPagamento())
                                .valorEntrada(pedido.getValorEntrada())
                                .valorDevido(pedido.getValorDevido())
                                .qtdParcelas(pedido.getParcelas())
                                .parcelas(listaParcelasDTO)
                                .historicoPagamentos(pagamentosDoPedido)
                                .build()
                );
            });
        }

        extrato.sort(Comparator.comparing(MovimentacaoDTO::getData).reversed());

        return extrato;
    }

    private void validarPosseDoCliente(Cliente cliente, String usuarioId, boolean isAdmin) {
        if (isAdmin) return;

        boolean pertenceAoRevendedor = cliente.getRevendedor() != null
                && cliente.getRevendedor().getId().equals(usuarioId);

        if (!pertenceAoRevendedor) {
            throw new RegraNegocioException("Você não tem permissão para acessar os dados deste cliente.");
        }
    }

    // ==========================================================
    // CONVERSORES (Mappers)
    // ==========================================================

    private ClienteResponseDTO converterParaDTO(Cliente cliente) {
        ClienteResponseDTO dto = new ClienteResponseDTO();
        dto.setId(cliente.getId());
        dto.setNome(cliente.getNome());
        dto.setTelefone(cliente.getWhatsapp());
        dto.setNomeRevendedor(cliente.getRevendedor() != null ? cliente.getRevendedor().getNome() : "Clarice Joias");

        BigDecimal totalPedidosFiado = BigDecimal.ZERO;
        if (cliente.getPedidos() != null) {
            totalPedidosFiado = cliente.getPedidos().stream()
                    .filter(p -> p.getValorDevido() != null)
                    .map(Pedido::getValorDevido)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
        }

        BigDecimal totalPagamentosRealizados = BigDecimal.ZERO;
        if (cliente.getPagamentos() != null) {
            totalPagamentosRealizados = cliente.getPagamentos().stream()
                    .filter(p -> p.getValorPago() != null)
                    .map(Pagamento::getValorPago)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
        }

        if (cliente.getPedidos() != null) {
            // Nota: Se você renomeou VendaResponseDTO para PedidoResponseDTO dentro de ClienteResponseDTO, ajuste aqui
            List<ClienteResponseDTO.PedidoResponseDTO> pedidosDTO = cliente.getPedidos().stream()
                    .map(pedido -> {
                        ClienteResponseDTO.PedidoResponseDTO pDto = new ClienteResponseDTO.PedidoResponseDTO();
                        pDto.setId(pedido.getId());
                        pDto.setDataCriacao(pedido.getDataCriacao()); // Ajuste no DTO se necessário
                        pDto.setTotal(pedido.getTotal());
                        pDto.setMetodoPagamento(pedido.getMetodoPagamento());
                        pDto.setValorEntrada(pedido.getValorEntrada());
                        pDto.setValorDevido(pedido.getValorDevido());

                        if (pedido.getParcelasDetalhadas() != null) {
                            List<ClienteResponseDTO.ParcelaResponseDTO> parcelasDTO = pedido.getParcelasDetalhadas().stream()
                                    .map(parcela -> {
                                        ClienteResponseDTO.ParcelaResponseDTO parcelaDto = new ClienteResponseDTO.ParcelaResponseDTO();
                                        parcelaDto.setId(parcela.getId());
                                        parcelaDto.setNumeroParcela(parcela.getNumeroParcela());
                                        parcelaDto.setValor(parcela.getValor());
                                        parcelaDto.setDataVencimento(parcela.getDataVencimento());
                                        parcelaDto.setDataPagamento(parcela.getDataPagamento());
                                        parcelaDto.setStatus(parcela.getStatus());
                                        return parcelaDto;
                                    }).collect(Collectors.toList());
                            pDto.setParcelas(parcelasDTO);
                        }

                        return pDto;
                    }).collect(Collectors.toList());

            dto.setPedidos(pedidosDTO); // Alterado de setVendas
        }

        BigDecimal saldoReal = totalPedidosFiado.subtract(totalPagamentosRealizados);
        dto.setValorDevido(saldoReal.max(BigDecimal.ZERO));

        historicoCobrancaRepository.findFirstByClienteIdOrderByDataHoraDesc(cliente.getId())
                .ifPresent(historico -> {
                    ClienteResponseDTO.UltimaCobrancaDTO cobrancaDTO = new ClienteResponseDTO.UltimaCobrancaDTO();
                    cobrancaDTO.setDataHora(historico.getDataHora());
                    cobrancaDTO.setFuncionario(historico.getFuncionario());
                    dto.setUltimaCobranca(cobrancaDTO);
                });

        return dto;
    }

    private CompraDetalheDTO converterPedidoParaCompraDetalheDTO(Pedido pedido) {
        return CompraDetalheDTO.builder()
                .id(pedido.getId())
                .data(pedido.getDataCriacao()) // Alterado de dataVenda
                .total(pedido.getTotal() != null ? pedido.getTotal() : BigDecimal.ZERO)
                .metodoPagamento(pedido.getMetodoPagamento())
                .valorEntrada(pedido.getValorEntrada() != null ? pedido.getValorEntrada() : BigDecimal.ZERO)
                .parcelas(pedido.getParcelas() != null ? pedido.getParcelas() : 1)
                .build();
    }

    @Transactional
    public void registrarPagamento(Long clienteId, BaixaPagamentoDTO dto, String usuarioId, boolean isAdmin) {

        if (dto.parcelaId() != null) {
            pagarParcelaEspecifica(dto.parcelaId(), dto, usuarioId, isAdmin);
            return;
        }

        Cliente cliente = clienteRepository.findById(clienteId)
                .orElseThrow(() -> new RuntimeException("Cliente não encontrado"));

        validarPosseDoCliente(cliente, usuarioId, isAdmin);

        BigDecimal valorPago = dto.valorPago();
        if (valorPago == null || valorPago.compareTo(BigDecimal.ZERO) <= 0) {
            throw new RuntimeException("O valor do pagamento deve ser maior que zero.");
        }

        List<Pedido> pedidosPendentes = cliente.getPedidos().stream()
                .filter(p -> p.getValorDevido() != null && p.getValorDevido().compareTo(BigDecimal.ZERO) > 0)
                .sorted(Comparator.comparing(Pedido::getDataCriacao)) // Ordenando pela data de criação
                .collect(Collectors.toList());

        BigDecimal montanteDisponivel = valorPago;

        for (Pedido pedido : pedidosPendentes) {
            if (montanteDisponivel.compareTo(BigDecimal.ZERO) <= 0) break;

            BigDecimal dividaPedido = pedido.getValorDevido();
            BigDecimal abatePedido = montanteDisponivel.min(dividaPedido);

            BigDecimal valorParaParcelas = abatePedido;
            List<Parcela> parcelasPendentes = pedido.getParcelasDetalhadas().stream()
                    .filter(p -> StatusParcela.PENDENTE.equals(p.getStatus()))
                    .sorted(Comparator.comparing(Parcela::getNumeroParcela))
                    .collect(Collectors.toList());

            for (Parcela parcela : parcelasPendentes) {
                if (valorParaParcelas.compareTo(BigDecimal.ZERO) <= 0) break;

                BigDecimal valorParcela = parcela.getValor();

                if (valorParaParcelas.compareTo(valorParcela) >= 0) {
                    parcela.setStatus(StatusParcela.PAGA);
                    parcela.setDataPagamento(dto.dataPagamento() != null ? dto.dataPagamento() : LocalDate.now());
                    valorParaParcelas = valorParaParcelas.subtract(valorParcela);
                } else {
                    parcela.setValor(valorParcela.subtract(valorParaParcelas));
                    valorParaParcelas = BigDecimal.ZERO;
                }
            }

            pedido.setValorDevido(dividaPedido.subtract(abatePedido));
            pedidoRepository.save(pedido);

            Pagamento historico = new Pagamento();
            historico.setCliente(cliente);
            historico.setPedido(pedido); // Alterado de setVenda
            historico.setValorPago(abatePedido);
            historico.setFormaPagamento(dto.formaPagamento());
            historico.setDataPagamento(dto.dataPagamento() != null ? dto.dataPagamento() : LocalDate.now());
            historico.setObservacao(dto.observacao());
            pagamentoRepository.save(historico);

            montanteDisponivel = montanteDisponivel.subtract(abatePedido);
        }

        BigDecimal saldoAnterior = cliente.getSaldoDevedor() != null ? cliente.getSaldoDevedor() : BigDecimal.ZERO;
        cliente.setSaldoDevedor(saldoAnterior.subtract(valorPago).max(BigDecimal.ZERO));
        clienteRepository.save(cliente);
    }

    private void pagarParcelaEspecifica(Long parcelaId, BaixaPagamentoDTO dto, String usuarioId, boolean isAdmin) {
        Parcela parcela = parcelaRepository.findById(parcelaId)
                .orElseThrow(() -> new RuntimeException("Parcela não encontrada"));

        validarPosseDoCliente(parcela.getPedido().getCliente(), usuarioId, isAdmin);

        if (StatusParcela.PAGA.equals(parcela.getStatus()) || StatusParcela.CANCELADA.equals(parcela.getStatus())) {
            throw new RuntimeException("Esta parcela não pode ser paga (status: " + parcela.getStatus() + ").");
        }

        parcela.setStatus(StatusParcela.PAGA);
        parcela.setDataPagamento(dto.dataPagamento() != null ? dto.dataPagamento() : LocalDate.now());
        parcelaRepository.save(parcela);

        Pedido pedido = parcela.getPedido(); // Alterado de getVenda
        pedido.setValorDevido(pedido.getValorDevido().subtract(parcela.getValor()).max(BigDecimal.ZERO));
        pedidoRepository.save(pedido);

        Cliente cliente = pedido.getCliente();
        BigDecimal saldoAnterior = cliente.getSaldoDevedor() != null ? cliente.getSaldoDevedor() : BigDecimal.ZERO;
        cliente.setSaldoDevedor(saldoAnterior.subtract(parcela.getValor()).max(BigDecimal.ZERO));
        clienteRepository.save(cliente);

        Pagamento historico = new Pagamento();
        historico.setCliente(cliente);
        historico.setPedido(pedido); // Alterado de setVenda
        historico.setValorPago(parcela.getValor());
        historico.setFormaPagamento(dto.formaPagamento());
        historico.setDataPagamento(dto.dataPagamento() != null ? dto.dataPagamento() : LocalDate.now());

        String obs = dto.observacao() != null ? dto.observacao() : "";
        historico.setObservacao("Pagamento direto da " + parcela.getNumeroParcela() + "ª parcela. " + obs);

        pagamentoRepository.save(historico);
    }
}