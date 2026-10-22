package com.raizestecnologia.relay.notify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.raizestecnologia.relay.AgentHub;
import com.raizestecnologia.relay.auth.UserEmpresaRepository;
import com.raizestecnologia.relay.loja.LojaService;
import com.raizestecnologia.relay.push.DeviceToken;
import com.raizestecnologia.relay.push.DeviceTokenRepository;
import com.raizestecnologia.relay.push.FcmSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resumo da manhã (8h de Brasília) pros usuários do app de cada loja: contas a pagar e a receber
 * vencidas ou vencendo hoje, e produtos que acabam em até 3 dias. Um push só por loja, e só se tiver algo.
 * Pergunta ao agente pelas mesmas rotas do app; loja sem a rota (outros sistemas) só pula aquela parte.
 */
@Service
public class ResumoDiario {

    private static final Logger log = LoggerFactory.getLogger(ResumoDiario.class);
    private static final ZoneId BRT = ZoneId.of("America/Sao_Paulo");
    private static final DateTimeFormatter BR = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final int DIAS_ACABANDO = 3;

    private final AgentHub hub;
    private final UserEmpresaRepository vinculos;
    private final DeviceTokenRepository tokens;
    private final FcmSender fcm;
    private final LojaService lojas;
    private final ObjectMapper json = new ObjectMapper();
    /** Loja -> dia em que já mandou (não repete no mesmo dia). */
    private final Map<String, LocalDate> enviado = new ConcurrentHashMap<>();

    public ResumoDiario(AgentHub hub, UserEmpresaRepository vinculos, DeviceTokenRepository tokens,
                        FcmSender fcm, LojaService lojas) {
        this.hub = hub;
        this.vinculos = vinculos;
        this.tokens = tokens;
        this.fcm = fcm;
        this.lojas = lojas;
    }

    /** A cada 10 min; só age entre 8:00 e 8:40 (loja que conectar atrasada ainda recebe). */
    @Scheduled(fixedDelay = 10 * 60_000L, initialDelay = 3 * 60_000L)
    public void varrer() {
        if (!fcm.enabled()) return;
        LocalTime agora = LocalTime.now(BRT);
        if (agora.isBefore(LocalTime.of(8, 0)) || agora.isAfter(LocalTime.of(8, 40))) return;
        LocalDate hoje = LocalDate.now(BRT);
        for (AgentHub.Empresa e : hub.empresas()) {
            if (hoje.equals(enviado.get(e.cnpj()))) continue;
            try {
                if (enviar(e.cnpj(), hoje)) log.info("[resumo-dia] loja {} avisada", e.cnpj());
                enviado.put(e.cnpj(), hoje);
            } catch (Exception ex) {
                log.debug("[resumo-dia] loja {}: {}", e.cnpj(), ex.getMessage());
            }
        }
    }

    private boolean enviar(String cnpj, LocalDate hoje) {
        List<String> linhas = new ArrayList<>();
        JsonNode pagar = dados(cnpj, "/api/contas-pagar/titulos", null);
        if (pagar != null) contas(pagar, hoje, "A pagar", linhas);
        JsonNode receber = dados(cnpj, "/api/contas-receber", "tipo=titulos");
        if (receber != null) contas(receber.path("titulos"), hoje, "A receber", linhas);
        JsonNode repo = dados(cnpj, "/api/produtos/reposicao", "horizonte=" + DIAS_ACABANDO + "&janela=30");
        if (repo != null) {
            int n = 0;
            for (JsonNode p : repo.path("itens")) if (p.path("diasRestantes").asInt(99) <= DIAS_ACABANDO) n++;
            if (n > 0) linhas.add(n == 1 ? "1 produto acabando" : n + " produtos acabando");
        }
        if (linhas.isEmpty()) return false;

        List<Long> ids = new ArrayList<>();
        for (Object[] v : vinculos.resumoPorCnpj(cnpj)) {
            if (Boolean.TRUE.equals(v[3]) && !ids.contains((Long) v[0])) ids.add((Long) v[0]);
        }
        if (ids.isEmpty()) return false;
        List<String> toks = tokens.findByUserIdIn(ids).stream().map(DeviceToken::getToken).toList();
        if (toks.isEmpty()) return false;
        String nome = lojas.conhecidas().getOrDefault(cnpj, "");
        fcm.sendToTokens(toks, "Bom dia" + (nome.isBlank() ? "" : " · " + nome), String.join("\n", linhas),
                Map.of("tipo", "resumo_dia", "cnpj", cnpj));
        return true;
    }

    /** "A pagar: 2 vencidas (R$ 340,00) · 1 vence hoje (R$ 90,00)" */
    private void contas(JsonNode lista, LocalDate hoje, String rotulo, List<String> linhas) {
        int nVenc = 0, nHoje = 0;
        double vVenc = 0, vHoje = 0;
        for (JsonNode t : lista) {
            if (t.path("pago").asBoolean(false)) continue;
            LocalDate venc;
            try {
                venc = LocalDate.parse(t.path("venc").asText("").trim(), BR);
            } catch (Exception e) {
                continue;
            }
            double valor = valor(t.path("valor").asText(""));
            if (venc.isBefore(hoje)) {
                nVenc++;
                vVenc += valor;
            } else if (venc.isEqual(hoje)) {
                nHoje++;
                vHoje += valor;
            }
        }
        if (nVenc == 0 && nHoje == 0) return;
        List<String> partes = new ArrayList<>();
        if (nVenc > 0) partes.add(nVenc + (nVenc == 1 ? " vencida (" : " vencidas (") + reais(vVenc) + ")");
        if (nHoje > 0) partes.add(nHoje + (nHoje == 1 ? " vence hoje (" : " vencem hoje (") + reais(vHoje) + ")");
        linhas.add(rotulo + ": " + String.join(" · ", partes));
    }

    private JsonNode dados(String cnpj, String path, String query) {
        try {
            AgentHub.Resposta r = hub.ask(cnpj, "GET", path, query, null);
            if (r.status() != 200) return null;
            JsonNode d = json.readTree(r.body()).path("data");
            return d.isMissingNode() || d.isNull() ? null : d;
        } catch (Exception e) {
            return null;
        }
    }

    /** "R$ 1.234,56" -> 1234.56 */
    static double valor(String s) {
        String t = s.replaceAll("[^0-9,.-]", "");
        if (t.isEmpty()) return 0;
        if (t.contains(",")) t = t.replace(".", "").replace(",", ".");
        try {
            return Double.parseDouble(t);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    static String reais(double v) {
        return "R$ " + String.format(Locale.forLanguageTag("pt-BR"), "%,.2f", v);
    }
}
