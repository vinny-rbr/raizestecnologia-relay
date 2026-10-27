package com.raizestecnologia.relay.suporte;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.text.Normalizer;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Regras dos chamados de suporte que vêm do WhatsApp (conector rodando no PC do Lucas, logado no número dele):
 *  - mensagem de CLIENTE num grupo "SUP..." sem chamado em aberto → abre um chamado;
 *  - mensagem da EQUIPE (o próprio número ou um número cadastrado) → primeira resposta / "atendido por";
 *  - comandos da equipe no grupo (#atendendo, #finalizado...) → muda o status.
 */
@Service
public class SuporteService {

    private static final Logger log = LoggerFactory.getLogger(SuporteService.class);
    static final ZoneId BRT = ZoneId.of("America/Sao_Paulo");
    static final Set<String> ATIVOS = Set.of(Chamado.ABERTO, Chamado.ATENDIMENTO);

    static final String CFG_TOKEN = "token_conector";
    static final String CFG_TV = "chave_tv";
    static final String CFG_MEU_NOME = "nome_dono";
    static final String CFG_PING = "conector_ping";

    private final ChamadoRepository chamados;
    private final SuporteEquipeRepository equipe;
    private final SuporteConfigRepository config;
    private final SuporteAutorRepository autores;

    public SuporteService(ChamadoRepository chamados, SuporteEquipeRepository equipe, SuporteConfigRepository config,
                          SuporteAutorRepository autores) {
        this.chamados = chamados;
        this.equipe = equipe;
        this.config = config;
        this.autores = autores;
    }

    /** Gera as chaves do conector e da TV na primeira vez (ficam no banco; o painel mostra). */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void garantirChaves() {
        try {
            if (cfg(CFG_TOKEN) == null) salvarCfg(CFG_TOKEN, aleatorio(32));
            if (cfg(CFG_TV) == null) salvarCfg(CFG_TV, aleatorio(16));
            if (cfg(CFG_MEU_NOME) == null) salvarCfg(CFG_MEU_NOME, "Lucas");
        } catch (Exception e) {
            log.warn("[suporte] não deu pra preparar as chaves: {}", e.getMessage());
        }
    }

    public String cfg(String chave) {
        return config.findById(chave).map(SuporteConfig::getValor).orElse(null);
    }

    public void salvarCfg(String chave, String valor) {
        SuporteConfig c = config.findById(chave).orElse(new SuporteConfig(chave, valor));
        c.setValor(valor);
        config.save(c);
    }

    private static String aleatorio(int n) {
        String abc = "abcdefghijkmnpqrstuvwxyz23456789";
        SecureRandom r = new SecureRandom();
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n; i++) b.append(abc.charAt(r.nextInt(abc.length())));
        return b.toString();
    }

    public boolean tokenOk(String t) {
        String ok = cfg(CFG_TOKEN);
        return ok != null && t != null && ok.equals(t.trim());
    }

    public boolean chaveTvOk(String k) {
        String ok = cfg(CFG_TV);
        return ok != null && k != null && ok.equals(k.trim());
    }

    /** Mensagem que chegou num grupo SUP (o conector já filtrou pelo nome do grupo). */
    public record Mensagem(String grupoId, String grupoNome, String autorNumero, String autorNome, boolean minha,
                           String texto, Long ts) {}

    @Transactional
    public void receber(Mensagem m) {
        if (m == null || m.grupoId() == null || m.grupoId().isBlank()) return;
        Instant quando = m.ts() != null && m.ts() > 0 ? Instant.ofEpochSecond(m.ts()) : Instant.now();
        String texto = m.texto() == null ? "" : m.texto().trim();
        String numero = m.autorNumero() == null ? "" : m.autorNumero().replaceAll("\\D", "");
        Optional<Chamado> aberto = chamados.findFirstByGrupoIdAndStatusInOrderByAbertoEmDesc(m.grupoId(), ATIVOS);

        if (!m.minha() && !numero.isEmpty()) registrarAutor(numero, m.autorNome(), m.grupoNome(), quando);

        SuporteEquipe membro = m.minha() ? new SuporteEquipe("", cfg(CFG_MEU_NOME), "TECNICO") : equipe.findById(numero).orElse(null);
        if (membro != null) {
            // equipe: não abre chamado. Comercial não atende; técnico: o ÚLTIMO que escreve assume o chamado
            // (ex.: Emerson manda "um momento, já vamos atender" e depois outro técnico entra e assume).
            if (aberto.isEmpty() || !membro.isTecnico()) return;
            Chamado c = aberto.get();
            String cmd = comando(texto);
            if (c.getPrimeiraRespostaEm() == null && quando.isAfter(c.getAbertoEm())) c.setPrimeiraRespostaEm(quando);
            if ("FINALIZAR".equals(cmd)) {
                finalizar(c, membro.getNome(), quando);
            } else if (!membro.getNome().equals(c.getAtendidoPor())) {
                atender(c, membro.getNome(), quando);
            }
            chamados.save(c);
            return;
        }

        // cliente
        if (aberto.isPresent()) {
            Chamado c = aberto.get();
            c.setQtdMensagens(c.getQtdMensagens() + 1);
            c.setUltimaMensagem(corta(texto));
            c.setUltimaMensagemEm(quando);
            if (m.grupoNome() != null && !m.grupoNome().isBlank()) c.setGrupoNome(m.grupoNome());
            chamados.save(c);
            return;
        }
        Chamado c = new Chamado();
        c.setGrupoId(m.grupoId());
        c.setGrupoNome(m.grupoNome());
        c.setClienteNome(m.autorNome());
        c.setClienteNumero(numero);
        c.setPrimeiraMensagem(corta(texto));
        c.setUltimaMensagem(corta(texto));
        c.setUltimaMensagemEm(quando);
        c.setAbertoEm(quando);
        chamados.save(c);
        log.info("[suporte] chamado aberto: {} ({})", m.grupoNome(), m.autorNome());
    }

    private void registrarAutor(String numero, String nome, String grupo, Instant quando) {
        try {
            SuporteAutor a = autores.findById(numero).orElseGet(() -> new SuporteAutor(numero));
            if (nome != null && !nome.isBlank()) a.setNome(nome);
            a.setUltimoGrupo(grupo);
            a.setUltimaVez(quando);
            a.setMensagens(a.getMensagens() + 1);
            autores.save(a);
        } catch (Exception e) {
            log.debug("[suporte] autor: {}", e.getMessage());
        }
    }

    /**
     * Comando da equipe escrito no grupo → "ATENDER", "FINALIZAR" ou null (mensagem comum).
     * O texto chega sem acento e em minúsculas (ver normalizar).
     */
    static String comando(String texto) {
        String t = normalizar(texto);
        // atender não precisa de comando (a 1ª resposta da equipe já marca); estes só passam o chamado pra quem mandou
        if (t.startsWith("#atendendo") || t.startsWith("#peguei")) return "ATENDER";
        if (t.startsWith("#finalizado") || t.startsWith("#resolvido") || t.startsWith("#fim")) return "FINALIZAR";
        return null;
    }

    /** minúsculas, sem acento e sem espaço nas pontas ("#Finalizado " → "#finalizado") */
    static String normalizar(String s) {
        if (s == null) return "";
        String n = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return n.toLowerCase().trim();
    }

    private static String corta(String s) {
        if (s == null) return null;
        return s.length() > 480 ? s.substring(0, 480) + "…" : s;
    }

    public void atender(Chamado c, String nome, Instant quando) {
        if (Chamado.FINALIZADO.equals(c.getStatus())) return;
        c.setStatus(Chamado.ATENDIMENTO);
        c.setAtendidoPor(nome);
        c.setAtendidoEm(quando);
        if (c.getPrimeiraRespostaEm() == null) c.setPrimeiraRespostaEm(quando);
    }

    public void finalizar(Chamado c, String nome, Instant quando) {
        if (c.getAtendidoPor() == null) atender(c, nome, quando);
        c.setStatus(Chamado.FINALIZADO);
        c.setFinalizadoPor(nome);
        c.setFinalizadoEm(quando);
    }

    // ---------- leitura (painel, TV, relatórios) ----------

    public Map<String, Object> json(Chamado c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.getId());
        m.put("grupo", c.getGrupoNome());
        m.put("cliente", c.getClienteNome());
        m.put("clienteNumero", c.getClienteNumero());
        m.put("primeiraMensagem", c.getPrimeiraMensagem());
        m.put("ultimaMensagem", c.getUltimaMensagem());
        m.put("ultimaMensagemEm", str(c.getUltimaMensagemEm()));
        m.put("mensagens", c.getQtdMensagens());
        m.put("status", c.getStatus());
        m.put("abertoEm", str(c.getAbertoEm()));
        m.put("primeiraRespostaEm", str(c.getPrimeiraRespostaEm()));
        m.put("atendidoPor", c.getAtendidoPor());
        m.put("atendidoEm", str(c.getAtendidoEm()));
        m.put("finalizadoPor", c.getFinalizadoPor());
        m.put("finalizadoEm", str(c.getFinalizadoEm()));
        m.put("minutosResposta", minutos(c.getAbertoEm(), c.getPrimeiraRespostaEm()));
        m.put("minutosSolucao", minutos(c.getAbertoEm(), c.getFinalizadoEm()));
        return m;
    }

    private static String str(Instant i) { return i == null ? null : i.toString(); }

    private static Long minutos(Instant a, Instant b) {
        return a == null || b == null ? null : Math.max(0, Duration.between(a, b).toMinutes());
    }

    public List<Map<String, Object>> ativos() {
        return chamados.findByStatusInOrderByAbertoEmAsc(ATIVOS).stream().map(this::json).toList();
    }

    /** Resumo/relatório de um período (datas em Brasília, inclusive). */
    public Map<String, Object> relatorio(LocalDate de, LocalDate ate, boolean comLista) {
        Instant i = de.atStartOfDay(BRT).toInstant();
        Instant f = ate.plusDays(1).atStartOfDay(BRT).toInstant();
        List<Chamado> lista = chamados.findByAbertoEmBetweenOrderByAbertoEmDesc(i, f);
        Map<String, long[]> porAtendente = new HashMap<>(); // nome -> [atendidos, finalizados, somaMinResposta, qtdResposta]
        Map<String, Integer> porGrupo = new HashMap<>();
        long somaResp = 0, qtdResp = 0, somaSol = 0, qtdSol = 0, finalizados = 0;
        for (Chamado c : lista) {
            porGrupo.merge(c.getGrupoNome() == null ? "?" : c.getGrupoNome(), 1, Integer::sum);
            Long r = minutos(c.getAbertoEm(), c.getPrimeiraRespostaEm());
            if (r != null) { somaResp += r; qtdResp++; }
            Long s = minutos(c.getAbertoEm(), c.getFinalizadoEm());
            if (s != null) { somaSol += s; qtdSol++; }
            if (Chamado.FINALIZADO.equals(c.getStatus())) finalizados++;
            if (c.getAtendidoPor() != null) {
                long[] a = porAtendente.computeIfAbsent(c.getAtendidoPor(), k -> new long[4]);
                a[0]++;
                if (Chamado.FINALIZADO.equals(c.getStatus())) a[1]++;
                if (r != null) { a[2] += r; a[3]++; }
            }
        }
        List<Map<String, Object>> ranking = new ArrayList<>();
        porAtendente.forEach((nome, a) -> {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("nome", nome);
            x.put("atendidos", a[0]);
            x.put("finalizados", a[1]);
            x.put("mediaRespostaMin", a[3] == 0 ? null : a[2] / a[3]);
            ranking.add(x);
        });
        ranking.sort((x, y) -> Long.compare((long) y.get("atendidos"), (long) x.get("atendidos")));
        List<Map<String, Object>> grupos = new ArrayList<>();
        porGrupo.entrySet().stream().sorted((x, y) -> y.getValue() - x.getValue()).limit(15)
                .forEach(e -> grupos.add(Map.of("grupo", e.getKey(), "chamados", e.getValue())));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("de", de.toString());
        out.put("ate", ate.toString());
        out.put("total", lista.size());
        out.put("finalizados", finalizados);
        out.put("emAberto", lista.size() - finalizados);
        out.put("mediaRespostaMin", qtdResp == 0 ? null : somaResp / qtdResp);
        out.put("mediaSolucaoMin", qtdSol == 0 ? null : somaSol / qtdSol);
        out.put("ranking", ranking);
        out.put("porGrupo", grupos);
        if (comLista) out.put("chamados", lista.stream().map(this::json).toList());
        return out;
    }
}
