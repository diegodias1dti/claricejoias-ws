package br.com.claricejoias_ws.service;

import br.com.claricejoias_ws.model.ItemPedido;
import br.com.claricejoias_ws.model.Pedido;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Integração com o Mercado Pago no modo Checkout Pro: o cliente é redirecionado para
 * a página hospedada do Mercado Pago (que já mostra Pix, cartão etc.) em vez de a gente
 * lidar com dado de cartão no nosso servidor.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MercadoPagoService {

    private static final String API_URL = "https://api.mercadopago.com";

    @Value("${mercadopago.access-token}")
    private String accessToken;

    @Value("${app.frontend.url}")
    private String frontendUrl;

    @Value("${app.public.url}")
    private String publicUrl;

    private final ObjectMapper objectMapper;

    // Timeouts explícitos: sem isso, uma Mercado Pago lenta prenderia a requisição
    // de checkout do cliente indefinidamente.
    private final RestTemplate restTemplate = criarRestTemplateComTimeout();

    private static RestTemplate criarRestTemplateComTimeout() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(10000);
        return new RestTemplate(factory);
    }

    /**
     * Cria uma preferência de pagamento para o pedido e devolve a URL (init_point) para
     * onde o cliente deve ser redirecionado. Grava o preferenceId no próprio pedido
     * (quem chama é responsável por persistir o pedido depois).
     */
    public String criarPreferencia(Pedido pedido) {
        if (accessToken == null || accessToken.isBlank()) {
            throw new IllegalStateException("MERCADOPAGO_ACCESS_TOKEN não configurado no servidor.");
        }

        List<Map<String, Object>> itens = new ArrayList<>();
        for (ItemPedido item : pedido.getItens()) {
            itens.add(Map.of(
                    "title", item.getProduto().getNome(),
                    "quantity", item.getQuantidade(),
                    "unit_price", item.getPrecoUnitario().doubleValue(),
                    "currency_id", "BRL"
            ));
        }

        String baseFrontend = frontendUrl.endsWith("/") ? frontendUrl.substring(0, frontendUrl.length() - 1) : frontendUrl;
        String baseBackend = publicUrl.endsWith("/") ? publicUrl.substring(0, publicUrl.length() - 1) : publicUrl;

        Map<String, Object> body = new HashMap<>();
        body.put("items", itens);
        body.put("external_reference", String.valueOf(pedido.getId()));
        body.put("notification_url", baseBackend + "/api/pagamentos/mercadopago/webhook");
        body.put("back_urls", Map.of(
                "success", baseFrontend + "/checkout/retorno?status=success",
                "pending", baseFrontend + "/checkout/retorno?status=pending",
                "failure", baseFrontend + "/checkout/retorno?status=failure"
        ));
        body.put("auto_return", "approved");

        try {
            ResponseEntity<String> response = restTemplate.exchange(
                    API_URL + "/checkout/preferences", HttpMethod.POST,
                    new HttpEntity<>(body, getHeaders()), String.class
            );

            JsonNode json = objectMapper.readTree(response.getBody());
            String preferenceId = json.path("id").asText(null);
            String initPoint = json.path("init_point").asText(null);

            if (initPoint == null) {
                log.error("Mercado Pago não devolveu init_point para o pedido {}. Resposta: {}", pedido.getId(), response.getBody());
                throw new IllegalStateException("Não foi possível gerar o link de pagamento.");
            }

            pedido.setMercadoPagoPreferenceId(preferenceId);
            return initPoint;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            log.error("Erro ao criar preferência no Mercado Pago para o pedido {}: {}", pedido.getId(), e.getMessage());
            throw new IllegalStateException("Erro ao comunicar com o Mercado Pago. Tente novamente em instantes.", e);
        }
    }

    /**
     * Consulta o status real de um pagamento direto na API do Mercado Pago. Nunca
     * confiamos apenas no corpo do webhook — ele só nos avisa QUE algo mudou, quem
     * confirma O QUE mudou é sempre essa consulta autenticada.
     */
    public PagamentoConsultado consultarPagamento(String paymentId) {
        try {
            ResponseEntity<String> response = restTemplate.exchange(
                    API_URL + "/v1/payments/" + paymentId, HttpMethod.GET,
                    new HttpEntity<>(getHeaders()), String.class
            );

            JsonNode json = objectMapper.readTree(response.getBody());
            return new PagamentoConsultado(
                    json.path("id").asText(null),
                    json.path("status").asText(null), // approved, rejected, pending, cancelled, refunded...
                    json.path("external_reference").asText(null)
            );
        } catch (Exception e) {
            log.error("Erro ao consultar pagamento {} no Mercado Pago: {}", paymentId, e.getMessage());
            throw new IllegalStateException("Erro ao consultar pagamento no Mercado Pago.", e);
        }
    }

    private HttpHeaders getHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(accessToken);
        return headers;
    }

    public record PagamentoConsultado(String paymentId, String status, String externalReference) {
    }
}
