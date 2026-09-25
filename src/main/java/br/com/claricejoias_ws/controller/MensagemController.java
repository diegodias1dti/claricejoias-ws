package br.com.claricejoias_ws.controller;

import br.com.claricejoias_ws.dto.LeadDTO;
import br.com.claricejoias_ws.model.Lead;
import br.com.claricejoias_ws.model.Revendedor;
import br.com.claricejoias_ws.service.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.modelmapper.ModelMapper;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/mensagens")
@RequiredArgsConstructor
@Slf4j
public class MensagemController {

    private final WhatsAppService whatsAppService;
    private final LeadService leadService;
    private final AutenticacaoService autenticacaoService;
    private final MinioService minioService;
    private final ModelMapper modelMapper;

    @PostMapping("/disparar")
    public ResponseEntity<String> dispararMensagem(@RequestBody MensagemRequestDTO request) {
        Lead leadEntity = leadService.buscarEntidadePorId(request.leadId());
        LeadDTO lead = leadService.buscarPorId(request.leadId());
        String operador = autenticacaoService.getUsername();

        // A mensagem deve sair pelo WhatsApp da revendedora dona do lead, não pelo
        // WhatsApp de quem clicou em "disparar" no painel. Lead sem revendedor
        // (loja matriz) cai no fallback global dentro de WhatsAppService.
        Revendedor revendedor = leadEntity.getRevendedor();

        // 1. Monta o texto
        StringBuilder textoIntro = new StringBuilder();
        textoIntro.append("Olá, ").append(lead.getNome()).append(". ");
        textoIntro.append("Aqui é da equipe de atendimento da Clarice Joias.\n\n");

        if (lead.getItens() != null && !lead.getItens().isEmpty()) {
            textoIntro.append("Notamos o seu excelente gosto por nossas peças e vimos que alguns itens estão aguardando em seu carrinho!\n");
        } else {
            textoIntro.append("Vimos que você demonstrou interesse em nossas coleções.\n");
        }
        textoIntro.append("Temos uma condição exclusiva liberada para você finalizar seu pedido hoje. Gostaria de conferir as opções?");

        // 2. Dispara a mensagem (Passando a instância)
        whatsAppService.enviarMensagemTexto(lead, textoIntro.toString(), operador, revendedor);

        if (lead.getItens() != null && !lead.getItens().isEmpty()) {
            for (var item : lead.getItens()) {
                var produto = item.getProduto();

                if (produto.getImagens() != null && !produto.getImagens().isEmpty()) {
                    try {
                        String objectName = produto.getImagens().get(0);
                        String path = produto.getImagens().get(0);
                        String legendaDaFoto = "💍 *" + produto.getNome() + "*";

                        // Chama o envio de mídia (Passando a instância)
                        whatsAppService.enviarMensagemImagem(modelMapper.map(lead, Lead.class), legendaDaFoto, path, operador, revendedor);

                    } catch (Exception e) {
                        log.error("Erro ao agendar imagem para o lead {}", lead.getId(), e);
                    }
                } else {
                    String textoSemFoto = "💍 *" + produto.getNome() + "* (Imagem indisponível)";
                    // Passando a instância
                    whatsAppService.enviarMensagemTexto(lead, textoSemFoto, operador, revendedor);
                }
            }
        }

        return ResponseEntity.ok("Mensagem enfileirada com sucesso para " + lead.getNome());
    }

    public record MensagemRequestDTO(Long leadId) {}
}