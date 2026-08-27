package com.raizestecnologia.relay.cobranca;

import com.raizestecnologia.relay.auth.ApiEnvelope;
import com.raizestecnologia.relay.auth.CurrentUser;
import com.raizestecnologia.relay.auth.RelayPrincipal;
import com.raizestecnologia.relay.loja.Loja;
import com.raizestecnologia.relay.loja.LojaService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Licença do próprio cliente (lojista): quando vence (dia 5) e se está ativa.
 * Usada no perfil ("Minha conta") do app — visível para qualquer usuário logado,
 * sobre a loja dele (X-Empresa ou a 1ª loja vinculada).
 */
@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class LicencaController {

    private static final ZoneId BRT = ZoneId.of("America/Sao_Paulo");
    private static final int DIA_COBRANCA = 5;

    private final LojaService lojas;
    private final CobrancaService cobrancas;

    public LicencaController(LojaService lojas, CobrancaService cobrancas) {
        this.lojas = lojas;
        this.cobrancas = cobrancas;
    }

    /** GET /api/licenca/lojas — lojas do cliente logado + a pendência de cada uma (pro cliente escolher). */
    @GetMapping("/licenca/lojas")
    public ResponseEntity<Map<String, Object>> minhasLojas() {
        RelayPrincipal p = CurrentUser.get();
        if (p == null || p.cnpjs() == null || p.cnpjs().isEmpty()) {
            return ResponseEntity.ok(ApiEnvelope.ok(java.util.List.of()));
        }
        return ResponseEntity.ok(ApiEnvelope.ok(cobrancas.pendencias(p.cnpjs())));
    }

    /** POST /api/licenca/pagar-lote  body: {"cnpjs":["...","..."]} — gera UM pagamento somando as lojas escolhidas. */
    @PostMapping("/licenca/pagar-lote")
    public ResponseEntity<Map<String, Object>> pagarLote(@RequestBody Map<String, Object> body) {
        RelayPrincipal p = CurrentUser.get();
        @SuppressWarnings("unchecked")
        java.util.List<String> pedidos = body != null && body.get("cnpjs") instanceof java.util.List
                ? (java.util.List<String>) body.get("cnpjs") : java.util.List.of();
        // só permite lojas do próprio usuário
        java.util.List<String> cnpjs = new java.util.ArrayList<>();
        for (String raw : pedidos) {
            String c = raw == null ? "" : raw.replaceAll("\\D", "");
            if (p != null && p.cnpjs() != null && p.cnpjs().contains(c)) cnpjs.add(c);
        }
        if (cnpjs.isEmpty()) return ResponseEntity.status(400).body(ApiEnvelope.fail("Selecione ao menos uma loja"));
        try {
            CobrancaService.Resultado r = cobrancas.gerarLote(cnpjs, p == null ? null : p.email());
            return ResponseEntity.ok(ApiEnvelope.ok(Map.of(
                    "linkPagamento", r.linkPagamento() == null ? "" : r.linkPagamento(),
                    "valor", r.valor(), "vencimento", r.vencimento(), "item", r.item())));
        } catch (IllegalStateException | IllegalArgumentException e) {
            return ResponseEntity.status(400).body(ApiEnvelope.fail(e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(502).body(ApiEnvelope.fail("Falha ao gerar pagamento: " + e.getMessage()));
        }
    }

    /** POST /api/licenca/pagar — o próprio cliente gera o link de pagamento da loja dele (Android). */
    @PostMapping("/licenca/pagar")
    public ResponseEntity<Map<String, Object>> pagar(@RequestHeader(value = "X-Empresa", required = false) String empresa) {
        RelayPrincipal p = CurrentUser.get();
        String cnpj = empresa == null ? null : empresa.replaceAll("\\D", "");
        if ((cnpj == null || cnpj.isBlank()) && p != null && p.cnpjs() != null && !p.cnpjs().isEmpty()) {
            cnpj = p.cnpjs().iterator().next();
        }
        if (cnpj == null || cnpj.isBlank()) return ResponseEntity.status(400).body(ApiEnvelope.fail("Sem loja vinculada"));
        try {
            CobrancaService.Resultado r = cobrancas.gerar(cnpj, p == null ? null : p.email());
            return ResponseEntity.ok(ApiEnvelope.ok(Map.of(
                    "linkPagamento", r.linkPagamento() == null ? "" : r.linkPagamento(),
                    "valor", r.valor(), "vencimento", r.vencimento(), "item", r.item())));
        } catch (IllegalStateException | IllegalArgumentException e) {
            return ResponseEntity.status(400).body(ApiEnvelope.fail(e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(502).body(ApiEnvelope.fail("Falha ao gerar pagamento: " + e.getMessage()));
        }
    }

    @GetMapping("/licenca")
    public ResponseEntity<Map<String, Object>> licenca(@RequestHeader(value = "X-Empresa", required = false) String empresa) {
        RelayPrincipal p = CurrentUser.get();
        String cnpj = empresa == null ? null : empresa.replaceAll("\\D", "");
        if ((cnpj == null || cnpj.isBlank()) && p != null && p.cnpjs() != null && !p.cnpjs().isEmpty()) {
            cnpj = p.cnpjs().iterator().next();
        }

        int diaVenc = cnpj == null ? 5 : lojas.diaVencimento(cnpj);
        LocalDate hoje = LocalDate.now(BRT);
        LocalDate vencimento = proximoVenc(hoje, diaVenc);
        long dias = ChronoUnit.DAYS.between(hoje, vencimento);
        boolean bloqueada = cnpj != null && lojas.estaBloqueada(cnpj);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("diaCobranca", diaVenc);
        out.put("vencimento", vencimento.toString());   // YYYY-MM-DD
        out.put("diasRestantes", (int) dias);
        out.put("ativa", !bloqueada);
        out.put("bloqueada", bloqueada);
        out.put("motivo", bloqueada ? lojas.motivo(cnpj) : "");
        // loja de revendedor: o pagamento é feito pela revenda; o app NÃO mostra a opção de pagar.
        boolean gerenciadoPorRevenda = cnpj != null &&
                lojas.obter(cnpj).map(l -> l.getRevendaCodigo() != null).orElse(false);
        out.put("gerenciadoPorRevenda", gerenciadoPorRevenda);
        return ResponseEntity.ok(ApiEnvelope.ok(out));
    }

    private static LocalDate proximoVenc(LocalDate from, int dia) {
        int d = Math.min(28, Math.max(1, dia));
        LocalDate dt = from.withDayOfMonth(d);
        if (dt.isBefore(from)) dt = from.plusMonths(1).withDayOfMonth(d);
        return dt;
    }
}
