package com.raizestecnologia.relay.loja;

import com.raizestecnologia.relay.notify.AgentConnectedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Mantem o registro persistente das lojas conhecidas. Toda vez que um agente conecta,
 * a loja e criada/atualizada. Assim o app lista tambem as lojas offline.
 */
@Service
public class LojaService {

    private static final Logger log = LoggerFactory.getLogger(LojaService.class);

    private final LojaRepository repo;

    public LojaService(LojaRepository repo) {
        this.repo = repo;
    }

    @EventListener
    @Transactional
    public void onConnect(AgentConnectedEvent ev) {
        String cnpj = ev.cnpj() == null ? "" : ev.cnpj().replaceAll("\\D", "");
        if (cnpj.isBlank()) return;
        try {
            Loja l = repo.findById(cnpj).orElseGet(() -> new Loja(cnpj, ev.nome()));
            if (ev.nome() != null && !ev.nome().isBlank()) l.setNome(ev.nome());
            l.setAtualizadoEm(Instant.now());
            repo.save(l);
        } catch (Exception e) {
            log.warn("[loja] falha ao registrar loja {}: {}", cnpj, e.getMessage());
        }
    }

    /**
     * Semeia as lojas que ja existiam antes desta feature (as novas se auto-registram
     * ao conectar). Idempotente: so insere se ainda nao existir.
     */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void seedExistentes() {
        seed("53629966000159", "FERREIRENSE SERVICOS");
        seed("10543487000102", "REP ERMERSON MACAPA - RAIZES TECNOLOGIA");
        seed("21698630000151", "BIG CONSTRUCAO");
    }

    private void seed(String cnpj, String nome) {
        try {
            if (!repo.existsById(cnpj)) repo.save(new Loja(cnpj, nome));
        } catch (Exception ignore) {}
    }

    /** Todas as lojas conhecidas: cnpj -> nome. */
    public Map<String, String> conhecidas() {
        Map<String, String> m = new LinkedHashMap<>();
        for (Loja l : repo.findAll()) {
            m.put(l.getCnpj(), l.getNome() == null ? "" : l.getNome());
        }
        return m;
    }
}
