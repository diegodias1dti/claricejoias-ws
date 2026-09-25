package br.com.claricejoias_ws.service;

import br.com.claricejoias_ws.config.RabbitMQConfig;
import br.com.claricejoias_ws.dto.DisparoMensagemDTO;
import br.com.claricejoias_ws.dto.LeadDTO;
import br.com.claricejoias_ws.enums.StatusDisparo;
import br.com.claricejoias_ws.exceptions.RegraNegocioException;
import br.com.claricejoias_ws.model.*;
import br.com.claricejoias_ws.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.modelmapper.ModelMapper;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class WhatsAppService {

    private final HistoricoDisparoRepository historicoRepository;
    private final FilaDisparoRepository filaRepository;
    private final HistoricoCobrancaRepository historicoCobrancaRepository;
    private final FilaCobrancaRepository filaCobrancaRepository;
    private final RabbitTemplate rabbitTemplate;
    private final ModelMapper modelMapper;
    private final WhatsappInstanceRepository whatsappInstanceRepository;

    @Value("${app.whatsapp.cooldown-horas:24}")
    private int cooldownHoras;

    // Usado só como ÚLTIMO recurso, se não houver nenhuma instância "loja matriz" cadastrada
    // no banco ainda (revendedor IS NULL). Na prática, resolverInstanciaGlobal() below busca a
    // instância real que foi conectada pelo painel — não esse valor fixo do properties, que
    // facilmente fica apontando para uma instância antiga/inexistente na Evolution API.
    @Value("${evolution.api.instance}")
    private String instanciaGlobal;

    // Fonte única de verdade para "qual instância usar quando não há revendedor": busca no
    // banco a WhatsappInstance sem revendedor vinculado (a "loja matriz" criada via painel
    // admin em /config/whatsapp). Só cai no valor fixo do properties se isso ainda não existir.
    public String resolverInstanciaGlobal() {
        return whatsappInstanceRepository.findByRevendedorIsNull()
                .map(WhatsappInstance::getInstanceName)
                .orElse(instanciaGlobal);
    }

    // =========================================================================
    // 1. MÉTODOS DE ENFILEIRAMENTO (SALVAM NO BANCO COM STATUS PENDENTE)
    // =========================================================================

    public void enviarMensagemTexto(LeadDTO lead, String texto, String operador, Revendedor revendedor) {
        if (filaRepository.existsByLeadIdAndStatus(lead.getId(), StatusDisparo.PENDENTE)) {
            throw new RegraNegocioException("Operação negada: " + lead.getNome() + " já possui uma mensagem na fila aguardando disparo.");
        }

        validarCooldown(modelMapper.map(lead, Lead.class));

        FilaDisparo fila = new FilaDisparo();
        fila.setLead(modelMapper.map(lead, Lead.class));
        fila.setTexto(texto);
        fila.setRevendedorId(revendedor != null ? revendedor.getId() : null);
        fila.setOperador(operador);
        fila.setStatus(StatusDisparo.PENDENTE);
        fila.setDataCriacao(LocalDateTime.now());
        fila.setInstanciaWhatsapp(instanciaParaRevendedor(revendedor));

        filaRepository.save(fila);
        log.info("Mensagem TEXTO enfileirada para o lead: {}", lead.getNome());
    }

    public void enviarMensagemImagem(Lead lead, String legenda, String path, String operador, Revendedor revendedor) {
        // Nota: não aplicamos aqui a checagem de "já existe mensagem PENDENTE" que existe em
        // enviarMensagemTexto, porque o MensagemController enfileira várias imagens em sequência
        // para o mesmo lead numa única chamada (uma por produto do carrinho) — todas ficam
        // PENDENTE até o dispatcher rodar. Aplicar aquele check aqui quebraria esse fluxo.
        // O cooldown de 24h (validarCooldown) já impede o abuso entre disparos diferentes.
        validarCooldown(lead);

        FilaDisparo fila = new FilaDisparo();
        fila.setLead(lead);
        fila.setTexto(legenda);
        fila.setUrlImagem(path);
        fila.setRevendedorId(revendedor != null ? revendedor.getId() : null);
        fila.setOperador(operador);
        fila.setStatus(StatusDisparo.PENDENTE);
        fila.setDataCriacao(LocalDateTime.now());
        fila.setInstanciaWhatsapp(instanciaParaRevendedor(revendedor));

        filaRepository.save(fila);
        log.info("Mensagem IMAGEM enfileirada para o lead: {}", lead.getNome());
    }

    private String instanciaParaRevendedor(Revendedor revendedor) {
        if (revendedor == null) {
            return resolverInstanciaGlobal();
        }
        return revendedor.getWhatsappInstance() != null ? revendedor.getWhatsappInstance().getInstanceName() : resolverInstanciaGlobal();
    }

    public void enviarCobrancaCliente(Cliente cliente, String texto, String operador, String instanciaRevendedor) {
        if (filaCobrancaRepository.existsByClienteIdAndStatus(cliente.getId(), StatusDisparo.PENDENTE)) {
            throw new RegraNegocioException("Já existe uma cobrança na fila para " + cliente.getNome());
        }

        Optional<HistoricoCobranca> ultimoHistorico = historicoCobrancaRepository
                .findFirstByClienteIdOrderByDataHoraDesc(cliente.getId());

        if (ultimoHistorico.isPresent()) {
            LocalDateTime dataLiberacao = ultimoHistorico.get().getDataHora().plus(cooldownHoras, ChronoUnit.HOURS);
            if (LocalDateTime.now().isBefore(dataLiberacao)) {
                throw new RegraNegocioException("Atenção! Este cliente já foi cobrado recentemente. " +
                        "Nova mensagem liberada em: " + dataLiberacao);
            }
        }

        FilaCobranca fila = new FilaCobranca();
        fila.setCliente(cliente);
        fila.setTexto(texto);
        fila.setOperador(operador);
        fila.setStatus(StatusDisparo.PENDENTE);
        fila.setDataCriacao(LocalDateTime.now());
        fila.setInstanciaWhatsapp(instanciaRevendedor != null ? instanciaRevendedor : resolverInstanciaGlobal());

        filaCobrancaRepository.save(fila);
        log.info("COBRANÇA enfileirada para o cliente: {}", cliente.getNome());
    }

    public void enfileirarMensagemSistema(String numeroDestino, String texto, Revendedor revendedor) {
        FilaDisparo fila = new FilaDisparo();
        fila.setNumeroDestino(numeroDestino);
        fila.setTexto(texto);
        fila.setTipo("OTP");
        fila.setStatus(StatusDisparo.PENDENTE);
        fila.setDataCriacao(LocalDateTime.now());

        fila.setRevendedorId(revendedor != null ? revendedor.getId() : null);
        fila.setInstanciaWhatsapp(instanciaParaRevendedor(revendedor));

        filaRepository.save(fila);
        log.info("Mensagem OTP enfileirada para o número: {}", numeroDestino);
    }

    private void validarCooldown(Lead lead) {
        Optional<HistoricoDisparo> ultimoDisparo = historicoRepository.findTopByLeadIdOrderByDataHoraDisparoDesc(lead.getId());
        if (ultimoDisparo.isPresent()) {
            LocalDateTime dataUltimo = ultimoDisparo.get().getDataHoraDisparo();
            LocalDateTime dataLiberacao = dataUltimo.plus(cooldownHoras, ChronoUnit.HOURS);

            if (LocalDateTime.now().isBefore(dataLiberacao)) {
                throw new RegraNegocioException("Aguarde! O próximo disparo para " + lead.getNome() +
                        " só estará liberado em: " + dataLiberacao);
            }
        }
    }

    // =========================================================================
    // 2. DISPATCHERS (BUSCAM DO BANCO E JOGAM NO RABBITMQ)
    // =========================================================================

    @Scheduled(fixedDelay = 5000)
    @Transactional
    public void despacharFilaPrincipalParaRabbitMQ() {
        List<FilaDisparo> pendentes = filaRepository.findNextMessagesFairly();

        for (FilaDisparo disparo : pendentes) {

            // Tratamento extra para OTP expirado na hora de jogar pro Rabbit
            if ("OTP".equals(disparo.getTipo())) {
                long minutosNaFila = ChronoUnit.MINUTES.between(disparo.getDataCriacao(), LocalDateTime.now());
                if (minutosNaFila >= 5) {
                    disparo.setStatus(StatusDisparo.EXPIRADO);
                    disparo.setMotivoFalha("OTP expirou antes do envio (mais de 5 min na fila)");
                    filaRepository.save(disparo);
                    log.warn("OTP descartado pois expirou: {}", disparo.getNumeroDestino());
                    continue; // Pula pro próximo
                }
            }

            String tipoMensagem = (disparo.getUrlImagem() != null && !disparo.getUrlImagem().isEmpty()) ? "IMAGEM" : "TEXTO";
            String instancia = disparo.getInstanciaWhatsapp() != null ? disparo.getInstanciaWhatsapp() : resolverInstanciaGlobal();
            String numero = disparo.getLead() != null ? disparo.getLead().getWhatsapp() : disparo.getNumeroDestino();

            DisparoMensagemDTO dto = new DisparoMensagemDTO(
                    disparo.getId(), "DISPARO", tipoMensagem, numero,
                    disparo.getTexto(), disparo.getUrlImagem(), instancia
            );

            // Joga na Fila do RabbitMQ
            rabbitTemplate.convertAndSend(RabbitMQConfig.EXCHANGE_DISPAROS, RabbitMQConfig.ROUTING_KEY_DISPAROS, dto);

            // Marca como processando para não pegar novamente no próximo loop do banco
            disparo.setStatus(StatusDisparo.EM_PROCESSAMENTO);
            filaRepository.save(disparo);
        }
    }

    @Scheduled(fixedDelay = 10000)
    @Transactional
    public void despacharCobrancasParaRabbitMQ() {
        // Usa a MESMA fila/exchange dos disparos: o DisparoMensagemDTO já trazia o campo
        // "tipoFila" (DISPARO/COBRANCA) exatamente para o WhatsAppWorker saber em qual tabela
        // (FilaDisparoRepository ou FilaCobrancaRepository) atualizar o status ao processar.
        List<FilaCobranca> pendentes = filaCobrancaRepository.findByStatusOrderByDataCriacaoAsc(StatusDisparo.PENDENTE);

        for (FilaCobranca cobranca : pendentes) {
            String instancia = cobranca.getInstanciaWhatsapp() != null ? cobranca.getInstanciaWhatsapp() : resolverInstanciaGlobal();
            String numero = cobranca.getCliente() != null ? cobranca.getCliente().getWhatsapp() : null;

            if (numero == null) {
                cobranca.setStatus(StatusDisparo.ERRO);
                cobranca.setMensagemErro("Cliente sem WhatsApp cadastrado.");
                filaCobrancaRepository.save(cobranca);
                continue;
            }

            DisparoMensagemDTO dto = new DisparoMensagemDTO(
                    cobranca.getId(), "COBRANCA", "TEXTO", numero,
                    cobranca.getTexto(), null, instancia
            );

            rabbitTemplate.convertAndSend(RabbitMQConfig.EXCHANGE_DISPAROS, RabbitMQConfig.ROUTING_KEY_DISPAROS, dto);

            cobranca.setStatus(StatusDisparo.EM_PROCESSAMENTO);
            filaCobrancaRepository.save(cobranca);
        }
    }

}