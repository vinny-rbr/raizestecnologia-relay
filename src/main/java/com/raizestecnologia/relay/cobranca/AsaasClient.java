package com.raizestecnologia.relay.cobranca;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Cliente da API do Asaas (cobranca dos lojistas). Chave via ASAAS_API_KEY.
 * Sandbox: https://sandbox.asaas.com/api/v3 | Producao: https://api.asaas.com/api/v3
 */
@Service
public class AsaasClient {

    private static final Logger log = LoggerFactory.getLogger(AsaasClient.class);

    private final String baseUrl;
    private final String apiKey;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    private final ObjectMapper json = new ObjectMapper();

    public AsaasClient(@Value("${asaas.base-url}") String baseUrl,
                       @Value("${asaas.api-key:}") String apiKey) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
    }

    /** true se a chave está configurada (senão a cobrança fica desabilitada). */
    public boolean enabled() {
        return !apiKey.isBlank();
    }

    public String baseUrl() { return baseUrl; }

    /** Nome da conta Asaas ativa (só leitura) — pra confirmar sandbox x produção. */
    public String contaNome() throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/myAccount"))
                .header("access_token", apiKey).timeout(Duration.ofSeconds(20)).GET().build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        JsonNode node = json.readTree(res.body() == null || res.body().isBlank() ? "{}" : res.body());
        return node.path("name").asText(null);
    }

    /** Cria (ou reaproveita) o cliente no Asaas e devolve o id (cus_...). */
    public String criarCliente(String nome, String cpfCnpj, String email) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", nome == null || nome.isBlank() ? "Cliente" : nome);
        body.put("cpfCnpj", onlyDigits(cpfCnpj));
        if (email != null && !email.isBlank()) body.put("email", email);
        JsonNode r = post("/customers", body);
        return r.path("id").asText(null);
    }

    /** Cria uma cobrança avulsa (cliente escolhe Pix/boleto/cartão). Devolve id + link. */
    public Cobranca criarCobranca(String customerId, double valor, LocalDate vencimento, String descricao) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("customer", customerId);
        body.put("billingType", "UNDEFINED"); // deixa o cliente escolher a forma
        body.put("value", round2(valor));
        body.put("dueDate", vencimento.toString());
        body.put("description", descricao);
        JsonNode r = post("/payments", body);
        return new Cobranca(r.path("id").asText(null), r.path("invoiceUrl").asText(null),
                r.path("status").asText(null), round2(valor), vencimento.toString());
    }

    /** Cria a assinatura mensal (recorrência automática todo mês). Devolve o id (sub_...). */
    public String criarAssinatura(String customerId, double valor, LocalDate proximoVencimento, String descricao) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("customer", customerId);
        body.put("billingType", "UNDEFINED");
        body.put("value", round2(valor));
        body.put("nextDueDate", proximoVencimento.toString());
        body.put("cycle", "MONTHLY");
        body.put("description", descricao);
        JsonNode r = post("/subscriptions", body);
        return r.path("id").asText(null);
    }

    // ---- HTTP ----

    private JsonNode post(String path, Map<String, Object> body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("access_token", apiKey)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                .build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        JsonNode node = json.readTree(res.body() == null || res.body().isBlank() ? "{}" : res.body());
        if (res.statusCode() >= 300) {
            String msg = node.path("errors").path(0).path("description").asText(res.body());
            log.warn("[asaas] POST {} -> {} : {}", path, res.statusCode(), msg);
            throw new AsaasException(msg);
        }
        return node;
    }

    private static String onlyDigits(String s) {
        return s == null ? "" : s.replaceAll("\\D", "");
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    /** Resultado de uma cobrança criada. */
    public record Cobranca(String id, String linkPagamento, String status, double valor, String vencimento) {}

    public static class AsaasException extends RuntimeException {
        public AsaasException(String m) { super(m); }
    }
}
