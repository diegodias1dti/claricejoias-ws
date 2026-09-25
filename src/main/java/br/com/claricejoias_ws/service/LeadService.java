package br.com.claricejoias_ws.service;

import br.com.claricejoias_ws.dto.*;
import br.com.claricejoias_ws.enums.StatusDisparo;
import br.com.claricejoias_ws.enums.StatusPedido;
import br.com.claricejoias_ws.exceptions.RegraNegocioException;
import br.com.claricejoias_ws.model.*;
import br.com.claricejoias_ws.repository.FilaDisparoRepository;
import br.com.claricejoias_ws.repository.LeadRepository;
import br.com.claricejoias_ws.repository.PedidoRepository;
import br.com.claricejoias_ws.repository.RevendedorRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.hibernate.StaleObjectStateException;
import org.modelmapper.ModelMapper;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class LeadService {

    private final LeadRepository repository;
    private final KeycloakUserService keycloakUserService;
    private final ModelMapper modelMapper;
    private final EvolutionApiService evolutionApiService;
    private final FilaDisparoRepository filaDisparoRepository;
    private final PedidoRepository pedidoRepository;
    private final CarrinhoService carrinhoService;
    private final WhatsAppService whatsAppService;
    private final RevendedorRepository revendedorRepository;

    private final Map<String, String> otpCache = new ConcurrentHashMap<>();

    // ==========================================
    // ETAPA 1: CAPTAÇÃO VIA ISCA DIGITAL
    // ==========================================

    public boolean deveMostrarBotaoGuia(String visitorId, String revendedorId) {
        if (visitorId == null || visitorId.trim().isEmpty()) {
            return true;
        }

        boolean isLojaMatriz = (revendedorId == null || revendedorId.trim().isEmpty());
        if (isLojaMatriz) {
            return !repository.existsByVisitorIdAndRevendedorIsNull(visitorId);
        } else {
            return !repository.existsByVisitorIdAndRevendedorId(visitorId,revendedorId);
        }

    }

    @Transactional
    public String converterEmLead(LeadDTO dto, String visitorId, String revendedorId) {
        String whatsappLimpo = dto.getWhatsapp().replaceAll("[^0-9]", "");

        if (whatsappLimpo.length() < 10) {
            throw new RegraNegocioException("Número de WhatsApp incompleto.");
        }

        String idNavegador = (visitorId != null && !visitorId.trim().isEmpty()) ? visitorId : UUID.randomUUID().toString();

        boolean isLojaMatriz = (revendedorId == null || revendedorId.trim().isEmpty());

        Lead lead;
        if (isLojaMatriz) {
            lead = repository.findFirstByVisitorIdAndRevendedorIsNullOrderByIdDesc(idNavegador).orElse(null);
        } else {
            lead = repository.findFirstByVisitorIdAndRevendedorIdOrderByIdDesc(idNavegador,revendedorId).orElse(null);
        }


        if (lead != null && lead.getUsuarioId() != null) {
            lead = null; // Proteção para computador público
        }

        if (lead == null) {
            lead = new Lead();
            lead.setVisitorId(idNavegador);
            lead.setAtivo(true);
            lead.setComprou(false);
        }

        lead.setNome(dto.getNome());
        lead.setWhatsapp(whatsappLimpo);
        if (dto.getEmail() != null) lead.setEmail(dto.getEmail());

        repository.save(lead);

        // ADICIONE ESTA LINHA: Retorna o código do cupom que o cliente ganhou
        return "CLARICE20";
    }

    // ==========================================
    // ETAPA 2: CHECKOUT (OTP E CONVERSÃO)
    // ==========================================

    public void solicitarCodigoOtp(String whatsapp, String revendedorID) {
        boolean isLojaMatriz = (revendedorID == null || revendedorID.trim().isEmpty());
        String whatsappLimpo = whatsapp.replaceAll("[^0-9]", "");

        String otp = String.format("%06d", new Random().nextInt(999999));
        otpCache.put(whatsappLimpo, otp);

        String mensagem = String.format("🔒 Seu código de segurança Clarice Joias é: *%s*", otp);

        FilaDisparo fila = new FilaDisparo();
        fila.setNumeroDestino(whatsappLimpo);
        fila.setTexto(mensagem);
        fila.setTipo("OTP");
        fila.setStatus(StatusDisparo.PENDENTE);
        fila.setDataCriacao(LocalDateTime.now());

        if (isLojaMatriz) {
            fila.setRevendedorId(null);
            fila.setInstanciaWhatsapp(whatsAppService.resolverInstanciaGlobal());
        } else {
            Revendedor revendedor = revendedorRepository.findById(revendedorID).orElseThrow(() -> new RegraNegocioException(""));
            // Revendedora ainda sem WhatsApp conectado: cai no fallback global em vez de NPE.
            fila.setInstanciaWhatsapp(revendedor.getWhatsappInstance() != null
                    ? revendedor.getWhatsappInstance().getInstanceName()
                    : whatsAppService.resolverInstanciaGlobal());
            fila.setRevendedorId(revendedor.getId());
        }


        filaDisparoRepository.save(fila);
    }

    public boolean validarCodigoOtp(String whatsapp, String codigoInformado) {
        String whatsappLimpo = whatsapp.replaceAll("[^0-9]", "");
        String codigoSalvo = otpCache.get(whatsappLimpo);

        if (codigoSalvo != null && codigoSalvo.equals(codigoInformado)) {
            otpCache.remove(whatsappLimpo);
            return true;
        }
        return false;
    }

    @Transactional
    @Retryable(
            retryFor = {DataIntegrityViolationException.class, ObjectOptimisticLockingFailureException.class, StaleObjectStateException.class},
            maxAttempts = 3,
            backoff = @Backoff(delay = 150)
    )
    public Lead processarNovoLead(LeadRequestDTO dto, String visitorId, String usuarioIdOrigem, String revendedorId) {
        String whatsappLimpo = dto.getWhatsapp().replaceAll("[^0-9]", "");
        boolean estaLogado = (usuarioIdOrigem != null && !usuarioIdOrigem.trim().isEmpty());
        boolean isLojaMatriz = (revendedorId == null || revendedorId.trim().isEmpty());

        // 1. BUSCA O REVENDEDOR REAL NO BANCO (Para ter acesso à Instância do WhatsApp dele)
        Revendedor revendedor = null;
        if (!isLojaMatriz) {
            revendedor = revendedorRepository.findById(revendedorId)
                    .orElseThrow(() -> new RegraNegocioException("Revendedor não encontrado."));
        }

        String finalUsuarioId = usuarioIdOrigem;
        String senhaGerada = null;

        // 2. Cria a conta se necessário
        if (dto.isCriarConta() && !estaLogado) {
            senhaGerada = String.format("%06d", new Random().nextInt(999999));
            String emailKeycloak = whatsappLimpo + "@claricejoias.com.br";
            finalUsuarioId = keycloakUserService.criarUsuarioCliente(emailKeycloak, senhaGerada, dto.getNome(), whatsappLimpo, revendedorId);
        }

        // 3. BUSCA INTELIGENTE DO LEAD
        Lead lead = obterOuPromoverLeadSeguro(whatsappLimpo, visitorId, finalUsuarioId, revendedorId);

        // Atualiza os dados
        lead.setWhatsapp(whatsappLimpo);
        lead.setNome(dto.getNome());
        lead.setAtivo(true);
        if (visitorId != null) lead.setVisitorId(visitorId);
        if (finalUsuarioId != null) lead.setUsuarioId(finalUsuarioId);

        // 4. VINCULA O LEAD À LOJA ANTES DE SALVAR (Usando a entidade real buscada no passo 1)
        if (revendedor != null) {
            lead.setRevendedor(revendedor);
        }

        lead = repository.save(lead);

        // 5. Busca o Carrinho
        Pedido carrinhoPedido = carrinhoService.obterOuCriarCarrinho(visitorId, usuarioIdOrigem, revendedorId);

        if (carrinhoPedido.getItens().isEmpty()) {
            throw new RegraNegocioException("O carrinho está vazio.");
        }

        // 6. Atualiza o Pedido
        carrinhoPedido.setLead(lead);
        carrinhoPedido.setStatus(StatusPedido.AGUARDANDO_WHATSAPP);
        carrinhoPedido.setMetodoPagamento(dto.getMetodoPagamento() != null ? dto.getMetodoPagamento() : "PIX");
        carrinhoPedido.setDataAtualizacao(LocalDateTime.now());

        if (finalUsuarioId != null) {
            carrinhoPedido.setUsuarioId(finalUsuarioId);
        }
        pedidoRepository.save(carrinhoPedido);

        // 7. Fluxo de notificações (Passando o revendedor para cair na fila certa)
        enviarNotificacaoFinal(dto, lead, carrinhoPedido, whatsappLimpo, senhaGerada, revendedor);

        return lead;
    }

    // =======================================================================
// O MÉTODO SEGURO AGORA VERIFICA A LOJA
// =======================================================================
    private Lead obterOuPromoverLeadSeguro(String whatsapp, String visitorId, String usuarioId, String revendedorId) {
        boolean isLojaMatriz = (revendedorId == null || revendedorId.trim().isEmpty());

        // 1. Prioridade Máxima: O WhatsApp DENTRO DA MESMA LOJA
        Optional<Lead> leadPorWhatsapp = isLojaMatriz
                ? repository.findByWhatsappAndRevendedorIsNull(whatsapp)
                : repository.findByWhatsappAndRevendedorId(whatsapp, revendedorId);

        if (leadPorWhatsapp.isPresent()) {
            return leadPorWhatsapp.get();
        }

        // 2. Rastro anônimo DENTRO DA MESMA LOJA
        if (visitorId != null && !visitorId.trim().isEmpty()) {
            Lead leadDoNavegador = isLojaMatriz
                    ? repository.findFirstByVisitorIdAndRevendedorIsNullOrderByIdDesc(visitorId).orElse(null)
                    : repository.findFirstByVisitorIdAndRevendedorIdOrderByIdDesc(visitorId, revendedorId).orElse(null);

            if (leadDoNavegador != null) {
                // A TRAVA DO COMPUTADOR PÚBLICO
                if (leadDoNavegador.getUsuarioId() != null && !leadDoNavegador.getUsuarioId().equals(usuarioId)) {
                    return new Lead();
                } else {
                    return leadDoNavegador;
                }
            }
        }

        // 3. Se não achou, nasce um novo zerado
        return new Lead();
    }

    private void enviarNotificacaoFinal(LeadRequestDTO dto, Lead lead, Pedido pedido, String whatsappLimpo, String senhaGerada, Revendedor revendedor) {
        StringBuilder msg = new StringBuilder();
        msg.append(String.format("Olá *%s*, tudo bem? 💎\n\n", lead.getNome()));

        if (senhaGerada != null) {
            msg.append("Seu cadastro foi realizado com sucesso na Clarice Joias!\n");
            msg.append(String.format("Sua senha provisória de acesso é: *%s*\n\n", senhaGerada));
        } else {
            msg.append("Vimos que você atualizou seus dados!\n\n");
        }

        msg.append("Seu pedido já está separado em nosso sistema e aguardando finalização!");

        // JOGA NA FILA DO RABBITMQ! O usuário não precisa esperar o WhatsApp enviar.
        whatsAppService.enfileirarMensagemSistema(whatsappLimpo, msg.toString(), revendedor);
    }


    private Lead obterOuPromoverLeadSeguro(String whatsapp, String visitorId, String usuarioId) {
        // 1. Prioridade Máxima: O WhatsApp (Garante que é a mesma pessoa, independente do PC)
        Optional<Lead> leadPorWhatsapp = repository.findByWhatsapp(whatsapp);
        if (leadPorWhatsapp.isPresent()) {
            return leadPorWhatsapp.get();
        }

        // 2. Se o WhatsApp é novo, vamos ver se temos um rastro anônimo neste navegador
        if (visitorId != null && !visitorId.trim().isEmpty()) {
            Lead leadDoNavegador = repository.findFirstByVisitorIdOrderByIdDesc(visitorId).orElse(null);

            if (leadDoNavegador != null) {
                // =========================================================
                //  A TRAVA DO COMPUTADOR PÚBLICO
                // =========================================================
                if (leadDoNavegador.getUsuarioId() != null && !leadDoNavegador.getUsuarioId().equals(usuarioId)) {
                    // CENÁRIO: Outra pessoa já logou ou criou conta neste PC antes!
                    // Ação: Ignoramos o rastro antigo para não misturar os dados e criamos um novo.
                    return new Lead();
                } else {
                    // CENÁRIO: É apenas um visitante anônimo que acabou de decidir comprar/criar conta.
                    // Ação: "Promovemos" este Lead, mantendo o histórico dele.
                    return leadDoNavegador;
                }
            }
        }

        // 3. Se não achou WhatsApp e não tem rastro válido, cria um do zero.
        return new Lead();
    }


    // ==========================================
    // ETAPA 3: PAINEL ADMINISTRATIVO (CRUD)
    // ==========================================

    public Page<LeadDTO> listarTodos(String busca, Pageable pageable) {
        // Se a busca vier vazia, passamos null para a query ignorar o filtro
        String termoBusca = (busca != null) ? busca.trim() : "";

        return repository.buscarComFiltros(termoBusca, pageable).map(this::montarLeadDTO);
    }

    public LeadDTO buscarPorId(Long id) {
        Lead lead = repository.findById(id).orElseThrow(() -> new RegraNegocioException("Lead não encontrado"));
        return montarLeadDTO(lead);
    }

    public Lead buscarEntidadePorId(Long id) {
        return repository.findById(id).orElseThrow(() -> new RegraNegocioException("Lead não encontrado"));
    }

    private LeadDTO montarLeadDTO(Lead lead) {
        if (lead == null) return null;

        // 1. Instanciamos o DTO e mapeamos os campos básicos manualmente
        LeadDTO dto = new LeadDTO();
        dto.setId(lead.getId());
        dto.setNome(lead.getNome());
        dto.setWhatsapp(lead.getWhatsapp());
        dto.setEmail(lead.getEmail());
        dto.setAtivo(lead.getAtivo());
        dto.setComprou(lead.getComprou());

        if (lead.getCliente() != null) {
            dto.setCliente(modelMapper.map(lead.getCliente(), ClienteDTO.class));
            // Se quiser garantir que a flag comprou esteja true quando tiver cliente:
            dto.setComprou(true);
        }

        // ADICIONE ESTAS 3 LINHAS
        if (lead.getRevendedor() != null) {
            // Nota: Se na sua entidade Revendedor o nome for razaoSocial, altere para getRazaoSocial()
            dto.setNomeRevendedor(lead.getRevendedor().getNome());
        }

        // 2. Mapeamos os Itens (extraindo do Pedido que é um Carrinho) e Agrupamos
        if (lead.getPedidos() != null) {
            lead.getPedidos().stream()
                    .filter(p -> p.getStatus() != null &&
                            (p.getStatus().equals(StatusPedido.CARRINHO) || p.getStatus().equals(StatusPedido.CARRINHO_ABANDONADO)))
                    .findFirst() // Pegamos o carrinho
                    .ifPresent(pedido -> {

                        // Usamos Collectors.toMap para agrupar pelo ID do Produto
                        List<LeadItemDTO> itensDTO = new ArrayList<>(pedido.getItens().stream()
                                .filter(item -> item.getProduto() != null) // Prevenção de segurança
                                .collect(Collectors.toMap(
                                        item -> item.getProduto().getId(), // Chave: ID do Produto
                                        item -> { // Valor: Construção do LeadItemDTO inicial
                                            LeadItemDTO itemDto = new LeadItemDTO();
                                            itemDto.setId(item.getProduto().getId());
                                            itemDto.setQuantidade(item.getQuantidade());
                                            itemDto.setPrecoMomento(item.getPrecoUnitario());
                                            Produto prod = item.getProduto();
                                            ProdutoDTO prodDto = new ProdutoDTO();
                                            prodDto.setId(prod.getId());
                                            prodDto.setNome(prod.getNome());
                                            prodDto.setPreco(item.getPrecoUnitario()); // Mantemos o preço unitário
                                            prodDto.setImagens(prod.getImagens());

                                            itemDto.setProduto(prodDto);
                                            return itemDto;
                                        },
                                        (itemExistente, itemRepetido) -> {
                                            // Regra de Conflito: O que fazer se achar produtos iguais?
                                            // Somamos a quantidade do item repetido ao item que já existia no map
                                            itemExistente.setQuantidade(itemExistente.getQuantidade() + itemRepetido.getQuantidade());
                                            return itemExistente;
                                        }
                                )).values()); // Pegamos apenas os valores resultantes

                        dto.setItens(itensDTO);
                    });
        }

        // 3. Mapeamos o histórico de disparos manualmente
        if (lead.getHistoricoDisparos() != null) {
            List<HistoricoDisparoDTO> disparosDTO = lead.getHistoricoDisparos().stream().map(h -> {
                HistoricoDisparoDTO hDto = new HistoricoDisparoDTO();
                hDto.setId(h.getId());
//            hDto.setMensagem(h.getMensagem());
                hDto.setDataHoraDisparo(h.getDataHoraDisparo());
//            hDto.setTipo(h.getTipo());
                return hDto;
            }).collect(Collectors.toList());

            dto.setHistoricoDisparos(disparosDTO);
        }

        return dto;
    }

    @Transactional
    public Lead marcarComoComprado(Long leadId) {
        Lead lead = repository.findById(leadId).orElseThrow(() -> new RuntimeException("Lead não encontrado"));

        // Busca o pedido que era o carrinho e marca como pago
        pedidoRepository.findFirstByVisitorIdAndStatusOrderByIdDesc(lead.getVisitorId(), StatusPedido.CARRINHO)
                .ifPresent(p -> {
                    p.setStatus(StatusPedido.PAGO);
                    pedidoRepository.save(p);
                });

        lead.setComprou(true);
        return repository.save(lead);
    }

    @Transactional
    public Lead alternarStatus(Long id) {
        return repository.findById(id).map(lead -> {
            lead.setAtivo(!lead.getAtivo());
            return repository.save(lead);
        }).orElseThrow(() -> new RegraNegocioException("Lead não encontrado com o ID: " + id));
    }

    @Transactional
    public LeadDTO atualizarLead(Long id, LeadAtualizacaoDTO dto) {
        Lead lead = repository.findById(id)
                .orElseThrow(() -> new RegraNegocioException("Lead não encontrado com o ID: " + id));

        // Atualiza apenas os campos que vieram preenchidos no DTO
        if (dto.getNome() != null && !dto.getNome().trim().isEmpty()) {
            lead.setNome(dto.getNome());
        }

        if (dto.getWhatsapp() != null && !dto.getWhatsapp().trim().isEmpty()) {
            lead.setWhatsapp(dto.getWhatsapp()); // O setter da entidade já limpa os caracteres especiais
        }

        if (dto.getEmail() != null && !dto.getEmail().trim().isEmpty()) {
            lead.setEmail(dto.getEmail());
        }

        lead = repository.save(lead);
        return montarLeadDTO(lead);
    }

    public MetricasLeadDTO calcularMetricas() {
        long totalLeads = repository.count();
        // Presume a existência do método countByComprouTrue no seu LeadRepository
        long leadsConvertidos = repository.countByComprouTrue();

        double taxaConversao = 0.0;
        if (totalLeads > 0) {
            taxaConversao = ((double) leadsConvertidos / totalLeads) * 100.0;
            // Arredonda para 2 casas decimais (ex: 15.54)
            taxaConversao = Math.round(taxaConversao * 100.0) / 100.0;
        }

        return new MetricasLeadDTO(totalLeads, leadsConvertidos, taxaConversao);
    }

    @Transactional
    public void registrarHistoricoMensagem(Long id, MensagemLogDTO dto) {
        Lead lead = repository.findById(id)
                .orElseThrow(() -> new RegraNegocioException("Lead não encontrado com o ID: " + id));

        HistoricoDisparo historico = new HistoricoDisparo();
//        historico.setMensagem(dto.getMensagem());
//        historico.setTipo(dto.getTipo());
        historico.setDataHoraDisparo(LocalDateTime.now());
        historico.setLead(lead); // Importante para o relacionamento bidirecional

        // Como a entidade Lead tem cascade = CascadeType.ALL em historicoDisparos,
        // basta adicionar à lista e o Hibernate cuidará de salvar na tabela filha.
        if (lead.getHistoricoDisparos() == null) {
            lead.setHistoricoDisparos(new ArrayList<>());
        }

        lead.getHistoricoDisparos().add(historico);
        repository.save(lead);
    }
}