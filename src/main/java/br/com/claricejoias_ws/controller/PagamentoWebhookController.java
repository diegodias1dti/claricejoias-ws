package br.com.claricejoias_ws.controller;

import br.com.claricejoias_ws.service.MercadoPagoService;
import br.com.claricejoias_ws.service.PedidoService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/pagamentos/mercadopago")
@RequiredArgsConstructor
public class PagamentoWebhookController {

    private final MercadoPagoService mercadoPagoService;
    private final PedidoService pedidoService;

    /**
     * O Mercado Pago chama essa rota direto (sem token nosso) sempre que o status de um
     * pagamento muda. Respondemos 200 rápido e SEMPRE reconsultamos o pagamento na API
     * deles antes de confiar em qualquer dado — nunca no corpo da notificação em si, que
     * poderia ser forjado por qualquer um que descubra essa URL.
     */
    @PostMapping("/webhook")
    public ResponseEntity<Void> receberWebhook(
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String topic,
            @RequestParam(required = false, name = "data.id") String dataIdParam,
            @RequestParam(required = false) String id,
            @RequestBody(required = false) Map<String, Object> body) {

        try {
            String paymentId = extrairPaymentId(dataIdParam, id, body);
            String tipo = type != null ? type : topic;

            if (paymentId == null || (tipo != null && !"payment".equalsIgnoreCase(tipo))) {
                // O Mercado Pago também manda notificações de outros tipos (merchant_order
                // etc.) que não nos interessam aqui — só confirmamos e ignoramos.
                return ResponseEntity.ok().build();
            }

            MercadoPagoService.PagamentoConsultado pagamento = mercadoPagoService.consultarPagamento(paymentId);
            pedidoService.confirmarPagamentoOnline(pagamento.paymentId(), pagamento.externalReference(), pagamento.status());

        } catch (Exception e) {
            // Nunca deixamos uma exceção virar 500 aqui: o Mercado Pago reencaminha
            // agressivamente notificações que falham, e um erro nosso não pode virar
            // um loop de retentativas sem fim.
            log.error("Erro ao processar webhook do Mercado Pago", e);
        }

        return ResponseEntity.ok().build();
    }

    @SuppressWarnings("unchecked")
    private String extrairPaymentId(String dataIdParam, String idParam, Map<String, Object> body) {
        if (dataIdParam != null && !dataIdParam.isBlank()) return dataIdParam;
        if (idParam != null && !idParam.isBlank()) return idParam;
        if (body != null) {
            Object data = body.get("data");
            if (data instanceof Map) {
                Object dataId = ((Map<String, Object>) data).get("id");
                if (dataId != null) return String.valueOf(dataId);
            }
        }
        return null;
    }
}
