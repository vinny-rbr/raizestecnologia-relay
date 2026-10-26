package com.raizestecnologia.relay.saude;

import com.fasterxml.jackson.databind.JsonNode;
import com.raizestecnologia.relay.auth.AppUser;
import com.raizestecnologia.relay.auth.AppUserRepository;
import com.raizestecnologia.relay.loja.LojaService;
import com.raizestecnologia.relay.push.DeviceToken;
import com.raizestecnologia.relay.push.DeviceTokenRepository;
import com.raizestecnologia.relay.push.FcmSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Recebe os eventos de rede que os agentes mandam (saúde das lojas), guarda no histórico e avisa o master (DONO)
 * por push. O mesmo problema (mesma chave) só gera push de novo depois de 5 min; "voltou ao normal" sempre avisa.
 */
@Service
public class SaudeService {

    private static final Logger log = LoggerFactory.getLogger(SaudeService.class);

    private final EventoLojaRepository repo;
    private final LojaService lojas;
    private final AppUserRepository users;
    private final DeviceTokenRepository tokens;
    private final FcmSender fcm;
    private final org.springframework.beans.factory.ObjectProvider<com.raizestecnologia.relay.AgentHub> hub;

    public SaudeService(EventoLojaRepository repo, LojaService lojas, AppUserRepository users, DeviceTokenRepository tokens, FcmSender fcm,
                        org.springframework.beans.factory.ObjectProvider<com.raizestecnologia.relay.AgentHub> hub) {
        this.hub = hub;
        this.repo = repo;
        this.lojas = lojas;
        this.users = users;
        this.tokens = tokens;
        this.fcm = fcm;
    }

    private static String corta(String s, int n) {
        if (s == null) return null;
        s = s.trim();
        return s.length() > n ? s.substring(0, n) : s;
    }

    /** Evento gerado pela própria central (ex.: computador da loja ficou offline). */
    public void registrarCentral(String loja, String tipo, String nivel, String titulo, String detalhe, String chave) {
        com.fasterxml.jackson.databind.node.ObjectNode n = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        n.put("tipo", tipo);
        n.put("nivel", nivel);
        n.put("titulo", titulo);
        n.put("detalhe", detalhe);
        n.put("chave", chave);
        registrar(loja, n);
    }

    public void registrar(String loja, JsonNode n) {
        try {
            String titulo = corta(n.path("titulo").asText(""), 200);
            if (loja == null || loja.isBlank() || titulo.isBlank()) return;
            EventoLoja e = new EventoLoja();
            e.setLoja(loja);
            e.setLojaNome(corta(lojas.conhecidas().getOrDefault(loja, ""), 120));
            e.setTipo(corta(n.path("tipo").asText("outro"), 30));
            String nivel = n.path("nivel").asText("aviso");
            e.setNivel(List.of("grave", "aviso", "ok").contains(nivel) ? nivel : "aviso");
            e.setTitulo(titulo);
            e.setDetalhe(corta(n.path("detalhe").asText(""), 1000));
            String chave = corta(n.path("chave").asText(e.getTipo() + ":" + titulo), 200);
            e.setChave(chave);
            // impressora de etiqueta liga/desliga toda hora (pedido do Lucas, 20/10/2026): so na lista, sem push
            boolean semPush = e.getTipo().startsWith("etiquetadora");
            var ultimo = repo.findTopByLojaAndChaveOrderByCriadoEmDesc(loja, chave);
            boolean aberto = ultimo.isPresent() && !"ok".equals(ultimo.get().getNivel());
            if (!"ok".equals(e.getNivel())) {
                // problema que já está aberto: não grava de novo nem avisa (pedido do Lucas, 26/10/2026: avisa uma vez
                // só e fica nos Pendentes até resolver). Só atualiza o detalhe (ex.: "offline há 40 min").
                if (aberto) {
                    EventoLoja u = ultimo.get();
                    u.setDetalhe(e.getDetalhe());
                    u.setVistoEm(Instant.now());
                    repo.save(u);
                    return;
                }
                // das 23h as 8h registra sem push; o avisarPendentes() manda depois das 8h se ainda estiver aberto
                boolean avisa = !silencio() && !semPush && !emResolucao(loja, chave);
                e.setPush(avisa);
                e.setVistoEm(e.getCriadoEm());
                repo.save(e);
                if (avisa) avisarMaster(e);
            } else {
                // voltou ao normal: avisa só se o problema tinha sido avisado
                boolean avisa = aberto && ultimo.get().isPush() && !semPush;
                e.setPush(avisa);
                repo.save(e);
                if (avisa) avisarMaster(e);
            }
        } catch (Exception ex) {
            log.warn("[saude] evento da loja {} não registrado: {}", loja, ex.getMessage());
        }
    }

    /** Problemas que aparecem nos Pendentes: os que se resolvem (offline, impressora, etiquetadora) e os graves. */
    public static boolean pendencia(EventoLoja e) {
        String t = e.getTipo() == null ? "" : e.getTipo();
        return "grave".equals(e.getNivel()) || t.equals("agente_off") || t.endsWith("_fora");
    }

