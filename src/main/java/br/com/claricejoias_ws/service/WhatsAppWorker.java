package br.com.claricejoias_ws.service;

import br.com.claricejoias_ws.config.RabbitMQConfig;
import br.com.claricejoias_ws.dto.DisparoMensagemDTO;
import br.com.claricejoias_ws.enums.StatusDisparo;
import br.com.claricejoias_ws.model.FilaCobranca;
import br.com.claricejoias_ws.model.HistoricoCobranca;
import br.com.claricejoias_ws.repository.FilaCobrancaRepository;
import br.com.claricejoias_ws.repository.FilaDisparoRepository;
import br.com.claricejoias_ws.repository.HistoricoCobrancaRepository;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class WhatsAppWorker {

    private final FilaDisparoRepository filaRepository;
    private final FilaCobrancaRepository filaCobrancaRepository;
    private final HistoricoCobrancaRepository historicoCobrancaRepository;

    // ATENÇÃO: Injete aqui o seu serviço real da Evolution API que não veio nos arquivos
     private final EvolutionApiService evolutionApiService;

    @RabbitListener(queues = RabbitMQConfig.FILA_DISPAROS, ackMode = "MANUAL")
    public void processarDisparo(DisparoMensagemDTO mensagem, Channel channel,
                                 @Header(AmqpHeaders.DELIVERY_TAG) long tag) throws Exception {
        log.info("Processando mensagem ID: {} para o telefone: {}", mensagem.getIdRegistroBanco(), mensagem.getNumeroDestino());

        try {
            boolean temImagem = "IMAGEM".equals(mensagem.getTipoMensagem())
                    && mensagem.getUrlImagem() != null && !mensagem.getUrlImagem().isBlank();

            boolean sucesso = temImagem
                    ? evolutionApiService.enviarImagem(mensagem.getNumeroDestino(), mensagem.getTexto(), mensagem.getUrlImagem(), mensagem.getInstanciaWhatsapp())
                    : evolutionApiService.enviarMensagemTexto(mensagem.getNumeroDestino(), mensagem.getTexto(), mensagem.getInstanciaWhatsapp());

            if (sucesso) {
                // Confirma que a mensagem foi processada (Remove da fila do RabbitMQ)
                channel.basicAck(tag, false);
                atualizarStatusBanco(mensagem, StatusDisparo.ENVIADO);

                // Proteção contra bloqueio do WhatsApp (Rate Limiting de 2 segundos)
                Thread.sleep(2000);
            } else {
                // Falha de envio por erro da API/Número (Não volta pra fila, vai pra ERRO no banco)
                channel.basicReject(tag, false);
                atualizarStatusBanco(mensagem, StatusDisparo.ERRO);
            }

        } catch (Exception e) {
            log.error("Falha na infraestrutura ao enviar mensagem ID: {}", mensagem.getNumeroDestino(), e);

            // Rejeita a mensagem e manda para a DLQ (Dead Letter Queue) para avaliar/retentar depois
            channel.basicNack(tag, false, false);
            atualizarStatusBanco(mensagem, StatusDisparo.FALHA_INFRA);
        }
    }

    // "tipoFila" (DISPARO/COBRANCA) diz em qual tabela está o registro que gerou a mensagem —
    // FilaDisparo (leads/OTP) ou FilaCobranca (cobrança manual de cliente inadimplente).
    private void atualizarStatusBanco(DisparoMensagemDTO mensagem, StatusDisparo status) {
        if ("COBRANCA".equals(mensagem.getTipoFila())) {
            filaCobrancaRepository.findById(mensagem.getIdRegistroBanco()).ifPresent(fila -> {
                fila.setStatus(status);
                filaCobrancaRepository.save(fila);

                // O HistoricoCobranca (que trava reenvio por cooldownHoras e alimenta "Última
                // Cobrança" no painel) só é criado aqui, quando a Evolution API confirmou que a
                // mensagem foi ENVIADA de verdade — nunca no momento de só enfileirar.
                if (status == StatusDisparo.ENVIADO) {
                    registrarHistoricoCobranca(fila);
                }
            });
        } else {
            filaRepository.findById(mensagem.getIdRegistroBanco()).ifPresent(fila -> {
                fila.setStatus(status);
                filaRepository.save(fila);
            });
        }
    }

    private void registrarHistoricoCobranca(FilaCobranca fila) {
        HistoricoCobranca historico = new HistoricoCobranca();
        historico.setCliente(fila.getCliente());
        historico.setFuncionario(fila.getOperador());
        historico.setDataHora(LocalDateTime.now());
        historicoCobrancaRepository.save(historico);
    }
}