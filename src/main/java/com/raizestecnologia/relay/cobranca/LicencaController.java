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

        LocalDate hoje = LocalDate.now(BRT);
        LocalDate vencimento = proximoDia5(hoje);
        long dias = ChronoUnit.DAYS.between(hoje, vencimento);
        boolean bloqueada = cnpj != null && lojas.estaBloqueada(cnpj);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("diaCobranca", DIA_COBRANCA);
        out.put("vencimento", vencimento.toString());   // YYYY-MM-DD
        out.put("diasRestantes", (int) dias);
        out.put("ativa", !bloqueada);
        out.put("bloqueada", bloqueada);
        out.put("motivo", bloqueada ? lojas.motivo(cnpj) : "");
        return ResponseEntity.ok(ApiEnvelope.ok(out));
    }

    private static LocalDate proximoDia5(LocalDate from) {
        LocalDate d = from.withDayOfMonth(DIA_COBRANCA);
        if (d.isBefore(from)) d = from.plusMonths(1).withDayOfMonth(DIA_COBRANCA);
        return d;
    }
}
