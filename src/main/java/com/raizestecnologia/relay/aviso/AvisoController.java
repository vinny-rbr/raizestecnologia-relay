package com.raizestecnologia.relay.aviso;

import com.raizestecnologia.relay.auth.ApiEnvelope;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Aviso de manutenção.
 *  GET  /api/aviso              (sem login) o aviso que está valendo agora, ou data null
 *  GET  /api/admin/aviso        (master) o aviso salvo, mesmo desligado
 *  POST /api/admin/aviso        (master) {ativo, tipo, inicio, fim, mensagem}  datas em ISO (2026-10-28T21:00:00-03:00)
 */
@RestController
@CrossOrigin(origins = "*")
public class AvisoController {

    private final AvisoManutencaoRepository repo;

    public AvisoController(AvisoManutencaoRepository repo) {
        this.repo = repo;
    }

    private AvisoManutencao atual() {
        return repo.findById(1L).orElseGet(AvisoManutencao::new);
    }

    /** Ligado e a manutenção ainda não terminou (sem fim: vale até desligar). */
    @GetMapping("/api/aviso")
    public ResponseEntity<Map<String, Object>> publico() {
        AvisoManutencao a = atual();
        Instant agora = Instant.now();
        boolean vale = a.isAtivo() && (a.getFim() == null || a.getFim().isAfter(agora));
        return ResponseEntity.ok(ApiEnvelope.ok(vale ? json(a, agora) : null));
    }

    @GetMapping("/api/admin/aviso")
    public ResponseEntity<Map<String, Object>> ver() {
        return ResponseEntity.ok(ApiEnvelope.ok(json(atual(), Instant.now())));
    }

    @PostMapping("/api/admin/aviso")
    public ResponseEntity<Map<String, Object>> salvar(@RequestBody Map<String, Object> b) {
        AvisoManutencao a = atual();
        Instant inicio, fim;
        try {
            inicio = data(b.get("inicio"));
            fim = data(b.get("fim"));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(ApiEnvelope.fail("Data inválida"));
        }
        if (inicio != null && fim != null && !fim.isAfter(inicio))
            return ResponseEntity.badRequest().body(ApiEnvelope.fail("O fim tem que ser depois do início"));
        String msg = b.get("mensagem") == null ? "" : String.valueOf(b.get("mensagem")).trim();
        if (msg.length() > 500) msg = msg.substring(0, 500);
        a.setAtivo(Boolean.TRUE.equals(b.get("ativo")));
        a.setTipo(AvisoManutencao.AVISO.equals(b.get("tipo")) ? AvisoManutencao.AVISO : AvisoManutencao.INDISPONIVEL);
        a.setInicio(inicio);
        a.setFim(fim);
        a.setMensagem(msg.isEmpty() ? null : msg);
        a.setAtualizadoEm(Instant.now());
        repo.save(a);
        return ResponseEntity.ok(ApiEnvelope.ok(json(a, Instant.now())));
    }

    private static Instant data(Object v) {
        if (v == null || String.valueOf(v).isBlank()) return null;
        return java.time.OffsetDateTime.parse(String.valueOf(v)).toInstant();
    }

    private static Map<String, Object> json(AvisoManutencao a, Instant agora) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ativo", a.isAtivo());
        m.put("tipo", a.getTipo());
        m.put("inicio", a.getInicio() == null ? null : a.getInicio().toString());
        m.put("fim", a.getFim() == null ? null : a.getFim().toString());
        m.put("mensagem", a.getMensagem());
        // já começou (sem início: começa quando liga)
        m.put("emAndamento", a.isAtivo() && (a.getInicio() == null || !a.getInicio().isAfter(agora))
                && (a.getFim() == null || a.getFim().isAfter(agora)));
        return m;
    }
}
