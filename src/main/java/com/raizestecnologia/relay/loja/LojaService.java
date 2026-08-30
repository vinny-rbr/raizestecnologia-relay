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
            // Vincula a revenda na 1a vez que a loja aparece com um codigo (fica com quem instalou).
            if (l.getRevendaCodigo() == null && ev.revenda() != null && !ev.revenda().isBlank()) {
                l.setRevendaCodigo(ev.revenda());
            }
            l.setAtualizadoEm(Instant.now());
            if (l.getAtivadaEm() == null) l.setAtivadaEm(Instant.now()); // 1a ativacao (base da cobranca)
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

    /** Data de ativação (cliente desde) por cnpj — null se não registrada. */
    public java.time.Instant ativadaEm(String cnpj) {
        String c = cnpj == null ? "" : cnpj.replaceAll("\\D", "");
        if (c.isBlank()) return null;
        return repo.findById(c).map(Loja::getAtivadaEm).orElse(null);
    }

    /** Master define/corrige a data de ativação (dia da instalação = base da cobrança proporcional). */
    @Transactional
    public void definirAtivacao(String cnpj, java.time.Instant quando) {
        String c = cnpj == null ? "" : cnpj.replaceAll("\\D", "");
        if (c.isBlank() || quando == null) return;
        Loja l = repo.findById(c).orElseGet(() -> new Loja(c, ""));
        l.setAtivadaEm(quando);
        repo.save(l);
    }

    /** Valor da mensalidade da loja (null se não definido). */
    public Double mensalidade(String cnpj) {
        String c = cnpj == null ? "" : cnpj.replaceAll("\\D", "");
        if (c.isBlank()) return null;
        return repo.findById(c).map(Loja::getMensalidade).orElse(null);
    }

    /** Master define a mensalidade (R$) da loja. */
    @Transactional
    public void definirMensalidade(String cnpj, Double valor) {
        String c = cnpj == null ? "" : cnpj.replaceAll("\\D", "");
        if (c.isBlank()) return;
        Loja l = repo.findById(c).orElseGet(() -> new Loja(c, ""));
        l.setMensalidade(valor);
        repo.save(l);
    }

    /** Master define o dia de vencimento (1..28) da loja. */
    @Transactional
    public void definirDiaVencimento(String cnpj, int dia) {
        String c = cnpj == null ? "" : cnpj.replaceAll("\\D", "");
        if (c.isBlank()) return;
        Loja l = repo.findById(c).orElseGet(() -> new Loja(c, ""));
        l.setDiaVencimento(Math.min(28, Math.max(1, dia)));
        repo.save(l);
    }

    public int diaVencimento(String cnpj) {
        return obter(cnpj).map(Loja::getDiaVencimento).orElse(5);
    }

    /** Define (ou limpa, com null/"") o grupo/pasta de organizacao da loja. */
    @Transactional
    public void definirGrupo(String cnpj, String grupo) {
        String c = cnpj == null ? "" : cnpj.replaceAll("\\D", "");
        if (c.isBlank()) return;
        Loja l = repo.findById(c).orElseGet(() -> new Loja(c, ""));
        l.setGrupo(grupo);
        repo.save(l);
    }

    public String grupo(String cnpj) {
        return obter(cnpj).map(Loja::getGrupo).orElse(null);
    }

    /** Master define o vencimento da implantação (null = padrão de 3 dias). */
    @Transactional
    public void definirImplantacaoVence(String cnpj, java.time.LocalDate data) {
        String c = cnpj == null ? "" : cnpj.replaceAll("\\D", "");
        if (c.isBlank()) return;
        Loja l = repo.findById(c).orElseGet(() -> new Loja(c, ""));
        l.setImplantacaoVence(data);
        repo.save(l);
    }

    public java.time.LocalDate implantacaoVence(String cnpj) {
        return obter(cnpj).map(Loja::getImplantacaoVence).orElse(null);
    }

    /** Master vincula (ou desvincula, com null/"") uma loja a um revendedor. */
    @Transactional
    public void vincularRevenda(String cnpj, String codigo) {
        String c = cnpj == null ? "" : cnpj.replaceAll("\\D", "");
        if (c.isBlank()) return;
        Loja l = repo.findById(c).orElseGet(() -> new Loja(c, ""));
        boolean tem = codigo != null && !codigo.isBlank();
        l.setRevendaCodigo(tem ? codigo.trim().toUpperCase() : null);
        l.setRevendaAtivada(tem);
        repo.save(l);
    }

    /** Todas as lojas (1 query) — pra montar telas sem N+1 consultas. */
    public java.util.List<Loja> todas() { return repo.findAll(); }

    /** A loja (entidade) por cnpj, se existir. */
    public java.util.Optional<Loja> obter(String cnpj) {
        String c = cnpj == null ? "" : cnpj.replaceAll("\\D", "");
        return c.isBlank() ? java.util.Optional.empty() : repo.findById(c);
    }

    /** Todas as lojas conhecidas: cnpj -> nome. */
    public Map<String, String> conhecidas() {
        Map<String, String> m = new LinkedHashMap<>();
        for (Loja l : repo.findAll()) {
            m.put(l.getCnpj(), l.getNome() == null ? "" : l.getNome());
        }
        return m;
    }

    // ---- Bloqueio por pagamento (somente o DONO/master aciona) --------------

    /** true se a loja esta suspensa por pendencia (usuarios dela nao acessam). */
    public boolean estaBloqueada(String cnpj) {
        String c = norm(cnpj);
        if (c.isBlank()) return false;
        return repo.findById(c).map(Loja::isBloqueada).orElse(false);
    }

    /** Motivo do bloqueio (ou "" se nao houver). */
    public String motivo(String cnpj) {
        String c = norm(cnpj);
        if (c.isBlank()) return "";
        return repo.findById(c).map(l -> l.getMotivoBloqueio() == null ? "" : l.getMotivoBloqueio()).orElse("");
    }

    /** Suspende a loja (cria o registro se ainda nao existir). */
    @Transactional
    public void bloquear(String cnpj, String motivo) {
        setBloqueio(cnpj, true, motivo);
    }

    /** Reativa a loja (no-op se ela nem existe/estava bloqueada). */
    @Transactional
    public void desbloquear(String cnpj) {
        String c = norm(cnpj);
        if (c.isBlank()) return;
        repo.findById(c).ifPresent(l -> {
            l.setBloqueada(false);
            l.setMotivoBloqueio(null);
            repo.save(l);
        });
    }

    /** Remove uma loja do registro (ex.: loja desativada / cadastro errado). */
    @Transactional
    public void remover(String cnpj) {
        String c = norm(cnpj);
        if (!c.isBlank()) repo.deleteById(c);
    }

    private void setBloqueio(String cnpj, boolean bloqueada, String motivo) {
        String c = norm(cnpj);
        if (c.isBlank()) return;
        // Bloquear pode criar o registro (permite suspender antes do 1o acesso).
        Loja l = repo.findById(c).orElseGet(() -> new Loja(c, ""));
        l.setBloqueada(bloqueada);
        l.setMotivoBloqueio(bloqueada ? (motivo == null || motivo.isBlank() ? "Pagamento pendente" : motivo.trim()) : null);
        repo.save(l);
    }

    private static String norm(String cnpj) {
        return cnpj == null ? "" : cnpj.replaceAll("\\D", "");
    }
}
