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
    private final SuporteMensagemRepository mensagens;

    public SuporteService(ChamadoRepository chamados, SuporteEquipeRepository equipe, SuporteConfigRepository config,
                          SuporteAutorRepository autores, SuporteMensagemRepository mensagens) {
        this.mensagens = mensagens;
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
            if (cfg("retorno_marcado2") == null) { // uma vez: marca os retornos dos chamados de antes
                Map<String, Chamado> ultimoFinalizado = new HashMap<>();
                Map<String, Chamado> ultimoDoCliente = new HashMap<>();
                List<Chamado> todos = new ArrayList<>(chamados.findAll());
                todos.sort(java.util.Comparator.comparing(Chamado::getAbertoEm));
                for (Chamado c : todos) {
                    Chamado p = ultimoFinalizado.get(c.getGrupoId());
                    if (p != null && p.getFinalizadoEm().isBefore(c.getAbertoEm())
                            && p.getFinalizadoEm().isAfter(c.getAbertoEm().minusSeconds(24 * 3600))) c.setRetornoDe(p.getId());
                    Chamado q = c.getClienteNumero() == null ? null : ultimoDoCliente.get(c.getClienteNumero());
                    if (c.getRetornoDe() == null && q != null && q.getAbertoEm().isAfter(c.getAbertoEm().minusSeconds(24 * 3600))) c.setRetornoDe(q.getId());
                    if (c.getFinalizadoEm() != null) ultimoFinalizado.put(c.getGrupoId(), c);
                    if (c.getClienteNumero() != null && !c.getClienteNumero().isEmpty()) ultimoDoCliente.put(c.getClienteNumero(), c);
                }
                chamados.saveAll(todos);
                salvarCfg("retorno_marcado2", "1");
            }
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

        SuporteEquipe membro = m.minha() ? new SuporteEquipe("", cfg(CFG_MEU_NOME), "TECNICO") : membroDaEquipe(numero);
        // comercial (ex.: Rômulo, dono da empresa) conta como cliente: pode abrir chamado pro cliente, mas nunca atende
        if (membro != null && !membro.isTecnico()) membro = null;
        if (membro != null) {
            // o ÚLTIMO técnico que escreve assume o chamado
            // (ex.: Emerson manda "um momento, já vamos atender" e depois outro técnico entra e assume).
            String cmd = comando(texto);
            if (aberto.isEmpty()) {
                // técnico escreveu primeiro ("irei acessar o servidor"): abre já em atendimento com ele
                if (cmd != null || confirmacao(texto) || acabouDeFinalizar(m.grupoId(), quando)) return;
                Chamado c = new Chamado();
                c.setGrupoId(m.grupoId());
                c.setGrupoNome(m.grupoNome());
                c.setPrimeiraMensagem(corta(texto));
                c.setUltimaMensagem(corta(texto));
                c.setAbertoEm(quando);
                c.setIniciadoPelaEquipe(true);
                c.setRetornoDe(retornoDe(m.grupoId(), quando));
                c.setStatus(Chamado.ATENDIMENTO);
                c.setAtendidoPor(membro.getNome());
                c.setAtendidoEm(quando);
                c.setUltimaRespostaEm(quando);
                c.setCategoria(Categorias.de(texto));
                chamados.save(c);
                mensagens.save(new SuporteMensagem(c.getId(), membro.getNome(), true, texto, quando));
                log.info("[suporte] chamado aberto pela equipe: {} ({})", m.grupoNome(), membro.getNome());
                return;
            }
            Chamado c = aberto.get();
            if (c.getPrimeiraRespostaEm() == null && !c.isIniciadoPelaEquipe() && quando.isAfter(c.getAbertoEm())) c.setPrimeiraRespostaEm(quando);
            c.setUltimaRespostaEm(quando);
            if ("FINALIZAR".equals(cmd)) {
                finalizar(c, membro.getNome(), quando);
            } else if (!membro.getNome().equals(c.getAtendidoPor())) {
                atender(c, membro.getNome(), quando);
            }
            chamados.save(c);
            mensagens.save(new SuporteMensagem(c.getId(), membro.getNome(), true, texto, quando));
            return;
        }

        // cliente
        if (aberto.isPresent()) {
            Chamado c = aberto.get();
            c.setQtdMensagens(c.getQtdMensagens() + 1);
            c.setUltimaMensagem(corta(texto));
            // "ok", 👍, "obrigado" não é o cliente esperando resposta
            if (!confirmacao(texto)) c.setUltimaMensagemEm(quando);
            if (c.getClienteNome() == null) { c.setClienteNome(m.autorNome()); c.setClienteNumero(numero); }
            if (m.grupoNome() != null && !m.grupoNome().isBlank()) c.setGrupoNome(m.grupoNome());
            mensagens.save(new SuporteMensagem(c.getId(), m.autorNome(), false, texto, quando));
            classificar(c);
            chamados.save(c);
            return;
        }
        // "ok", 👍, "obrigado" depois de finalizar não abre chamado novo
        if (confirmacao(texto)) return;
        // quem teve o chamado descartado e segue escrevendo logo depois: vai pro descartado, não abre outro
        Optional<Chamado> descartado = chamados.findFirstByGrupoIdAndStatusOrderByFinalizadoEmDesc(m.grupoId(), Chamado.DESCARTADO)
                .filter(d -> d.getFinalizadoEm() != null && d.getFinalizadoEm().isAfter(quando.minusSeconds(30 * 60)));
        if (descartado.isPresent()) {
            mensagens.save(new SuporteMensagem(descartado.get().getId(), m.autorNome(), false, texto, quando));
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
        c.setCategoria(Categorias.de(texto));
        c.setRetornoDe(retornoDe(m.grupoId(), quando));
        if (c.getRetornoDe() == null && !numero.isEmpty()) // mesmo cliente chamando por outro grupo (ex.: ADM e WEBPOSTO do mesmo posto)
            c.setRetornoDe(chamados.findFirstByClienteNumeroAndAbertoEmAfterOrderByAbertoEmDesc(numero, quando.minusSeconds(24 * 3600))
                    .filter(p -> !Chamado.DESCARTADO.equals(p.getStatus()))
                    .map(Chamado::getId).orElse(null));
        chamados.save(c);
        mensagens.save(new SuporteMensagem(c.getId(), m.autorNome(), false, texto, quando));
        log.info("[suporte] chamado aberto: {} ({})", m.grupoNome(), m.autorNome());
    }

    /** Só confirmação/agradecimento ("ok", 👍, "obrigado", "valeu", figurinha): não pede nada. */
    static boolean confirmacao(String t) {
        String n = normalizar(t == null ? "" : t).replaceAll("\\[(figurinha|reacao)\\]", "").replaceAll("[^a-z ]", " ").replaceAll("\\s+", " ").trim();
        return n.isEmpty() || n.matches("(figurinha|reacao|ok|okay|blz|beleza|certo|certinho|show|top|perfeito|ta bom|tá bom|ta|entendi|combinado|valeu|vlw|obg|obrigad[oa]|muito obrigad[oa]|grat[oa]|disponha|por nada|de nada|sim|ss|otimo|joia)( (ok|obrigad[oa]|valeu|entao|pessoal|amigo|amiga|ai|viu|demais))*");
    }

    /** Chamado novo num grupo que teve outro finalizado nas últimas 24h = retorno (mesmo cliente chamando de novo). */
    private Long retornoDe(String grupoId, Instant quando) {
        return chamados.findFirstByGrupoIdAndStatusOrderByFinalizadoEmDesc(grupoId, Chamado.FINALIZADO)
                .filter(p -> p.getFinalizadoEm() != null && p.getFinalizadoEm().isAfter(quando.minusSeconds(24 * 3600)))
                .map(Chamado::getId).orElse(null);
    }

    /** Equipe mandou mais uma mensagem logo depois de finalizar ("qualquer coisa chama"): não abre outro. */
    private boolean acabouDeFinalizar(String grupoId, Instant quando) {
        return chamados.findFirstByGrupoIdAndStatusOrderByFinalizadoEmDesc(grupoId, Chamado.FINALIZADO)
                .map(Chamado::getFinalizadoEm).filter(f -> f.isAfter(quando.minusSeconds(30 * 60))).isPresent();
    }

    /** Tipo de problema pelas primeiras mensagens do cliente (a 1ª às vezes é só "bom dia"). */
    private void classificar(Chamado c) {
        StringBuilder b = new StringBuilder();
        for (SuporteMensagem x : mensagens.findTop8ByChamadoIdAndEquipeFalseOrderByEmAsc(c.getId())) b.append(' ').append(x.getTexto());
        c.setCategoria(Categorias.de(b.toString()));
    }

    /** Categoria de chamado antigo (sem mensagens guardadas): pela primeira e última mensagem. */
    String categoria(Chamado c) {
        if (c.getCategoria() != null) return c.getCategoria();
        return Categorias.de((c.getPrimeiraMensagem() == null ? "" : c.getPrimeiraMensagem()) + " " + (c.getUltimaMensagem() == null ? "" : c.getUltimaMensagem()));
    }

    /** Procura o número na equipe com e sem o 9 do celular (o WhatsApp usa os dois: 5596991139863 / 559691139863). */
    private SuporteEquipe membroDaEquipe(String numero) {
        if (numero == null || numero.isEmpty()) return null;
        Optional<SuporteEquipe> e = equipe.findById(numero);
        if (e.isEmpty() && numero.startsWith("55") && numero.length() == 13 && numero.charAt(4) == '9')
            e = equipe.findById(numero.substring(0, 4) + numero.substring(5));
        if (e.isEmpty() && numero.startsWith("55") && numero.length() == 12)
            e = equipe.findById(numero.substring(0, 4) + "9" + numero.substring(4));
        return e.orElse(null);
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
        // do jeito que a equipe já escreve: "chamado finalizado" (ou a mensagem ser só "finalizado")
        String sem = t.replaceAll("[.!\\s]+$", "");
        if (sem.matches("chamado (finalizado|encerrado|resolvido|concluido)( .*)?")) return "FINALIZAR";
        if (sem.matches("(finalizado|encerrado|resolvido|concluido)")) return "FINALIZAR";
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
        if (Chamado.FINALIZADO.equals(c.getStatus()) || Chamado.DESCARTADO.equals(c.getStatus())) return;
        c.setStatus(Chamado.ATENDIMENTO);
        c.setAtendidoPor(nome);
        c.setAtendidoEm(quando);
        if (c.getPrimeiraRespostaEm() == null && !c.isIniciadoPelaEquipe()) c.setPrimeiraRespostaEm(quando);
    }

    public void finalizar(Chamado c, String nome, Instant quando) {
        if (c.getAtendidoPor() == null) atender(c, nome, quando);
        c.setStatus(Chamado.FINALIZADO);
        c.setFinalizadoPor(nome);
        c.setFinalizadoEm(quando);
    }

    /** Chamado de brincadeira/por engano: sai da TV, dos avisos e dos relatórios (fica guardado como DESCARTADO). */
    public void descartar(Chamado c, String nome, Instant quando) {
        c.setStatus(Chamado.DESCARTADO);
        c.setFinalizadoPor(nome);
        c.setFinalizadoEm(quando);
    }

    // ---------- leitura (painel, TV, relatórios) ----------

    public Map<String, Object> json(Chamado c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.getId());
        m.put("retornoDe", c.getRetornoDe());
        m.put("grupo", c.getGrupoNome());
        m.put("categoria", categoria(c));
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
        Instant esp = c.esperandoDesde();
        m.put("esperandoDesde", str(esp));
        m.put("minutosEsperando", esp == null ? null : minutos(esp, Instant.now()));
        m.put("minutosResposta", minutos(c.getAbertoEm(), c.getPrimeiraRespostaEm()));
        m.put("minutosSolucao", minutos(c.getAbertoEm(), c.getFinalizadoEm()));
        return m;
    }

    private static String str(Instant i) { return i == null ? null : i.toString(); }

    private static Long minutos(Instant a, Instant b) {
        return a == null || b == null ? null : Math.max(0, Duration.between(a, b).toMinutes());
    }

    /** Conversa do chamado + resumo (problema, quem atendeu, tempo, última resposta do técnico). */
    public Map<String, Object> conversa(Chamado c) {
        List<SuporteMensagem> msgs = mensagens.findByChamadoIdOrderByEmAsc(c.getId());
        List<Map<String, Object>> lista = new ArrayList<>();
        String solucao = null;
        StringBuilder problema = new StringBuilder();
        for (SuporteMensagem x : msgs) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("autor", x.getAutor());
            m.put("equipe", x.isEquipe());
            m.put("texto", x.getTexto());
            m.put("em", x.getEm().toString());
            lista.add(m);
            String t = x.getTexto() == null ? "" : x.getTexto().trim();
            if (!x.isEquipe() && problema.length() < 300 && !saudacao(t)) problema.append(problema.length() > 0 ? " / " : "").append(t);
            if (x.isEquipe() && !"FINALIZAR".equals(comando(t)) && !t.isEmpty()) solucao = t;
        }
        Map<String, Object> resumo = new LinkedHashMap<>();
        resumo.put("categoria", categoria(c));
        resumo.put("problema", problema.length() > 0 ? problema.toString() : c.getPrimeiraMensagem());
        resumo.put("atendidoPor", c.getAtendidoPor());
        resumo.put("respostaMin", minutos(c.getAbertoEm(), c.getPrimeiraRespostaEm()));
        resumo.put("solucaoMin", minutos(c.getAbertoEm(), c.getFinalizadoEm()));
        resumo.put("ultimaRespostaTecnico", solucao);
        resumo.put("mensagens", msgs.size());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("chamado", json(c));
        out.put("resumo", resumo);
        out.put("mensagens", lista);
        out.put("semHistorico", msgs.isEmpty()); // chamado de antes de guardar as conversas
        return out;
    }

    /** "bom dia", "boa tarde", "ok", "obrigado"... não descrevem o problema. */
    private static boolean saudacao(String t) {
        String n = normalizar(t).replaceAll("[^a-z ]", "").trim();
        return n.isEmpty() || n.length() <= 3 || n.matches("(bom dia|boa tarde|boa noite|ola|oi|ok|obrigad[oa]|tudo bem|alguem ai|bom dia pessoal|boa tarde pessoal)( .{0,12})?");
    }

    public List<Map<String, Object>> ativos() {
        return chamados.findByStatusInOrderByAbertoEmAsc(ATIVOS).stream().map(this::json).toList();
    }

    // ---------- chamado parado: aviso no celular + resumo pra colar no grupo ----------

    static final long PARADO_MIN = 10;   // cliente esperando há 10 min → avisa
    static final long REPETE_MIN = 30;   // continua parado → avisa de novo a cada 30 min
    static final long LONGO_MIN = 15;    // chamado aberto há 15 min → avisa (uma vez)

    @org.springframework.beans.factory.annotation.Autowired
    private com.raizestecnologia.relay.push.FcmSender fcm;
    @org.springframework.beans.factory.annotation.Autowired
    private com.raizestecnologia.relay.push.DeviceTokenRepository tokens;
    @org.springframework.beans.factory.annotation.Autowired
    private com.raizestecnologia.relay.auth.AppUserRepository users;

    /** A cada minuto: chamado com cliente esperando há 10+ min vai pro celular do master (um aviso só, com todos). */
    @org.springframework.scheduling.annotation.Scheduled(initialDelay = 90_000, fixedDelay = 60_000)
    @Transactional
    public void avisarParados() {
        if (!fcm.enabled()) return;
        Instant agora = Instant.now();
        List<Chamado> novos = new ArrayList<>();
        Set<Long> longos = new java.util.HashSet<>(); // aberto há 15+ min (mesmo com o técnico respondendo): avisa uma vez
        for (Chamado c : chamados.findByStatusInOrderByAbertoEmAsc(ATIVOS)) {
            Instant esp = c.esperandoDesde();
            Instant ult = c.getAlertaEm();
            boolean parado = esp != null && !esp.isAfter(agora.minusSeconds(PARADO_MIN * 60))
                    && !(ult != null && ult.isAfter(esp) && ult.isAfter(agora.minusSeconds(REPETE_MIN * 60)));
            boolean longo = !c.isAlertaLongo() && !c.getAbertoEm().isAfter(agora.minusSeconds(LONGO_MIN * 60));
            if (longo) { c.setAlertaLongo(true); if (!parado) longos.add(c.getId()); }
            if (parado || longo) novos.add(c);
        }
        if (novos.isEmpty()) return;
        novos.stream().filter(c -> !longos.contains(c.getId())).forEach(c -> c.setAlertaEm(agora));
        chamados.saveAll(novos);
        List<Long> donos = users.findByRoleIgnoreCaseAndAtivoTrue("DONO").stream()
                .map(com.raizestecnologia.relay.auth.AppUser::getId).toList();
        List<String> toks = tokens.findByUserIdIn(donos).stream()
                .map(com.raizestecnologia.relay.push.DeviceToken::getToken).distinct().toList();
        String titulo = novos.size() == 1 ? "⏰ Chamado parado: " + grupoCurto(novos.get(0).getGrupoNome())
                : "⏰ " + novos.size() + " chamados parados no suporte";
        String corpo = String.join("\n", novos.stream().map(c -> grupoCurto(c.getGrupoNome()) + (longos.contains(c.getId())
                ? ": aberto há " + duracao(Duration.between(c.getAbertoEm(), agora).toMinutes())
                : ": esperando há " + duracao(Duration.between(c.esperandoDesde(), agora).toMinutes()))).toList());
        fcm.sendToTokens(toks, titulo, corpo, Map.of("tipo", "suporte"));
        log.info("[suporte] aviso de chamado parado: {}", corpo.replace("\n", " | "));
    }

    /** Texto pronto pra colar no WhatsApp com todos os chamados em aberto. */
    public String resumoTexto() {
        Instant agora = Instant.now();
        List<Chamado> l = chamados.findByStatusInOrderByAbertoEmAsc(ATIVOS);
        var fmt = java.time.format.DateTimeFormatter.ofPattern("dd/MM HH:mm").withZone(BRT);
        StringBuilder sb = new StringBuilder("*Chamados em aberto* (" + fmt.format(agora) + ")\n");
        if (l.isEmpty()) return sb.append("\nNenhum chamado em aberto ✅").toString();
        int i = 0;
        for (Chamado c : l) {
            sb.append("\n*").append(++i).append(". ").append(grupoCurto(c.getGrupoNome())).append("*\n");
            if (c.getClienteNome() != null && !c.getClienteNome().isBlank()) sb.append("Cliente: ").append(c.getClienteNome()).append("\n");
            sb.append("Aberto há ").append(duracao(Duration.between(c.getAbertoEm(), agora).toMinutes())).append("\n");
            Instant esp = c.esperandoDesde();
            String espera = esp == null ? null : duracao(Duration.between(esp, agora).toMinutes());
            if (Chamado.ABERTO.equals(c.getStatus()) && c.getAtendidoPor() == null) {
                sb.append("🔴 Sem atendimento").append(espera == null ? "" : " (esperando há " + espera + ")").append("\n");
            } else {
                boolean atrasado = (esp != null && Duration.between(esp, agora).toMinutes() >= PARADO_MIN)
                        || Duration.between(c.getAbertoEm(), agora).toMinutes() >= LONGO_MIN;
                sb.append(atrasado ? "🔴 Com " : "🟡 Com ").append(c.getAtendidoPor() == null ? "a equipe" : c.getAtendidoPor())
                        .append(espera == null ? "" : ", cliente esperando resposta há " + espera).append("\n");
            }
            String msg = c.getUltimaMensagem() == null ? c.getPrimeiraMensagem() : c.getUltimaMensagem();
            if (msg != null && !msg.isBlank()) sb.append("_\"").append(msg.length() > 120 ? msg.substring(0, 120) + "..." : msg).append("\"_\n");
        }
        return sb.toString().trim();
    }

    /** "SUP - POSTO X" → "POSTO X" */
    static String grupoCurto(String g) {
        if (g == null) return "Grupo";
        String s = g.replaceFirst("(?i)^\\s*sup(orte)?\\s*[-:._|]*\\s*", "").trim();
        return s.isEmpty() ? g.trim() : s;
    }

    static String duracao(long min) {
        if (min < 60) return min + " min";
        long h = min / 60, m = min % 60;
        return h + "h" + (m > 0 ? String.format(" %02dmin", m) : "");
    }

    /** Resumo/relatório de um período (datas em Brasília, inclusive). */
    public Map<String, Object> relatorio(LocalDate de, LocalDate ate, boolean comLista) {
        Instant i = de.atStartOfDay(BRT).toInstant();
        Instant f = ate.plusDays(1).atStartOfDay(BRT).toInstant();
        List<Chamado> todos = chamados.findByAbertoEmBetweenOrderByAbertoEmDesc(i, f);
        List<Chamado> lista = todos.stream().filter(c -> !Chamado.DESCARTADO.equals(c.getStatus())).toList();
        Map<String, long[]> porAtendente = new HashMap<>(); // nome -> [atendidos, finalizados, somaMinResposta, qtdResposta]
        Map<String, Integer> porGrupo = new HashMap<>();
        Map<String, Integer> porCategoria = new HashMap<>();
        long somaResp = 0, qtdResp = 0, somaSol = 0, qtdSol = 0, finalizados = 0;
        for (Chamado c : lista) {
            porGrupo.merge(c.getGrupoNome() == null ? "?" : c.getGrupoNome(), 1, Integer::sum);
            porCategoria.merge(categoria(c), 1, Integer::sum);
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
        Map<String, Integer> retornosPor = new HashMap<>();
        List<Long> anteriores = lista.stream().map(Chamado::getRetornoDe).filter(java.util.Objects::nonNull).toList();
        Map<Long, Chamado> ant = new HashMap<>();
        chamados.findAllById(anteriores).forEach(p -> ant.put(p.getId(), p));
        int retornos = 0;
        for (Long id : anteriores) {
            retornos++;
            Chamado p = ant.get(id);
            String quem = p == null ? null : p.getFinalizadoPor() != null ? p.getFinalizadoPor() : p.getAtendidoPor();
            if (quem != null) retornosPor.merge(quem, 1, Integer::sum);
        }
        List<Map<String, Object>> ranking = new ArrayList<>();
        porAtendente.forEach((nome, a) -> {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("nome", nome);
            x.put("atendidos", a[0]);
            x.put("finalizados", a[1]);
            x.put("mediaRespostaMin", a[3] == 0 ? null : a[2] / a[3]);
            x.put("retornos", retornosPor.getOrDefault(nome, 0)); // chamados que ele finalizou e o cliente voltou em 24h
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
        out.put("retornos", retornos);
        out.put("descartados", todos.size() - lista.size());
        out.put("emAberto", lista.size() - finalizados);
        out.put("mediaRespostaMin", qtdResp == 0 ? null : somaResp / qtdResp);
        out.put("mediaSolucaoMin", qtdSol == 0 ? null : somaSol / qtdSol);
        out.put("ranking", ranking);
        out.put("porGrupo", grupos);
        List<Map<String, Object>> cats = new ArrayList<>();
        porCategoria.entrySet().stream().sorted((x, y) -> y.getValue() - x.getValue())
                .forEach(e -> cats.add(Map.of("categoria", e.getKey(), "chamados", e.getValue())));
        out.put("porCategoria", cats);
        if (comLista) out.put("chamados", lista.stream().map(this::json).toList());
        return out;
    }
}
