package com.raizestecnologia.relay.cobranca;

import com.raizestecnologia.relay.auth.ApiEnvelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Cobrança do lojista (Asaas). O endpoint de gerar é do master (sob /api/admin, exige JWT);
 * o webhook é público (o Asaas chama /webhooks/asaas quando o pagamento muda de status).
 */
@RestController
@CrossOrigin(origins = "*")
public class CobrancaController {

    private static final Logger log = LoggerFactory.getLogger(CobrancaController.class);
    private final CobrancaService cobranca;

    public CobrancaController(CobrancaService cobranca) {
        this.cobranca = cobranca;
    }

    /** POST /api/admin/lojas/{cnpj}/cobranca  body opcional: {"email":"cliente@..."} — gera a fatura do item pendente + assinatura. */
    @PostMapping("/api/admin/lojas/{cnpj}/cobranca")
    public ResponseEntity<Map<String, Object>> gerar(@PathVariable String cnpj,
                                                     @RequestBody(required = false) Map<String, String> body) {
        String email = body == null ? null : body.get("email");
        try {
            CobrancaService.Resultado r = cobranca.gerar(cnpj, email);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("linkPagamento", r.linkPagamento());
            out.put("valor", r.valor());
            out.put("vencimento", r.vencimento());
            out.put("item", r.item());
            out.put("assinaturaCriada", r.assinaturaCriada());
            return ResponseEntity.ok(ApiEnvelope.ok(out));
        } catch (IllegalStateException | IllegalArgumentException e) {
            return ResponseEntity.status(400).body(ApiEnvelope.fail(e.getMessage()));
        } catch (Exception e) {
            log.warn("[cobranca] falha ao gerar cobranca da loja {}: {}", cnpj, e.getMessage());
            return ResponseEntity.status(502).body(ApiEnvelope.fail("Falha ao gerar cobrança: " + e.getMessage()));
        }
    }

    /** POST /api/admin/lojas/{cnpj}/implantacao/paga — marca implantação paga (Pix/dinheiro) e libera. */
    @PostMapping("/api/admin/lojas/{cnpj}/implantacao/paga")
    public ResponseEntity<Map<String, Object>> implantacaoPaga(@PathVariable String cnpj) {
        try {
            cobranca.marcarImplantacaoPaga(cnpj, true);
            return ResponseEntity.ok(ApiEnvelope.ok(Map.of("cnpj", cnpj, "implantacaoPaga", true)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(400).body(ApiEnvelope.fail(e.getMessage()));
        }
    }

    /** POST /api/admin/lojas/{cnpj}/mensalidade/paga — marca a mensalidade atual paga (Pix/dinheiro) e libera. */
    @PostMapping("/api/admin/lojas/{cnpj}/mensalidade/paga")
    public ResponseEntity<Map<String, Object>> mensalidadePaga(@PathVariable String cnpj) {
        try {
            cobranca.marcarMensalidadePaga(cnpj, true);
            return ResponseEntity.ok(ApiEnvelope.ok(Map.of("cnpj", cnpj, "mensalidadePaga", true)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(400).body(ApiEnvelope.fail(e.getMessage()));
        }
    }

    /** POST /webhooks/asaas — o Asaas notifica mudanças de pagamento (público). */
    @PostMapping("/webhooks/asaas")
    public ResponseEntity<Map<String, Object>> webhook(@RequestBody Map<String, Object> payload) {
        try {
            String event = String.valueOf(payload.get("event"));
            Object pObj = payload.get("payment");
            String customer = null, status = null;
            if (pObj instanceof Map<?, ?> p) {
                customer = p.get("customer") == null ? null : String.valueOf(p.get("customer"));
                status = p.get("status") == null ? null : String.valueOf(p.get("status"));
            }
            log.info("[asaas-webhook] event={} status={} customer={}", event, status, customer);
            // Pagamento efetivado: confirma e desbloqueia a loja.
            if ("PAYMENT_CONFIRMED".equals(event) || "PAYMENT_RECEIVED".equals(event)
                    || "CONFIRMED".equals(status) || "RECEIVED".equals(status)) {
                cobranca.confirmarPagamentoAsaas(customer);
            }
        } catch (Exception e) {
            log.warn("[asaas-webhook] erro ao processar: {}", e.getMessage());
        }
        // Sempre 200: o Asaas re-tenta em caso de erro; evita fila de reentrega.
        return ResponseEntity.ok(Map.of("received", true));
    }
}
