package com.raizestecnologia.relay.saude;

import com.raizestecnologia.relay.auth.ApiEnvelope;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** GET /api/admin/saude — histórico de eventos de rede das lojas (só o master/DONO: /api/admin/**). */
@RestController
@RequestMapping("/api/admin/saude")
public class SaudeController {

    private final EventoLojaRepository repo;
    private final com.raizestecnologia.relay.auth.AppUserRepository users;

    public SaudeController(EventoLojaRepository repo, com.raizestecnologia.relay.auth.AppUserRepository users) {
        this.repo = repo;
        this.users = users;
    }

    /** POST /api/admin/saude/{id}/resolvendo {resolvendo:true|false} — "estou resolvendo": para os avisos desse problema. */
    @org.springframework.web.bind.annotation.PostMapping("/{id}/resolvendo")
    public ResponseEntity<Map<String, Object>> resolvendo(@org.springframework.web.bind.annotation.PathVariable Long id,
                                                          @org.springframework.web.bind.annotation.RequestBody(required = false) Map<String, Object> body) {
        EventoLoja e = repo.findById(id).orElse(null);
        if (e == null) return ResponseEntity.status(404).body(ApiEnvelope.fail("Evento não encontrado"));
        boolean marcar = body == null || !Boolean.FALSE.equals(body.get("resolvendo"));
        var p = com.raizestecnologia.relay.auth.CurrentUser.get();
        String quem = p == null ? "" : p.email();
        try {
            var u = p == null ? null : users.findById(Long.valueOf(p.userId())).orElse(null);
            if (u != null && u.getNome() != null && !u.getNome().isBlank()) quem = u.getNome();
        } catch (Exception ignored) {
        }
        e.setResolvendo(marcar);
        e.setResolvendoPor(marcar ? quem : null);
        e.setResolvendoEm(marcar ? java.time.Instant.now() : null);
        repo.save(e);
        return ResponseEntity.ok(ApiEnvelope.ok(Map.of("id", id, "resolvendo", marcar)));
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> lista(@RequestParam(required = false) String loja,
                                                     @RequestParam(defaultValue = "150") int limite) {
        PageRequest p = PageRequest.of(0, Math.max(1, Math.min(500, limite)));
        List<EventoLoja> ev = loja == null || loja.isBlank() ? repo.findByOrderByCriadoEmDesc(p)
                : repo.findByLojaOrderByCriadoEmDesc(loja.replaceAll("\\D", ""), p);
        // situação de cada problema: resolvido (veio "voltou" depois) / resolvendo (alguém marcou)
        Map<String, java.time.Instant> okMaisRecente = new java.util.HashMap<>();
        Map<String, EventoLoja> marcado = new java.util.HashMap<>();
        for (EventoLoja e : ev) {
            String k = e.getLoja() + "|" + e.getChave();
            if ("ok".equals(e.getNivel())) okMaisRecente.merge(k, e.getCriadoEm(), (a, b) -> a.isAfter(b) ? a : b);
            if (e.isResolvendo()) marcado.merge(k, e, (a, b) -> a.getResolvendoEm().isAfter(b.getResolvendoEm()) ? a : b);
        }
        List<Map<String, Object>> out = ev.stream().map(e -> {
            Map<String, Object> m = new LinkedHashMap<>();
            String k = e.getLoja() + "|" + e.getChave();
            java.time.Instant ok = okMaisRecente.get(k);
            boolean resolvido = !"ok".equals(e.getNivel()) && ok != null && ok.isAfter(e.getCriadoEm());
            EventoLoja mk = marcado.get(k);
            boolean resolvendo = !resolvido && !"ok".equals(e.getNivel()) && mk != null && (ok == null || ok.isBefore(mk.getResolvendoEm()));
            m.put("resolvido", resolvido);
            m.put("resolvendo", resolvendo);
            m.put("resolvendoPor", resolvendo ? mk.getResolvendoPor() : null);
            m.put("resolvendoEm", resolvendo ? mk.getResolvendoEm().toString() : null);
            m.put("id", e.getId());
            m.put("loja", e.getLoja());
            m.put("lojaNome", e.getLojaNome());
            m.put("tipo", e.getTipo());
            m.put("nivel", e.getNivel());
            m.put("titulo", e.getTitulo());
            m.put("detalhe", e.getDetalhe());
            m.put("avisou", e.isPush());
            m.put("criadoEm", e.getCriadoEm().toString());
            return m;
        }).toList();
        return ResponseEntity.ok(ApiEnvelope.ok(out));
    }
}
