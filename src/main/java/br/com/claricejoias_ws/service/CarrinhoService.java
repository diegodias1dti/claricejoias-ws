package br.com.claricejoias_ws.service;

import br.com.claricejoias_ws.dto.CarrinhoDTO;
import br.com.claricejoias_ws.dto.ItemCarrinhoDTO;
import br.com.claricejoias_ws.dto.ProdutoDTO;
import br.com.claricejoias_ws.enums.OrigemPedido;
import br.com.claricejoias_ws.enums.StatusPedido;
import br.com.claricejoias_ws.exceptions.RegraNegocioException;
import br.com.claricejoias_ws.model.ItemPedido;
import br.com.claricejoias_ws.model.Pedido;
import br.com.claricejoias_ws.model.Produto;
import br.com.claricejoias_ws.model.Revendedor;
import br.com.claricejoias_ws.repository.PedidoRepository;
import br.com.claricejoias_ws.repository.ProdutoRepository;
import br.com.claricejoias_ws.repository.RevendedorRepository;
import lombok.RequiredArgsConstructor;
import org.hibernate.StaleObjectStateException;
import org.modelmapper.ModelMapper;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class CarrinhoService {

    private final PedidoRepository pedidoRepository;
    private final ProdutoRepository produtoRepository;
    private final ModelMapper modelMapper;
    private final RevendedorRepository revendedorRepository;


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
    public Pedido obterOuCriarCarrinho(String visitorId, String usuarioId, String revendedorId) {
        Pedido carrinho = isUsuarioLogado(usuarioId)
                // Passamos o revendedorId para o carrinho de usuário
                ? processarCarrinhoDeUsuario(visitorId, usuarioId, revendedorId)
                // Passamos o revendedorId para o carrinho anônimo
                : processarCarrinhoAnonimo(visitorId, revendedorId);

        // Um item pode ter ficado "órfão" se o produto dele foi desativado depois (ex:
        // categoria/subcategoria excluída) — como Produto tem @SQLRestriction("ativo = true"),
        // item.getProduto() vem null nesse caso em vez de lançar erro. Removemos aqui pra não
        // quebrar quem usa o carrinho depois (adicionarItem, removerItem, checkout etc).
        limparItensComProdutoInativo(carrinho);
        return carrinho;
    }

    private void limparItensComProdutoInativo(Pedido carrinho) {
        if (carrinho.getItens() != null) {
            carrinho.getItens().removeIf(item -> item.getProduto() == null);
        }
    }

    @Transactional(readOnly = true)
    public CarrinhoDTO consultarCarrinhoAtualDTO(String visitorId, String usuarioId, String revendedorId) {
        Optional<Pedido> carrinhoOpt = Optional.empty();

        // Descobre se é compra na matriz (parâmetro nulo ou vazio)
        boolean isLojaMatriz = (revendedorId == null || revendedorId.trim().isEmpty());

        if (isUsuarioLogado(usuarioId)) {
            // CADEIA DO USUÁRIO LOGADO
            if (isLojaMatriz) {
                carrinhoOpt = pedidoRepository.findFirstByUsuarioIdAndStatusAndRevendedorIsNullOrderByIdDesc(
                        usuarioId, StatusPedido.CARRINHO);
            } else {
                carrinhoOpt = pedidoRepository.findFirstByUsuarioIdAndStatusAndRevendedorIdOrderByIdDesc(
                        usuarioId, StatusPedido.CARRINHO, revendedorId);
            }

        } else if (visitorId != null && !visitorId.trim().isEmpty()) {
            // CADEIA DO VISITANTE (ANÔNIMO)
            if (isLojaMatriz) {
                carrinhoOpt = pedidoRepository.findFirstByVisitorIdAndStatusAndRevendedorIsNullOrderByIdDesc(
                        visitorId, StatusPedido.CARRINHO);
            } else {
                carrinhoOpt = pedidoRepository.findFirstByVisitorIdAndStatusAndRevendedorIdOrderByIdDesc(
                        visitorId, StatusPedido.CARRINHO, revendedorId);
            }
        }

        return carrinhoOpt.map(this::convertToDTO).orElse(null);
    }


    public Pedido consultarPedidoAtualEntidade(String visitorId, String usuarioId) {
        Optional<Pedido> carrinhoOpt = Optional.empty();

        if (isUsuarioLogado(usuarioId)) {
            carrinhoOpt = pedidoRepository.findFirstByUsuarioIdAndStatusOrderByIdDesc(usuarioId, StatusPedido.CARRINHO);
        } else if (visitorId != null) {
            carrinhoOpt = pedidoRepository.findFirstByVisitorIdAndStatusOrderByIdDesc(visitorId, StatusPedido.CARRINHO);
        }

        return carrinhoOpt.orElse(null);
    }




    // No CarrinhoService.java
    public CarrinhoDTO aplicarCupom(String visitorId, String usuarioId, String codigoCupom) {
        // 1. Busca o pedido atual (Status CARRINHO)
        Pedido pedido = consultarPedidoAtualEntidade(visitorId, usuarioId);

        // 2. Lógica de validação do cupom (exemplo fixo de 20%)
        if ("CLARICE20".equalsIgnoreCase(codigoCupom)) {
            BigDecimal subtotal = calcularSubtotal(pedido); // Soma dos itens
            BigDecimal desconto = subtotal.multiply(new BigDecimal("0.20"));

            pedido.setCupomDesconto(codigoCupom.toUpperCase());
            pedido.setValorDesconto(desconto);
            pedido.setTotalCobrado(subtotal.subtract(desconto));
        } else {
            throw new RegraNegocioException("Cupom inválido.");
        }

        pedidoRepository.save(pedido);
        return convertToDTO(pedido); // Retorna o DTO com os novos valores
    }

    // Adicione este método dentro do seu CarrinhoService (ou PedidoService)

    private BigDecimal calcularSubtotal(Pedido pedido) {
        // 1. Prevenção de nulos: se não houver itens, o subtotal é zero
        if (pedido.getItens() == null || pedido.getItens().isEmpty()) {
            return BigDecimal.ZERO;
        }

        // 2. Usamos o Stream com map() e reduce() para somar os BigDecimals
        return pedido.getItens().stream()
                .map(item -> {
                    // Assumindo que seu getPrecoUnitario() já retorna um BigDecimal.
                    // Se ele ainda retornar Double, use: BigDecimal.valueOf(item.getPrecoUnitario())
                    BigDecimal preco = item.getPrecoUnitario();

                    // Converte a quantidade (que deve ser int ou Integer) para BigDecimal
                    BigDecimal quantidade = new BigDecimal(item.getQuantidade());

                    // Multiplica o preço pela quantidade
                    return preco.multiply(quantidade);
                })
                // Soma todos os resultados começando do ZERO
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public CarrinhoDTO convertToDTO(Pedido pedido) {
        CarrinhoDTO dto = new CarrinhoDTO();
        dto.setId(pedido.getId());
        dto.setVisitorId(pedido.getVisitorId());
        dto.setUsuarioId(pedido.getUsuarioId());
        dto.setCupomDesconto(pedido.getCupomDesconto());
        dto.setValorDesconto(pedido.getValorDesconto());

        List<ItemCarrinhoDTO> itensDTO = pedido.getItens().stream()
                // item.getProduto() vem null se o produto foi desativado depois de entrar no
                // carrinho (ver limparItensComProdutoInativo) — ignoramos esses itens aqui
                // porque esse método é chamado também em transações somente-leitura, onde não
                // dá pra limpar o carrinho de verdade no banco.
                .filter(item -> item.getProduto() != null)
                .map(item -> {
                    ItemCarrinhoDTO itemDto = new ItemCarrinhoDTO();
                    itemDto.setProduto(modelMapper.map(item.getProduto(), ProdutoDTO.class));
                    itemDto.setQuantidade(item.getQuantidade());
                    return itemDto;
                }).toList();

        dto.setItens(itensDTO);

        BigDecimal total = itensDTO.stream()
                .map(i -> i.getProduto().getPreco().multiply(BigDecimal.valueOf(i.getQuantidade())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        dto.setValorTotal(total);
        return dto;
    }

    private boolean isUsuarioLogado(String usuarioId) {
        return usuarioId != null && !usuarioId.trim().isEmpty();
    }

    private Pedido processarCarrinhoDeUsuario(String visitorId, String usuarioId, String revendedorId) {
        boolean isLojaMatriz = (revendedorId == null || revendedorId.trim().isEmpty());
        Pedido carrinhoOficial;

        // 1. Busca o carrinho correto (Matriz ou Revendedor) ou cria um novo
        if (isLojaMatriz) {
            carrinhoOficial = pedidoRepository.findFirstByUsuarioIdAndStatusAndRevendedorIsNullOrderByIdDesc(usuarioId, StatusPedido.CARRINHO)
                    .orElseGet(() -> criarCarrinho(null, usuarioId, null)); // Passando null para o revendedor
        } else {
            carrinhoOficial = pedidoRepository.findFirstByUsuarioIdAndStatusAndRevendedorIdOrderByIdDesc(usuarioId, StatusPedido.CARRINHO, revendedorId)
                    .orElseGet(() -> criarCarrinho(null, usuarioId, revendedorId)); // Passando o ID do revendedor
        }

        // 2. A "REGRA DE OURO" (vincularRevendedor) não muda mais a posse de um carrinho único,
        // mas você pode mantê-la caso ela faça validações extras ou popule o objeto Revendedor na memória.
        // Se o método apenas fazia um setRevendedor(), ele se torna opcional aqui, pois o criarCarrinho já deve fazer isso.
        if (!isLojaMatriz) {
            vincularRevendedor(carrinhoOficial, revendedorId);
        }

        // 3. Mescla os itens caso o usuário tenha adicionado algo no carrinho antes de fazer o login
        if (visitorId != null) {
            mesclarCarrinhoAnonimoNoOficial(visitorId, carrinhoOficial);
        }

        // 4. Salva o resultado final
        return pedidoRepository.saveAndFlush(carrinhoOficial);
    }


    private Pedido processarCarrinhoAnonimo(String visitorId, String revendedorId) {
        boolean isLojaMatriz = (revendedorId == null || revendedorId.trim().isEmpty());
        Pedido carrinhoAnonimo;

        // 1. Busca o carrinho correto do visitante (Matriz ou Revendedor) ou cria um novo
        if (isLojaMatriz) {
            carrinhoAnonimo = pedidoRepository.findFirstByVisitorIdAndStatusAndRevendedorIsNullOrderByIdDesc(visitorId, StatusPedido.CARRINHO)
                    .orElseGet(() -> criarCarrinho(visitorId, null, null)); // Matriz (sem revendedor)
        } else {
            carrinhoAnonimo = pedidoRepository.findFirstByVisitorIdAndStatusAndRevendedorIdOrderByIdDesc(visitorId, StatusPedido.CARRINHO, revendedorId)
                    .orElseGet(() -> criarCarrinho(visitorId, null, revendedorId)); // Com Revendedor
        }

        // 2. Garante o vínculo caso o método vincularRevendedor faça validações adicionais
        if (!isLojaMatriz) {
            vincularRevendedor(carrinhoAnonimo, revendedorId);
        }

        // 3. Salva as alterações no banco e retorna
        return pedidoRepository.saveAndFlush(carrinhoAnonimo);
    }

    // =======================================================
// MÉTODOS AUXILIARES
// =======================================================

    private void vincularRevendedor(Pedido carrinho, String revendedorId) {
        if (revendedorId != null) {
            // Otimização: Só bate no banco se o revendedor atual for nulo ou diferente do novo
            if (carrinho.getRevendedor() == null || !carrinho.getRevendedor().getId().equals(revendedorId)) {
                Revendedor revendedor = revendedorRepository.findById(String.valueOf(revendedorId))
                        .orElseThrow(() -> new RuntimeException("Revendedor não encontrado"));

                carrinho.setRevendedor(revendedor);
            }
        }
    }

    private Pedido criarCarrinho(String visitorId, String usuarioId, String revendedorId) {
        Pedido novo = new Pedido();
        novo.setVisitorId(visitorId);
        novo.setUsuarioId(usuarioId);
        novo.setStatus(StatusPedido.CARRINHO); // Define como carrinho
        novo.setOrigem(OrigemPedido.ECOMMERCE); // Define a origem
        novo.setDataCriacao(LocalDateTime.now());
        novo.setMetodoPagamento("PENDENTE");

        // Vincula o revendedor se o ID foi informado (Não é a Matriz)
        if (revendedorId != null && !revendedorId.trim().isEmpty()) {
            Revendedor revendedor = new Revendedor();

            // Dica: Se o ID na sua entidade Revendedor for do tipo UUID em vez de String,
            // você precisará converter aqui usando: UUID.fromString(revendedorId)
            revendedor.setId(revendedorId);

            novo.setRevendedor(revendedor);
        }

        return pedidoRepository.saveAndFlush(novo);
    }


    private void mesclarCarrinhoAnonimoNoOficial(String visitorId, Pedido carrinhoOficial) {
        pedidoRepository.findFirstByVisitorIdAndStatusOrderByIdDesc(visitorId, StatusPedido.CARRINHO).ifPresent(anonimo -> {

            if (anonimo.getId().equals(carrinhoOficial.getId())) {
                carrinhoOficial.setVisitorId(null);
                pedidoRepository.saveAndFlush(carrinhoOficial);
                return;
            }

            transferirItens(anonimo, carrinhoOficial);

            // Se o carrinho anônimo tinha um revendedor e o oficial não tem, nós herdamos ele na mesclagem!
            if (carrinhoOficial.getRevendedor() == null && anonimo.getRevendedor() != null) {
                carrinhoOficial.setRevendedor(anonimo.getRevendedor());
            }

            pedidoRepository.delete(anonimo);
            pedidoRepository.flush();
            pedidoRepository.saveAndFlush(carrinhoOficial);
        });
    }



    private void transferirItens(Pedido origem, Pedido destino) {
        for (ItemPedido itemOrigem : origem.getItens()) {
            if (itemOrigem.getProduto() == null) continue; // produto desativado depois — ignora

            destino.getItens().stream()
                    .filter(i -> i.getProduto() != null && i.getProduto().getId().equals(itemOrigem.getProduto().getId()))
                    .findFirst()
                    .ifPresentOrElse(
                            itemDestino -> itemDestino.setQuantidade(itemDestino.getQuantidade() + itemOrigem.getQuantidade()),
                            () -> adicionarNovoItemAoCarrinho(destino, itemOrigem.getProduto(), itemOrigem.getQuantidade())
                    );
        }
    }

    private void adicionarNovoItemAoCarrinho(Pedido pedido, Produto produto, Integer quantidade) {
        ItemPedido novoItem = new ItemPedido();
        novoItem.setProduto(produto);
        novoItem.setQuantidade(quantidade);
        // Trava o preço atual da joia no momento que vai pro carrinho
        novoItem.setPrecoUnitario(produto.getPreco() != null ? produto.getPreco() : BigDecimal.ZERO);
        novoItem.setSubtotal(novoItem.getPrecoUnitario().multiply(BigDecimal.valueOf(quantidade)));

        // Usa o método utilitário que corrigimos na entidade Pedido
        pedido.addItem(novoItem);
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
    public Pedido adicionarItem(String visitorId, String usuarioId, Long produtoId, Integer quantidade, String revendedorId) {
        Pedido carrinho = obterOuCriarCarrinho(visitorId, usuarioId,revendedorId);

        Optional<ItemPedido> itemExistente = carrinho.getItens().stream()
                .filter(item -> item.getProduto().getId().equals(produtoId))
                .findFirst();

        if (itemExistente.isPresent()) {
            ItemPedido item = itemExistente.get();
            int novaQuantidade = item.getQuantidade() + quantidade;
            if (novaQuantidade <= 0) {
                carrinho.getItens().remove(item);
            } else {
                item.setQuantidade(novaQuantidade);
                item.setSubtotal(item.getPrecoUnitario().multiply(BigDecimal.valueOf(novaQuantidade)));
            }
        } else if (quantidade > 0) {
            Produto produto = produtoRepository.findById(produtoId)
                    .orElseThrow(() -> new RuntimeException("Produto não encontrado"));
            adicionarNovoItemAoCarrinho(carrinho, produto, quantidade);
        }

        return pedidoRepository.saveAndFlush(carrinho);
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
    public Pedido removerItem(String visitorId, String usuarioId, Long produtoId, String revendedorId) {
        Pedido carrinho = obterOuCriarCarrinho(visitorId, usuarioId,revendedorId);
        carrinho.getItens().removeIf(item -> item.getProduto().getId().equals(produtoId));
        return pedidoRepository.saveAndFlush(carrinho);
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
    public void limparCarrinho(String visitorId, String usuarioId) {
        Pedido carrinho = obterOuCriarCarrinho(visitorId, usuarioId,null);
        carrinho.getItens().clear();
        pedidoRepository.saveAndFlush(carrinho);
    }
}