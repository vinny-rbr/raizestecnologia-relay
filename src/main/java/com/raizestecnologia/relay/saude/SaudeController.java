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

/**
 * Histórico de eventos de rede das lojas. /api/admin/saude = master (vê todas); /api/saude-lojas = revenda, que vê
 * só as lojas dela com a Saúde ligada (recurso "saude", +R$10/mês na mensalidade da loja).
 */
@RestController
@RequestMapping({"/api/admin/saude", "/api/saude-lojas"})
public class SaudeController {

    private final EventoLojaRepository repo;
    private final com.raizestecnologia.relay.auth.AppUserRepository users;
    private final com.raizestecnologia.relay.loja.LojaRepository lojas;

    public SaudeController(EventoLojaRepository repo, com.raizestecnologia.relay.auth.AppUserRepository users,
                           com.raizestecnologia.relay.loja.LojaRepository lojas) {
        this.repo = repo;
        this.users = users;
        this.lojas = lojas;
    }

    /** Lojas que o usuário pode ver na Saúde: null = todas (master); revenda = as dela com a Saúde ligada. */
    private java.util.Set<String> permitidas() {
        var p = com.raizestecnologia.relay.auth.CurrentUser.get();
        if (p == null) return java.util.Set.of();
        if (p.isDono()) return null;
        java.util.Set<String> out = new java.util.HashSet<>();
        if (p.isRevenda()) {
            for (var l : lojas.findByRevendaCodigoOrderByAtualizadoEmDesc(p.revendaCodigo()))
                if (l.temRecurso(com.raizestecnologia.relay.loja.Recursos.SAUDE)) out.add(l.getCnpj());
        }
        return out;
    }

    private static boolean pode(java.util.Set<String> ok, String loja) {
        return ok == null || ok.contains(loja);
    }

    private static final ResponseEntity<Map<String, Object>> SEM_PERMISSAO =
            ResponseEntity.status(403).body(ApiEnvelope.fail("Sem permissão para esta loja"));

    /** GET .../ativas — lojas com a Saúde ligada que o usuário vê ({todas:true} pro master). O app usa pra explicar a
     *  Saúde (e o preço) pra revenda que ainda não ligou em nenhuma loja. */
    @GetMapping("/ativas")
    public ResponseEntity<Map<String, Object>> ativas() {
        java.util.Set<String> ok = permitidas();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("todas", ok == null);
        out.put("lojas", ok == null ? List.of() : List.copyOf(ok));
        out.put("precoMensal", com.raizestecnologia.relay.cobranca.CobrancaService.SAUDE_REVENDA);
        return ResponseEntity.ok(ApiEnvelope.ok(out));
    }

