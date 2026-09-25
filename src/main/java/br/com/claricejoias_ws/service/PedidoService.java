package br.com.claricejoias_ws.service;

import br.com.claricejoias_ws.dto.CheckoutDTO;
import br.com.claricejoias_ws.dto.PedidoDTO;
import br.com.claricejoias_ws.dto.PedidoRequestDTO;
import br.com.claricejoias_ws.enums.OrigemPedido;
import br.com.claricejoias_ws.enums.StatusParcela;
import br.com.claricejoias_ws.enums.StatusPedido;
import br.com.claricejoias_ws.exceptions.RegraNegocioException;
import br.com.claricejoias_ws.model.*;
import br.com.claricejoias_ws.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.modelmapper.ModelMapper;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class PedidoService {

    private final PedidoRepository pedidoRepository;
    private final ProdutoRepository produtoRepository;
    private final ClienteRepository clienteRepository;
    private final RevendedorRepository revendedorRepository;
    private final EstoqueRevendedorRepository estoqueRevendedorRepository;
    private final ModelMapper modelMapper;
    private final KeycloakUserService keycloakUserService;
    private final WhatsAppService whatsAppService;
    private final MercadoPagoService mercadoPagoService;



    @Transactional
    public Pedido registrarPedidoPDV(PedidoRequestDTO dto, String userId, boolean isAdmin, String loginOperador) {
        // Permite lançar a venda com a data/hora de agora (padrão) ou, se informado,
        // com uma data passada — ex: venda feita no balcão e cadastrada só depois.
        LocalDate dataInformada = dto.getDataVenda();
        if (dataInformada != null && dataInformada.isAfter(LocalDate.now())) {
            throw new RuntimeException("A data da venda não pode ser no futuro.");
        }
        LocalDateTime dataVendaFinal = (dataInformada != null)
                ? dataInformada.atTime(LocalTime.now())
                : LocalDateTime.now();

        Pedido pedido = new Pedido();
        pedido.setDataCriacao(dataVendaFinal);
        pedido.setLoginOperador(loginOperador);
        pedido.setOrigem(OrigemPedido.PDV);
        pedido.setStatus(StatusPedido.PAGO);

        // 1. Identifica se existe um revendedor vinculado
        Revendedor revendedor = null;
        if (!isAdmin) {
            revendedor = revendedorRepository.findById(userId)
                    .orElseThrow(() -> new RuntimeException("Revendedor não cadastrado ou não autorizado."));
            pedido.setRevendedor(revendedor); // Vincula o pedido ao revendedor
        }

        // --- Lógica de Financeiro (Total, Pagamento, Troco) ---
        BigDecimal totalVenda = dto.getTotal() != null ? dto.getTotal() : BigDecimal.ZERO;
        pedido.setTotal(totalVenda);

        // ========================================================================
        // NOVO: CÁLCULO DA COMISSÃO DO REVENDEDOR (LUCRO)
        // ========================================================================
        if (revendedor != null && revendedor.getPercentualComissao() != null) {
            // Divide a porcentagem por 100 (Ex: 30.00 / 100 = 0.30)
            BigDecimal taxa = revendedor.getPercentualComissao().divide(new BigDecimal("100"), 4, RoundingMode.HALF_UP);
            // Multiplica o total da venda pela taxa para achar a comissão
            BigDecimal comissao = totalVenda.multiply(taxa).setScale(2, RoundingMode.HALF_UP);

            pedido.setComissaoRevendedor(comissao);
        } else {
            // Se for venda da matriz (Admin) ou o revendedor não tiver taxa definida
            pedido.setComissaoRevendedor(BigDecimal.ZERO);
        }
        // ========================================================================

        String metodo = dto.getPagamento().getMetodo();
        BigDecimal valorEntradaInput = dto.getPagamento().getValorEntrada();
        BigDecimal valorRecebidoInput = dto.getPagamento().getValorRecebido();
        Integer parcelasInput = dto.getPagamento().getParcelas();

        pedido.setMetodoPagamento(metodo);
        pedido.setParcelas(parcelasInput != null && parcelasInput > 0 ? parcelasInput : 1);

        BigDecimal valorEntradaSeguro = (valorEntradaInput != null) ? valorEntradaInput : BigDecimal.ZERO;
        pedido.setValorEntrada(valorEntradaSeguro);

        if ("especie".equalsIgnoreCase(metodo) && valorRecebidoInput != null) {
            pedido.setValorRecebido(valorRecebidoInput);
            pedido.setTroco(valorRecebidoInput.subtract(totalVenda).max(BigDecimal.ZERO));
        } else {
            pedido.setValorRecebido(totalVenda);
            pedido.setTroco(BigDecimal.ZERO);
        }

        // Lógica de Parcelas para Fiado
        if ("fiado".equalsIgnoreCase(metodo)) {
            BigDecimal saldoDevedor = totalVenda.subtract(valorEntradaSeguro);
            pedido.setValorDevido(saldoDevedor);

            int qtdParcelas = pedido.getParcelas();
            BigDecimal valorPorParcela = saldoDevedor.divide(
                    BigDecimal.valueOf(qtdParcelas), 2, RoundingMode.HALF_UP
            );
            // A última parcela absorve o resto da divisão, garantindo que a soma das
            // parcelas bata exatamente com o saldo devedor (sem sobra/falta de centavos).
            BigDecimal valorUltimaParcela = saldoDevedor.subtract(
                    valorPorParcela.multiply(BigDecimal.valueOf(qtdParcelas - 1))
            );

            List<Parcela> listaParcelas = new ArrayList<>();
            LocalDate dataBaseParcelas = dataVendaFinal.toLocalDate();

            for (int i = 1; i <= qtdParcelas; i++) {
                Parcela parcela = new Parcela();
                parcela.setPedido(pedido);
                parcela.setNumeroParcela(i);
                parcela.setValor(i == qtdParcelas ? valorUltimaParcela : valorPorParcela);
                parcela.setStatus(StatusParcela.PENDENTE);
                parcela.setDataVencimento(dataBaseParcelas.plusMonths(i));
                listaParcelas.add(parcela);
            }
            pedido.setParcelasDetalhadas(listaParcelas);
        } else {
            pedido.setValorDevido(BigDecimal.ZERO);
        }

        // 2. Mapeamento de Itens e BAIXA DE ESTOQUE (Central vs Maleta)
        final Revendedor revendedorFinal = revendedor; // Necessário para o lambda
        List<ItemPedido> itens = dto.getItens().stream().map(itemDto -> {
            Produto produto = produtoRepository.findById(itemDto.getId())
                    .orElseThrow(() -> new RuntimeException("Produto não encontrado"));

            if (isAdmin) {
                // ---> REGRA ADMIN: Baixa do Estoque Central <---
                produto.diminuirEstoqueCentral(itemDto.getQuantidade());
            } else {
                // ---> REGRA REVENDEDOR: Baixa da Maleta <---
                EstoqueRevendedor estoqueMaleta = estoqueRevendedorRepository
                        .findByProdutoIdAndRevendedorId(produto.getId(), revendedorFinal.getId())
                        .orElseThrow(() -> new RuntimeException("Produto não consta na sua maleta."));

                estoqueMaleta.diminuirEstoque(itemDto.getQuantidade());
            }

            ItemPedido item = new ItemPedido();
            item.setPedido(pedido);
            item.setProduto(produto);
            item.setQuantidade(itemDto.getQuantidade());

            // ---> Preço de Venda
            BigDecimal precoUnitario = itemDto.getPreco() != null ? itemDto.getPreco() : BigDecimal.ZERO;
            item.setPrecoUnitario(precoUnitario);
            item.setSubtotal(precoUnitario.multiply(BigDecimal.valueOf(itemDto.getQuantidade())));

            // ========================================================================
            // NOVO: SALVANDO O CUSTO HISTÓRICO DA PEÇA (Margem Bruta)
            // ========================================================================
            BigDecimal custoUnitario = produto.getPrecoCusto() != null ? produto.getPrecoCusto() : BigDecimal.ZERO;
            item.setCustoUnitario(custoUnitario);

            // A margem bruta da loja nesta peça (Preço Venda - Preço Custo) * Qtd
            BigDecimal lucroUnitario = precoUnitario.subtract(custoUnitario);
            item.setLucro(lucroUnitario.multiply(BigDecimal.valueOf(itemDto.getQuantidade())));
            // ========================================================================

            return item;
        }).collect(Collectors.toList());

        pedido.setItens(itens);

        // 3. Lógica do Cliente (Isolado para Revendedor / Global para Admin)
        if (dto.getCliente() != null) {
            String whatsapp = dto.getCliente().getTelefone();

            Optional<Cliente> clienteOpt;
            if (isAdmin) {
                // Admin busca na base global (onde revendedor_id é nulo)
                clienteOpt = clienteRepository.findByWhatsappAndRevendedorIsNull(whatsapp);
            } else {
                // Revendedor busca na sua base isolada
                clienteOpt = clienteRepository.findByWhatsappAndRevendedorId(whatsapp, revendedor.getId());
            }

            Cliente cliente = clienteOpt.orElseGet(() -> {
                Cliente novo = new Cliente();
                novo.setNome(dto.getCliente().getNome());
                novo.setWhatsapp(whatsapp);
                novo.setRevendedor(revendedorFinal); // Se for admin, fica nulo
                novo.setUsuarioId(java.util.UUID.randomUUID().toString());
                return clienteRepository.save(novo);
            });

            pedido.setCliente(cliente);

            if ("fiado".equalsIgnoreCase(metodo)) {
                BigDecimal dividaAtual = cliente.getSaldoDevedor() != null ? cliente.getSaldoDevedor() : BigDecimal.ZERO;
                cliente.setSaldoDevedor(dividaAtual.add(pedido.getValorDevido()));
            }
        }

        return pedidoRepository.save(pedido);
    }

    /**
     * Checkout real do e-commerce. Recebe o carrinho ativo do visitante/cliente logado e:
     * 1) opcionalmente cria a conta dele (mesmo padrão do cadastro via Lead);
     * 2) baixa o estoque na hora (central se for a loja matriz, maleta se for de uma revendedora);
     * 3) finaliza como "RETIRADA" (combinar com a revendedora, confirmado manualmente depois) ou
     *    "ONLINE" (gera link de pagamento no Mercado Pago e devolve a URL pra redirecionar).
     *
     * A revendedora dona do pedido vem do PRÓPRIO CARRINHO (carrinhoAtual.getRevendedor()),
     * nunca do usuarioId de quem está comprando — usar o ID do cliente pra buscar uma
     * "revendedora" era o bug que fazia esse checkout falhar pra qualquer cliente real.
     */
    @Transactional
    public Map<String, Object> realizarCheckoutOnline(String visitorId, String usuarioId, CheckoutDTO dto) {

        Pedido carrinhoAtual = pedidoRepository.buscarCarrinhoAtivo(visitorId, usuarioId)
                .orElseThrow(() -> new RuntimeException("Nenhum carrinho ativo encontrado para checkout."));

        if (carrinhoAtual.getItens().isEmpty()) {
            throw new RuntimeException("Não é possível finalizar um pedido com o carrinho vazio.");
        }

        Revendedor revendedor = carrinhoAtual.getRevendedor();
        boolean isLojaMatriz = (revendedor == null);
        String whatsappLimpo = dto.getWhatsapp() != null ? dto.getWhatsapp().replaceAll("[^0-9]", "") : null;

        // 1. Cria a conta do cliente se solicitado e ele ainda não estiver logado.
        //    criarUsuarioCliente já cria também o Cliente local isolado para esta loja,
        //    então buscamos esse registro em vez de correr o risco de duplicá-lo.
        String finalUsuarioId = usuarioId;
        String senhaGerada = null;
        Cliente cliente;

        if (dto.isCriarConta() && (usuarioId == null || usuarioId.isBlank())) {
            if (whatsappLimpo == null || whatsappLimpo.length() < 10) {
                throw new RuntimeException("WhatsApp inválido para criar a conta.");
            }
            senhaGerada = String.format("%06d", new Random().nextInt(999999));
            String emailKeycloak = whatsappLimpo + "@claricejoias.com.br";
            finalUsuarioId = keycloakUserService.criarUsuarioCliente(
                    emailKeycloak, senhaGerada, dto.getNome(), whatsappLimpo,
                    revendedor != null ? revendedor.getId() : null
            );
            cliente = clienteRepository.findByUsuarioId(finalUsuarioId)
                    .orElseThrow(() -> new IllegalStateException("Falha ao localizar o cliente recém-criado."));
        } else {
            Optional<Cliente> clienteExistente = isLojaMatriz
                    ? clienteRepository.findByWhatsappAndRevendedorIsNull(whatsappLimpo)
                    : clienteRepository.findByWhatsappAndRevendedorId(whatsappLimpo, revendedor.getId());

            final String usuarioIdFinal = finalUsuarioId;
            cliente = clienteExistente.orElseGet(() -> {
                Cliente novo = new Cliente();
                novo.setNome(dto.getNome());
                novo.setWhatsapp(whatsappLimpo);
                novo.setEmail(dto.getEmail());
                novo.setRevendedor(revendedor);
                novo.setUsuarioId(usuarioIdFinal != null && !usuarioIdFinal.isBlank() ? usuarioIdFinal : UUID.randomUUID().toString());
                return clienteRepository.save(novo);
            });
        }

        // 2. Baixa o estoque agora — vale tanto pra "pagar agora" quanto pra "retirar na
        //    loja", pra não vender a mesma peça duas vezes enquanto um pedido está pendente.
        for (ItemPedido item : carrinhoAtual.getItens()) {
            if (isLojaMatriz) {
                item.getProduto().diminuirEstoqueCentral(item.getQuantidade());
            } else {
                EstoqueRevendedor estoqueMaleta = estoqueRevendedorRepository
                        .findByProdutoIdAndRevendedorId(item.getProduto().getId(), revendedor.getId())
                        .orElseThrow(() -> new RuntimeException("A peça \"" + item.getProduto().getNome() + "\" não está mais disponível nesta loja."));
                estoqueMaleta.diminuirEstoque(item.getQuantidade());
            }
        }

        carrinhoAtual.setCliente(cliente);
        carrinhoAtual.setUsuarioId(finalUsuarioId);
        carrinhoAtual.setDataAtualizacao(LocalDateTime.now());
        carrinhoAtual.calcularTotal();

        Map<String, Object> resultado = new HashMap<>();

        if ("RETIRADA".equalsIgnoreCase(dto.getTipoFinalizacao())) {
            carrinhoAtual.setStatus(StatusPedido.AGUARDANDO_RETIRADA);
            carrinhoAtual.setMetodoPagamento("retirada_loja");
            Pedido salvo = pedidoRepository.save(carrinhoAtual);

            if (senhaGerada != null) notificarNovaConta(cliente, senhaGerada, revendedor);

            resultado.put("tipo", "RETIRADA");
            resultado.put("pedido", salvo);
        } else {
            carrinhoAtual.setStatus(StatusPedido.PENDENTE_PAGAMENTO);
            carrinhoAtual.setMetodoPagamento("mercadopago");
            Pedido salvo = pedidoRepository.save(carrinhoAtual);

            String redirectUrl = mercadoPagoService.criarPreferencia(salvo);
            pedidoRepository.save(salvo); // persiste o mercadoPagoPreferenceId setado acima

            if (senhaGerada != null) notificarNovaConta(cliente, senhaGerada, revendedor);

            resultado.put("tipo", "ONLINE");
            resultado.put("pedidoId", salvo.getId());
            resultado.put("redirectUrl", redirectUrl);
        }

        return resultado;
    }

    /**
     * Chamado pelo webhook do Mercado Pago. Idempotente: se o pedido já foi processado
     * (PAGO ou CANCELADO), não faz nada — o Mercado Pago pode reenviar a mesma notificação.
     */
    @Transactional
    public void confirmarPagamentoOnline(String paymentId, String externalReference, String statusMercadoPago) {
        if (externalReference == null || externalReference.isBlank()) {
            log.warn("Webhook do Mercado Pago sem external_reference (paymentId={}), ignorando.", paymentId);
            return;
        }

        Long pedidoId;
        try {
            pedidoId = Long.parseLong(externalReference);
        } catch (NumberFormatException e) {
            log.warn("external_reference inválido no webhook do Mercado Pago: {}", externalReference);
            return;
        }

        Pedido pedido = pedidoRepository.findById(pedidoId).orElse(null);
        if (pedido == null) {
            log.warn("Webhook do Mercado Pago referencia um pedido inexistente: {}", pedidoId);
            return;
        }

        if (pedido.getStatus() == StatusPedido.PAGO || pedido.getStatus() == StatusPedido.CANCELADO) {
            log.info("Pedido {} já estava {} — webhook do Mercado Pago ignorado (idempotência).", pedidoId, pedido.getStatus());
            return;
        }

        pedido.setMercadoPagoPaymentId(paymentId);

        if ("approved".equalsIgnoreCase(statusMercadoPago)) {
            pedido.setStatus(StatusPedido.PAGO);
            pedido.setValorRecebido(pedido.getTotal());
            pedido.setTroco(BigDecimal.ZERO);
            pedido.setComissaoRevendedor(calcularComissao(pedido));
        } else if ("rejected".equalsIgnoreCase(statusMercadoPago) || "cancelled".equalsIgnoreCase(statusMercadoPago)) {
            devolverEstoque(pedido);
            pedido.setStatus(StatusPedido.CANCELADO);
        }
        // outros status (pending, in_process...) apenas gravam o paymentId e aguardam o próximo webhook.

        pedidoRepository.save(pedido);
    }

    /**
     * A revendedora (ou admin) confirma que o cliente apareceu, pagou e retirou a peça.
     */
    @Transactional
    public Pedido confirmarRetirada(Long pedidoId, String usuarioId, boolean isAdmin) {
        Pedido pedido = pedidoRepository.findById(pedidoId)
                .orElseThrow(() -> new RuntimeException("Pedido não encontrado."));

        validarPosseDoPedido(pedido, usuarioId, isAdmin);

        if (pedido.getStatus() != StatusPedido.AGUARDANDO_RETIRADA) {
            throw new RuntimeException("Este pedido não está aguardando retirada (status atual: " + pedido.getStatus() + ").");
        }

        pedido.setStatus(StatusPedido.PAGO);
        pedido.setValorRecebido(pedido.getTotal());
        pedido.setTroco(BigDecimal.ZERO);
        pedido.setComissaoRevendedor(calcularComissao(pedido));
        pedido.setDataAtualizacao(LocalDateTime.now());

        return pedidoRepository.save(pedido);
    }

    /**
     * Cancela um pedido que ainda não foi pago (o cliente nunca apareceu pra retirada,
     * ou o pagamento online nunca se confirmou) e devolve o estoque reservado.
     */
    @Transactional
    public Pedido cancelarPedidoPendente(Long pedidoId, String usuarioId, boolean isAdmin) {
        Pedido pedido = pedidoRepository.findById(pedidoId)
                .orElseThrow(() -> new RuntimeException("Pedido não encontrado."));

        validarPosseDoPedido(pedido, usuarioId, isAdmin);

        if (pedido.getStatus() != StatusPedido.AGUARDANDO_RETIRADA && pedido.getStatus() != StatusPedido.PENDENTE_PAGAMENTO) {
            throw new RuntimeException("Só é possível cancelar pedidos pendentes ou aguardando retirada (status atual: " + pedido.getStatus() + ").");
        }

        devolverEstoque(pedido);
        pedido.setStatus(StatusPedido.CANCELADO);
        pedido.setDataAtualizacao(LocalDateTime.now());

        return pedidoRepository.save(pedido);
    }

    private void validarPosseDoPedido(Pedido pedido, String usuarioId, boolean isAdmin) {
        if (isAdmin) return;
        boolean pertenceAoRevendedor = pedido.getRevendedor() != null && pedido.getRevendedor().getId().equals(usuarioId);
        if (!pertenceAoRevendedor) {
            throw new RegraNegocioException("Você não tem permissão para gerenciar este pedido.");
        }
    }

    private BigDecimal calcularComissao(Pedido pedido) {
        Revendedor revendedor = pedido.getRevendedor();
        if (revendedor == null || revendedor.getPercentualComissao() == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal taxa = revendedor.getPercentualComissao().divide(new BigDecimal("100"), 4, RoundingMode.HALF_UP);
        return pedido.getTotal().multiply(taxa).setScale(2, RoundingMode.HALF_UP);
    }

    private void devolverEstoque(Pedido pedido) {
        boolean isLojaMatriz = pedido.getRevendedor() == null;
        for (ItemPedido item : pedido.getItens()) {
            if (isLojaMatriz) {
                item.getProduto().adicionarEstoque(item.getQuantidade());
            } else {
                estoqueRevendedorRepository.findByProdutoIdAndRevendedorId(item.getProduto().getId(), pedido.getRevendedor().getId())
                        .ifPresent(estoque -> estoque.setQuantidade(estoque.getQuantidade() + item.getQuantidade()));
            }
        }
    }

    private void notificarNovaConta(Cliente cliente, String senhaGerada, Revendedor revendedor) {
        String mensagem = String.format(
                "Olá *%s*! 💎\n\nSeu cadastro foi realizado com sucesso na Clarice Joias!\n" +
                        "Sua senha provisória de acesso é: *%s*\n\nSeu pedido já foi registrado com sucesso!",
                cliente.getNome(), senhaGerada
        );
        whatsAppService.enfileirarMensagemSistema(cliente.getWhatsapp(), mensagem, revendedor);
    }

    public Page<PedidoDTO> listarPedidos(String userId, boolean isAdmin, String loginOperador, String metodoPagamento, LocalDate dataInicio, LocalDate dataFim, Pageable pageable) {

        LocalDateTime inicioDia = (dataInicio != null) ? dataInicio.atStartOfDay() : null;
        LocalDateTime fimDia = (dataFim != null) ? dataFim.atTime(LocalTime.MAX) : null;

        // Regra de Ouro: Se for admin, revendedorIdFiltro fica nulo (traz tudo).
        // Se for revendedor, fixa o filtro no ID dele.
        String revendedorIdFiltro = isAdmin ? null : userId;

        Page<Pedido> pedidosPage = pedidoRepository.findComFiltros(
                revendedorIdFiltro,
                loginOperador,
                metodoPagamento,
                inicioDia,
                fimDia,
                pageable
        );

        return pedidosPage.map(p -> modelMapper.map(p, PedidoDTO.class));
    }

    public Page<PedidoDTO> listarMeusPedidos(String usuarioId, String revendedorId, Pageable pageable) {
        boolean isLojaMatriz = (revendedorId == null || revendedorId.trim().isEmpty());
        Page<Pedido> pedidos;
        if (isLojaMatriz){
             pedidos = pedidoRepository.findFirstByUsuarioIdAndRevendedorIsNullOrderByIdDesc(usuarioId, pageable);
        }else {
             pedidos = pedidoRepository.findByUsuarioIdAndRevendedorIdOrderByIdDesc(usuarioId,revendedorId, pageable);
        }

        return pedidos.map(pedido -> modelMapper.map(pedido, PedidoDTO.class));
    }
}