    /**
     * A cada 5 min fecha sozinho o que já passou (sem push): loja dada como offline que está online agora, e
     * impressora/etiquetadora "fora" que o agente parou de relatar há 30 min (ele repete enquanto continua fora).
     */
    @org.springframework.scheduling.annotation.Scheduled(initialDelay = 180_000, fixedDelay = 300_000)
    public void fecharResolvidos() {
        try {
            com.raizestecnologia.relay.AgentHub h = hub.getIfAvailable();
            java.util.Map<String, EventoLoja> ultimo = new java.util.LinkedHashMap<>();
            for (EventoLoja e : repo.findByCriadoEmAfterOrderByCriadoEmAsc(Instant.now().minus(Duration.ofDays(30))))
                ultimo.put(e.getLoja() + "|" + e.getChave(), e);
            Instant limite = Instant.now().minus(Duration.ofMinutes(30));
            for (EventoLoja e : ultimo.values()) {
                if ("ok".equals(e.getNivel())) continue;
                String t = e.getTipo() == null ? "" : e.getTipo();
                Instant visto = e.getVistoEm() != null ? e.getVistoEm() : e.getCriadoEm();
                String motivo = null;
                if (t.equals("agente_off") && h != null && h.online(e.getLoja())) motivo = "o agente está conectado de novo";
                else if (t.endsWith("_fora") && visto.isBefore(limite)) motivo = "parou de acontecer";
                if (motivo == null) continue;
                EventoLoja ok = new EventoLoja();
                ok.setLoja(e.getLoja());
                ok.setLojaNome(e.getLojaNome());
                ok.setTipo(e.getTipo());
                ok.setNivel("ok");
                ok.setTitulo(t.equals("agente_off") ? "Computador da loja voltou" : "Normalizou");
                ok.setDetalhe(e.getTitulo() + ": " + motivo);
                ok.setChave(e.getChave());
                ok.setPush(false);
                repo.save(ok);
            }
        } catch (Exception ex) {
            log.warn("[saude] limpeza dos pendentes: {}", ex.getMessage());
        }
    }

    /** Problema que começou no silêncio (23h-8h) e continua aberto: avisa uma vez depois das 8h. */
    @org.springframework.scheduling.annotation.Scheduled(initialDelay = 120_000, fixedDelay = 300_000)
    public void avisarPendentes() {
        if (silencio()) return;
        try {
            java.util.Map<String, EventoLoja> ultimo = new java.util.LinkedHashMap<>();
            for (EventoLoja e : repo.findByCriadoEmAfterOrderByCriadoEmAsc(Instant.now().minus(Duration.ofHours(12))))
                ultimo.put(e.getLoja() + "|" + e.getChave(), e);
            for (EventoLoja e : ultimo.values()) {
                if ("ok".equals(e.getNivel()) || e.isPush() || e.getTipo().startsWith("etiquetadora")) continue;
                if (emResolucao(e.getLoja(), e.getChave())) continue;
                e.setPush(true);
                repo.save(e);
                avisarMaster(e);
            }
        } catch (Exception ex) {
            log.warn("[saude] pendentes da madrugada: {}", ex.getMessage());
        }
    }

    private static final java.time.ZoneId BRT = java.time.ZoneId.of("America/Sao_Paulo");

    /** Horario de silencio dos avisos de saude pro master: das 23h as 8h (Brasilia). */
    static boolean silencio() {
        int h = java.time.ZonedDateTime.now(BRT).getHour();
        return h >= 23 || h < 8;
    }

    /** Alguém marcou "estou resolvendo" depois do último "voltou ao normal" desse problema: não avisa de novo. */
    public boolean emResolucao(String loja, String chave) {
        var marcado = repo.findTopByLojaAndChaveAndResolvendoTrueOrderByResolvendoEmDesc(loja, chave);
        if (marcado.isEmpty()) return false;
        var ok = repo.findTopByLojaAndChaveAndNivelOrderByCriadoEmDesc(loja, chave, "ok");
        return ok.isEmpty() || ok.get().getCriadoEm().isBefore(marcado.get().getResolvendoEm());
    }

    private void avisarMaster(EventoLoja e) {
        if (!fcm.enabled()) return;
        List<Long> donos = users.findByRoleIgnoreCaseAndAtivoTrue("DONO").stream().map(AppUser::getId).toList();
        if (donos.isEmpty()) return;
        List<String> toks = tokens.findByUserIdIn(donos).stream().map(DeviceToken::getToken).toList();
        String loja = e.getLojaNome() == null || e.getLojaNome().isBlank() ? e.getLoja() : e.getLojaNome();
        String icone = "grave".equals(e.getNivel()) ? "🔴 " : "ok".equals(e.getNivel()) ? "🟢 " : "🟠 ";
        fcm.sendToTokens(toks, icone + loja + ": " + e.getTitulo(), e.getDetalhe() == null ? "" : e.getDetalhe(),
                Map.of("tipo", "saude", "loja", e.getLoja()));
    }
}