    /** POST /api/admin/saude/{id}/resolvendo {resolvendo:true|false} — "estou resolvendo": para os avisos desse problema. */
    @org.springframework.web.bind.annotation.PostMapping("/{id}/resolvendo")
    public ResponseEntity<Map<String, Object>> resolvendo(@org.springframework.web.bind.annotation.PathVariable Long id,
                                                          @org.springframework.web.bind.annotation.RequestBody(required = false) Map<String, Object> body) {
        EventoLoja e = repo.findById(id).orElse(null);
        if (e == null) return ResponseEntity.status(404).body(ApiEnvelope.fail("Evento não encontrado"));
        if (!pode(permitidas(), e.getLoja())) return SEM_PERMISSAO;
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

    /** POST /api/admin/saude/{id}/resolvido — fecha o problema à mão (aviso que não se resolve sozinho). */
    @org.springframework.web.bind.annotation.PostMapping("/{id}/resolvido")
    public ResponseEntity<Map<String, Object>> resolvido(@org.springframework.web.bind.annotation.PathVariable Long id) {
        EventoLoja e = repo.findById(id).orElse(null);
        if (e == null) return ResponseEntity.status(404).body(ApiEnvelope.fail("Evento não encontrado"));
        if (!pode(permitidas(), e.getLoja())) return SEM_PERMISSAO;
        var p = com.raizestecnologia.relay.auth.CurrentUser.get();
        String quem = p == null ? "" : p.email();
        try {
            var u = p == null ? null : users.findById(Long.valueOf(p.userId())).orElse(null);
            if (u != null && u.getNome() != null && !u.getNome().isBlank()) quem = u.getNome();
        } catch (Exception ignored) {
        }
        EventoLoja ok = new EventoLoja();
        ok.setLoja(e.getLoja());
        ok.setLojaNome(e.getLojaNome());
        ok.setTipo(e.getTipo());
        ok.setNivel("ok");
        ok.setTitulo("Marcado como resolvido");
        ok.setDetalhe(e.getTitulo() + (quem.isBlank() ? "" : " · por " + quem));
        ok.setChave(e.getChave());
        ok.setPush(false);
        repo.save(ok);
        return ResponseEntity.ok(ApiEnvelope.ok(Map.of("id", id)));
    }

    /** GET /api/admin/saude/pendentes — problemas ainda abertos (o último evento do problema não é "voltou"). */
    @GetMapping("/pendentes")
    public ResponseEntity<Map<String, Object>> pendentes() {
        java.util.Set<String> ok = permitidas();
        Map<String, EventoLoja> ultimo = new java.util.LinkedHashMap<>();
        Map<String, java.time.Instant> desde = new java.util.HashMap<>();
        for (EventoLoja e : repo.findByCriadoEmAfterOrderByCriadoEmAsc(java.time.Instant.now().minus(java.time.Duration.ofDays(30)))) {
            if (!pode(ok, e.getLoja())) continue;
            String k = e.getLoja() + "|" + e.getChave();
            EventoLoja ant = ultimo.get(k);
            if (!"ok".equals(e.getNivel()) && (ant == null || "ok".equals(ant.getNivel()))) desde.put(k, e.getCriadoEm());
            ultimo.put(k, e);
        }
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (var en : ultimo.entrySet()) {
            EventoLoja e = en.getValue();
            if ("ok".equals(e.getNivel()) || !SaudeService.pendencia(e)) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", e.getId());
            m.put("loja", e.getLoja());
            m.put("lojaNome", e.getLojaNome());
            m.put("tipo", e.getTipo());
            m.put("nivel", e.getNivel());
            m.put("titulo", e.getTitulo());
            m.put("detalhe", e.getDetalhe());
            m.put("desde", desde.getOrDefault(en.getKey(), e.getCriadoEm()).toString());
            m.put("resolvendo", e.isResolvendo());
            m.put("resolvendoPor", e.getResolvendoPor());
            out.add(m);
        }
        // graves primeiro, depois os mais antigos
        out.sort((a, b) -> {
            int g = Boolean.compare(!"grave".equals(a.get("nivel")), !"grave".equals(b.get("nivel")));
            return g != 0 ? g : String.valueOf(a.get("desde")).compareTo(String.valueOf(b.get("desde")));
        });
        return ResponseEntity.ok(ApiEnvelope.ok(out));
    }

    /**
     * GET /api/admin/saude/relatorio?dias=30 — o que mais acontece: ocorrências por tipo, por loja e por dia, e o tempo
     * até resolver. Ocorrência = começo de um problema (repetições do mesmo problema aberto não contam).
     */
    @GetMapping("/relatorio")
    public ResponseEntity<Map<String, Object>> relatorio(@RequestParam(defaultValue = "30") int dias) {
        int d = Math.max(1, Math.min(90, dias));
        java.time.ZoneId brt = java.time.ZoneId.of("America/Sao_Paulo");
        Map<String, EventoLoja> ultimo = new java.util.HashMap<>();
        Map<String, java.time.Instant> inicio = new java.util.HashMap<>();
        Map<String, long[]> porTipo = new java.util.TreeMap<>();   // tipo -> [ocorrencias, resolvidas, minutos]
        Map<String, Integer> porLoja = new java.util.HashMap<>();
        Map<String, String> nomeLoja = new java.util.HashMap<>();
        Map<String, String> tituloTipo = new java.util.HashMap<>();
        Map<String, Integer> porDia = new java.util.TreeMap<>();
        java.time.LocalDate hoje = java.time.LocalDate.now(brt);
        for (int i = d - 1; i >= 0; i--) porDia.put(hoje.minusDays(i).toString(), 0);
        java.util.Set<String> ok = permitidas();
        for (EventoLoja e : repo.findByCriadoEmAfterOrderByCriadoEmAsc(java.time.Instant.now().minus(java.time.Duration.ofDays(d)))) {
            if (!pode(ok, e.getLoja())) continue;
            String k = e.getLoja() + "|" + e.getChave();
            EventoLoja ant = ultimo.get(k);
            ultimo.put(k, e);
            if (!"ok".equals(e.getNivel())) {
                if (ant != null && !"ok".equals(ant.getNivel())) continue; // repetição do mesmo problema
                inicio.put(k, e.getCriadoEm());
                porTipo.computeIfAbsent(e.getTipo(), t -> new long[3])[0]++;
                tituloTipo.putIfAbsent(e.getTipo(), e.getTitulo());
                porLoja.merge(e.getLoja(), 1, Integer::sum);
                if (e.getLojaNome() != null && !e.getLojaNome().isBlank()) nomeLoja.put(e.getLoja(), e.getLojaNome());
                porDia.merge(e.getCriadoEm().atZone(brt).toLocalDate().toString(), 1, Integer::sum);
            } else if (ant != null && !"ok".equals(ant.getNivel()) && inicio.containsKey(k)) {
                long[] t = porTipo.computeIfAbsent(ant.getTipo(), x -> new long[3]);
                t[1]++;
                t[2] += java.time.Duration.between(inicio.remove(k), e.getCriadoEm()).toMinutes();
            }
        }
        List<Map<String, Object>> tipos = new java.util.ArrayList<>();
        long total = 0;
        for (var en : porTipo.entrySet()) {
            long[] t = en.getValue();
            if (t[0] == 0) continue;
            total += t[0];
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("tipo", en.getKey());
            m.put("titulo", tituloTipo.getOrDefault(en.getKey(), en.getKey()));
            m.put("ocorrencias", t[0]);
            m.put("resolvidas", t[1]);
            m.put("mediaMinutos", t[1] == 0 ? null : t[2] / t[1]);
            tipos.add(m);
        }
        tipos.sort((a, b) -> Long.compare((long) b.get("ocorrencias"), (long) a.get("ocorrencias")));
        List<Map<String, Object>> lojasOut = new java.util.ArrayList<>();
        porLoja.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(10).forEach(en -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("loja", en.getKey());
            m.put("lojaNome", nomeLoja.getOrDefault(en.getKey(), en.getKey()));
            m.put("ocorrencias", en.getValue());
            lojasOut.add(m);
        });
        List<Map<String, Object>> diasOut = new java.util.ArrayList<>();
        porDia.forEach((dia, n) -> diasOut.add(Map.of("dia", dia, "ocorrencias", n)));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dias", d);
        out.put("total", total);
        out.put("porTipo", tipos);
        out.put("porLoja", lojasOut);
        out.put("porDia", diasOut);
        return ResponseEntity.ok(ApiEnvelope.ok(out));
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> lista(@RequestParam(required = false) String loja,
                                                     @RequestParam(defaultValue = "150") int limite) {
        java.util.Set<String> permitidas = permitidas();
        PageRequest p = PageRequest.of(0, Math.max(1, Math.min(500, limite)));
        List<EventoLoja> ev;
        if (loja != null && !loja.isBlank()) {
            String c = loja.replaceAll("\\D", "");
            if (!pode(permitidas, c)) return SEM_PERMISSAO;
            ev = repo.findByLojaOrderByCriadoEmDesc(c, p);
        } else if (permitidas == null) {
            ev = repo.findByOrderByCriadoEmDesc(p);
        } else if (permitidas.isEmpty()) {
            ev = List.of();
        } else {
            ev = repo.findByLojaInOrderByCriadoEmDesc(permitidas, p);
        }
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
