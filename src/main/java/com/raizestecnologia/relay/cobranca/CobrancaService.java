package com.raizestecnologia.relay.cobranca;

import com.raizestecnologia.relay.loja.Loja;
import com.raizestecnologia.relay.loja.LojaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

/**
 * Regras de cobrança do lojista.
 * Fases: 1) IMPLANTAÇÃO R$50 (avulsa) → 2) 1ª mensalidade PROPORCIONAL ao uso (vence dia 5)
 * → 3) mensalidade cheia todo dia 5. Pagamento por Asaas (webhook) OU marcado manual (Pix/dinheiro).
 */
@Service
public class CobrancaService {

    private static final Logger log = LoggerFactory.getLogger(CobrancaService.class);
    private static final ZoneId BRT = ZoneId.of("America/Sao_Paulo");
    public static final double IMPLANTACAO = 50.0;
    public static final double MENSALIDADE_PADRAO = 30.0;
    private static final int DIA_COBRANCA = 5;
    /** Dias de tolerância após o vencimento antes de suspender por inadimplência. */
    private static final int TOLERANCIA_DIAS = 5;

    private final AsaasClient asaas;
    private final LojaRepository lojas;
    private final CobrancaLoteRepository lotes;
    private final PagamentoRepository pagamentos;

    public CobrancaService(AsaasClient asaas, LojaRepository lojas, CobrancaLoteRepository lotes,
                           PagamentoRepository pagamentos) {
        this.asaas = asaas;
        this.lojas = lojas;
        this.lotes = lotes;
        this.pagamentos = pagamentos;
    }

    // Diagnóstico do Asaas (só leitura).
    public boolean asaasEnabled() { return asaas.enabled(); }
    public String asaasBaseUrl() { return asaas.baseUrl(); }
    public String asaasContaNome() throws Exception { return asaas.contaNome(); }

    /** Quantos dias antes do vencimento a mensalidade "abre" para pagamento (evita pagar meses adiantado). */
    public static final int LIBERA_PAGAMENTO_DIAS = 10;

    /** Estado de cobrança atual da loja (o que está pendente agora). pagavel=false → ainda não abriu. */
    public record Estado(String fase, boolean implantacaoPaga, String item, double valor,
                         String vencimento, boolean primeiraMensalidade, boolean pagavel) {}

    /** A mensalidade só abre p/ pagamento LIBERA_PAGAMENTO_DIAS antes do vencimento (ou depois de vencida). */
    private static boolean abre(LocalDate vencimento, LocalDate hoje) {
        return !hoje.isBefore(vencimento.minusDays(LIBERA_PAGAMENTO_DIAS));
    }

    public Estado estado(Loja l) {
        double mens = l.getMensalidade() != null ? l.getMensalidade() : MENSALIDADE_PADRAO;
        int dia = l.getDiaVencimento();
        LocalDate ativ = l.getAtivadaEm() != null ? l.getAtivadaEm().atZone(BRT).toLocalDate() : LocalDate.now(BRT);
        LocalDate hoje = LocalDate.now(BRT);

        if (!l.isImplantacaoPaga()) {
            // vencimento definido pelo master; senão, folga de 3 dias após a instalação. Implantação sempre pagável.
            String venc = l.getImplantacaoVence() != null ? l.getImplantacaoVence().toString() : hoje.plusDays(3).toString();
            return new Estado("implantacao", false, "Implantação", IMPLANTACAO, venc, false, true);
        }
        LocalDate primeira = primeiroVenc(ativ, dia);
        if (l.getMensalidadePagaAte() == null) {
            long dias = ChronoUnit.DAYS.between(ativ, primeira);
            double valor = round2(mens * dias / 30.0);
            return new Estado("mensalidade", true, "1ª mensalidade (proporcional)", valor, primeira.toString(), true, abre(primeira, hoje));
        }
        LocalDate prox = proximoVenc(l.getMensalidadePagaAte().plusDays(1), dia);
        return new Estado("mensalidade", true, "Mensalidade", round2(mens), prox.toString(), false, abre(prox, hoje));
    }

    public record Resultado(String linkPagamento, double valor, String vencimento, String item, boolean assinaturaCriada) {}

    /** Gera a cobrança do item pendente (implantação ou mensalidade) no Asaas + assinatura mensal. */
    @Transactional
    public Resultado gerar(String cnpj, String email) throws Exception {
        if (!asaas.enabled()) throw new IllegalStateException("Asaas não configurado (defina ASAAS_API_KEY no servidor).");
        Loja l = carregar(cnpj);
        if (l.getRevendaCodigo() != null) {
            throw new IllegalArgumentException("O pagamento desta loja é feito pela revenda, não pelo aplicativo.");
        }
        if (l.getAtivadaEm() == null) l.setAtivadaEm(Instant.now());
        Estado est = estado(l);
        if (!est.pagavel()) {
            throw new IllegalArgumentException("A mensalidade abre para pagamento " + LIBERA_PAGAMENTO_DIAS
                    + " dias antes do vencimento (" + est.vencimento() + ").");
        }

        String cust = l.getAsaasCustomerId();
        if (cust == null || cust.isBlank()) {
            cust = asaas.criarCliente(l.getNome(), l.getCnpj(), email);
            l.setAsaasCustomerId(cust);
            l.setAsaasSubscriptionId(null);
        }
        String descricao = "Meu Giro - " + est.item();
        LocalDate venc = LocalDate.parse(est.vencimento());
        AsaasClient.Cobranca cob;
        try {
            cob = asaas.criarCobranca(cust, est.valor(), venc, descricao);
        } catch (AsaasClient.AsaasException e) {
            // Cliente pode estar inválido (ex.: id do sandbox depois de trocar p/ produção). Recria e tenta 1x.
            log.warn("[cobranca] cliente {} inválido ({}); recriando na conta atual", cust, e.getMessage());
            cust = asaas.criarCliente(l.getNome(), l.getCnpj(), email);
            l.setAsaasCustomerId(cust);
            l.setAsaasSubscriptionId(null); // assinatura antiga era do cliente antigo
            cob = asaas.criarCobranca(cust, est.valor(), venc, descricao);
        }

        // assinatura mensal (recorrência): cria uma vez, começando no dia 5 seguinte à mensalidade atual.
        boolean assinaturaCriada = false;
        if ((l.getAsaasSubscriptionId() == null || l.getAsaasSubscriptionId().isBlank())
                && "mensalidade".equals(est.fase())) {
            double mens = l.getMensalidade() != null ? l.getMensalidade() : MENSALIDADE_PADRAO;
            LocalDate proxAssinatura = proximoVenc(LocalDate.parse(est.vencimento()).plusDays(1), l.getDiaVencimento());
            String sub = asaas.criarAssinatura(cust, mens, proxAssinatura, "Meu Giro - Mensalidade");
            l.setAsaasSubscriptionId(sub);
            assinaturaCriada = true;
        }
        lojas.save(l);
        log.info("[cobranca] loja {} {} R$ {} venc {}", l.getCnpj(), est.item(), est.valor(), est.vencimento());
        return new Resultado(cob.linkPagamento(), est.valor(), est.vencimento(), est.item(), assinaturaCriada);
    }

    // ---- Cobrança combinada (várias lojas do mesmo cliente) ----

    /** Pendência atual de cada loja (pro cliente escolher o que pagar). */
    public java.util.List<java.util.Map<String, Object>> pendencias(java.util.Collection<String> cnpjs) {
        java.util.List<java.util.Map<String, Object>> out = new java.util.ArrayList<>();
        for (String raw : cnpjs) {
            String c = raw == null ? "" : raw.replaceAll("\\D", "");
            var lo = lojas.findById(c);
            if (lo.isEmpty()) continue;
            Loja l = lo.get();
            Estado est = estado(l);
            java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("cnpj", c);
            m.put("nome", l.getNome());
            m.put("fase", est.fase());
            m.put("item", est.item());
            m.put("valor", est.valor());
            m.put("vencimento", est.vencimento());
            m.put("pagavel", est.pagavel() && l.getRevendaCodigo() == null);
            m.put("gerenciadoPorRevenda", l.getRevendaCodigo() != null);
            m.put("bloqueada", l.isBloqueada());
            out.add(m);
        }
        return out;
    }

    /** Gera UM pagamento no Asaas somando as pendências das lojas escolhidas pelo cliente. */
    @Transactional
    public Resultado gerarLote(java.util.List<String> cnpjs, String email) throws Exception {
        if (!asaas.enabled()) throw new IllegalStateException("Asaas não configurado (defina ASAAS_API_KEY no servidor).");
        if (cnpjs == null || cnpjs.isEmpty()) throw new IllegalArgumentException("Selecione ao menos uma loja");
        java.util.List<Loja> sel = new java.util.ArrayList<>();
        for (String raw : cnpjs) {
            String c = raw == null ? "" : raw.replaceAll("\\D", "");
            lojas.findById(c).ifPresent(sel::add);
        }
        if (sel.isEmpty()) throw new IllegalArgumentException("Loja não encontrada");
        if (sel.size() == 1) return gerar(sel.get(0).getCnpj(), email); // uma só → fluxo normal

        double total = 0;
        StringBuilder itens = new StringBuilder();
        StringBuilder desc = new StringBuilder("Meu Giro (" + sel.size() + " lojas): ");
        for (Loja l : sel) {
            if (l.getAtivadaEm() == null) l.setAtivadaEm(Instant.now());
            Estado est = estado(l);
            if (!est.pagavel()) {
                throw new IllegalArgumentException("“" + l.getNome() + "” ainda não abriu para pagamento (abre "
                        + LIBERA_PAGAMENTO_DIAS + " dias antes do vencimento, " + est.vencimento() + ").");
            }
            total += est.valor();
            String tipo = "implantacao".equals(est.fase()) ? "IMPLANTACAO" : "MENSALIDADE";
            if (itens.length() > 0) itens.append(";");
            itens.append(l.getCnpj()).append(":").append(tipo);
        }
        desc.append("implantação/mensalidade");
        total = round2(total);

        Loja pagador = sel.get(0);
        String cust = pagador.getAsaasCustomerId();
        if (cust == null || cust.isBlank()) {
            cust = asaas.criarCliente(pagador.getNome(), pagador.getCnpj(), email);
            pagador.setAsaasCustomerId(cust);
        }
        LocalDate venc = LocalDate.now(BRT).plusDays(3);
        AsaasClient.Cobranca cob;
        try {
            cob = asaas.criarCobranca(cust, total, venc, desc.toString());
        } catch (AsaasClient.AsaasException e) {
            cust = asaas.criarCliente(pagador.getNome(), pagador.getCnpj(), email);
            pagador.setAsaasCustomerId(cust);
            pagador.setAsaasSubscriptionId(null);
            cob = asaas.criarCobranca(cust, total, venc, desc.toString());
        }
        lojas.saveAll(sel);
        lotes.save(new CobrancaLote(cob.id(), itens.toString(), total));
        log.info("[cobranca] lote {} - {} lojas - R$ {}", cob.id(), sel.size(), total);
        return new Resultado(cob.linkPagamento(), total, venc.toString(), sel.size() + " lojas", false);
    }

    /** Webhook confirmou o pagamento de um lote: dá baixa em todas as lojas dele. */
    @Transactional
    public boolean confirmarLote(String paymentId) {
        if (paymentId == null || paymentId.isBlank()) return false;
        var lo = lotes.findByAsaasPaymentId(paymentId);
        if (lo.isEmpty()) return false;
        CobrancaLote lote = lo.get();
        if (lote.isPago()) return true;
        for (String par : lote.getItens().split(";")) {
            String[] p = par.split(":");
            if (p.length < 2) continue;
            try {
                if ("IMPLANTACAO".equals(p[1])) marcarImplantacaoPaga(p[0], false);
                else marcarMensalidadePaga(p[0], false);
            } catch (Exception e) {
                log.warn("[cobranca] lote {}: falha na baixa da loja {}: {}", paymentId, p[0], e.getMessage());
            }
        }
        lote.setPago(true);
        lotes.save(lote);
        log.info("[cobranca] lote {} pago - lojas liberadas", paymentId);
        return true;
    }

    // ---- Baixa de pagamento (manual ou via webhook do Asaas) ----

    /** Marca a IMPLANTAÇÃO como paga (Pix/dinheiro ou Asaas) e libera a loja. */
    @Transactional
    public boolean marcarImplantacaoPaga(String cnpj, boolean manual) {
        boolean ok = apply(cnpj, l -> {
            l.setImplantacaoPaga(true);
            l.setImplantacaoPagaEm(Instant.now());
            liberar(l);
        }, manual ? "implantacao_paga_manual" : "implantacao_paga_asaas");
        lojas.findById(cnpj.replaceAll("\\D", "")).ifPresent(l -> pagamentos.save(new Pagamento(
                l.getCnpj(), "Implantação", IMPLANTACAO, null, manual ? "manual" : "asaas")));
        return ok;
    }

    // ---- Revenda: o revendedor paga R$30/mês/loja ao dono ----
    public static final double REVENDA_MENSALIDADE = 30.0;

    /** Prepara a loja instalada por revendedor pro ciclo de R$30/mês (dia 5, sem implantação
     *  separada; 1º mês coberto pela ativação). Depois disso o auto-bloqueio já cuida do resto. */
    @Transactional
    public void ativarRevendaStore(String cnpj) {
        apply(cnpj, l -> {
            if (l.getAtivadaEm() == null) l.setAtivadaEm(Instant.now());
            l.setDiaVencimento(DIA_COBRANCA);
            l.setMensalidade(REVENDA_MENSALIDADE);
            l.setImplantacaoPaga(true);
            LocalDate ativ = l.getAtivadaEm().atZone(BRT).toLocalDate();
            l.setMensalidadePagaAte(primeiroVenc(ativ, DIA_COBRANCA));
            liberar(l);
        }, "revenda_ciclo_ativado");
    }

    /** Revendedor pagou os R$30 do mês desta loja ao dono: dá baixa (avança o ciclo) e libera. */
    @Transactional
    public void revendaPagou(String cnpj) {
        marcarMensalidadePaga(cnpj, true);
    }

    public record ResultadoRevenda(String linkPagamento, double valor, String vencimento, String custId) {}

    /** Gera UM pagamento (boleto/Pix) somando R$30 × N lojas do revendedor. Ao pagar (webhook),
     *  cada loja tem o ciclo avançado e é liberada (via CobrancaLote → confirmarLote). */
    @Transactional
    public ResultadoRevenda cobrarRevenda(String custId, String nome, String doc, String email,
                                          java.util.List<String> cnpjs) throws Exception {
        if (!asaas.enabled()) throw new IllegalStateException("Asaas não configurado (defina ASAAS_API_KEY).");
        java.util.List<Loja> sel = new java.util.ArrayList<>();
        for (String raw : cnpjs) {
            String c = raw == null ? "" : raw.replaceAll("\\D", "");
            lojas.findById(c).ifPresent(sel::add);
        }
        if (sel.isEmpty()) throw new IllegalArgumentException("Selecione ao menos uma loja");
        double total = round2(REVENDA_MENSALIDADE * sel.size());
        String desc = "Meu Giro (revenda) - " + sel.size() + " loja(s)";
        if (custId == null || custId.isBlank()) custId = asaas.criarCliente(nome, doc, email);
        LocalDate venc = LocalDate.now(BRT).plusDays(3);
        AsaasClient.Cobranca cob;
        try {
            cob = asaas.criarCobranca(custId, total, venc, desc);
        } catch (AsaasClient.AsaasException e) {
            custId = asaas.criarCliente(nome, doc, email);
            cob = asaas.criarCobranca(custId, total, venc, desc);
        }
        StringBuilder itens = new StringBuilder();
        for (Loja l : sel) {
            if (itens.length() > 0) itens.append(";");
            itens.append(l.getCnpj()).append(":MENSALIDADE");
        }
        lotes.save(new CobrancaLote(cob.id(), itens.toString(), total));
        log.info("[cobranca] revenda lote {} - {} lojas - R$ {}", cob.id(), sel.size(), total);
        return new ResultadoRevenda(cob.linkPagamento(), total, venc.toString(), custId);
    }

    /** Marca a MENSALIDADE atual como paga: avança o "pago até" para o próximo dia 5. */
    @Transactional
    public boolean marcarMensalidadePaga(String cnpj, boolean manual) {
        boolean ok = apply(cnpj, l -> {
            int dia = l.getDiaVencimento();
            LocalDate ativ = l.getAtivadaEm() != null ? l.getAtivadaEm().atZone(BRT).toLocalDate() : LocalDate.now(BRT);
            LocalDate novaAte = l.getMensalidadePagaAte() == null
                    ? primeiroVenc(ativ, dia)
                    : proximoVenc(l.getMensalidadePagaAte().plusDays(1), dia);
            l.setMensalidadePagaAte(novaAte);
            liberar(l);
        }, manual ? "mensalidade_paga_manual" : "mensalidade_paga_asaas");
        lojas.findById(cnpj.replaceAll("\\D", "")).ifPresent(l -> pagamentos.save(new Pagamento(
                l.getCnpj(), "Mensalidade",
                l.getMensalidade() != null ? l.getMensalidade() : MENSALIDADE_PADRAO,
                l.getMensalidadePagaAte(), manual ? "manual" : "asaas")));
        return ok;
    }

    /** Parcelas pagas de uma loja (mais recente primeiro). */
    public java.util.List<java.util.Map<String, Object>> historico(String cnpj) {
        String c = cnpj == null ? "" : cnpj.replaceAll("\\D", "");
        java.util.List<java.util.Map<String, Object>> out = new java.util.ArrayList<>();
        for (Pagamento p : pagamentos.findByCnpjOrderByPagoEmDesc(c)) {
            java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("item", p.getItem());
            m.put("valor", p.getValor());
            m.put("competencia", p.getCompetencia() == null ? null : p.getCompetencia().toString());
            m.put("pagoEm", p.getPagoEm() == null ? null : p.getPagoEm().toString());
            m.put("forma", p.getForma());
            out.add(m);
        }
        return out;
    }

    /** Webhook do Asaas confirmou pagamento: dá baixa no item pendente (implantação → mensalidade). */
    @Transactional
    public boolean confirmarPagamentoAsaas(String customerId) {
        if (customerId == null || customerId.isBlank()) return false;
        Optional<Loja> lo = lojas.findByAsaasCustomerId(customerId);
        if (lo.isEmpty()) return false;
        Loja l = lo.get();
        if (!l.isImplantacaoPaga()) {
            l.setImplantacaoPaga(true);
            l.setImplantacaoPagaEm(Instant.now());
        } else {
            int dia = l.getDiaVencimento();
            LocalDate ativ = l.getAtivadaEm() != null ? l.getAtivadaEm().atZone(BRT).toLocalDate() : LocalDate.now(BRT);
            l.setMensalidadePagaAte(l.getMensalidadePagaAte() == null
                    ? primeiroVenc(ativ, dia)
                    : proximoVenc(l.getMensalidadePagaAte().plusDays(1), dia));
        }
        liberar(l);
        lojas.save(l);
        log.info("[cobranca] pagamento Asaas confirmado - loja {} liberada", l.getCnpj());
        return true;
    }

    /** Agendado: verifica inadimplência 2 min após subir e depois a cada 6h. */
    @org.springframework.scheduling.annotation.Scheduled(initialDelay = 120_000, fixedRate = 21_600_000)
    void agendarBloqueioInadimplentes() {
        try {
            bloquearInadimplentes();
        } catch (Exception e) {
            log.warn("[cobranca] falha no auto-bloqueio agendado: {}", e.getMessage());
        }
    }

    /** Suspende automaticamente quem não pagou até TOLERANCIA_DIAS após o vencimento (dia por loja). */
    @Transactional
    public int bloquearInadimplentes() {
        LocalDate hoje = LocalDate.now(BRT);
        int n = 0;
        for (Loja l : lojas.findAll()) {
            if (l.isBloqueada() || l.getAtivadaEm() == null) continue;
            int dia = l.getDiaVencimento();
            LocalDate ativ = l.getAtivadaEm().atZone(BRT).toLocalDate();
            LocalDate primeira = primeiroVenc(ativ, dia);
            // vencimento do ciclo atual = último dia de vencimento que já passou
            LocalDate venc = hoje.withDayOfMonth(dia);
            if (venc.isAfter(hoje)) venc = venc.minusMonths(1).withDayOfMonth(dia);
            if (venc.isBefore(primeira)) continue; // cliente novo: ainda não teve cobrança vencida
            LocalDate corte = venc.plusDays(TOLERANCIA_DIAS);
            if (hoje.isBefore(corte)) continue; // ainda no prazo de tolerância
            boolean mensalidadePaga = l.getMensalidadePagaAte() != null && !l.getMensalidadePagaAte().isBefore(venc);
            if (!l.isImplantacaoPaga() || !mensalidadePaga) {
                l.setBloqueada(true);
                l.setMotivoBloqueio("Pagamento em atraso — venceu dia " + dia + " e não foi recebido. Regularize para reativar.");
                lojas.save(l);
                n++;
                log.info("[cobranca] auto-bloqueio por inadimplência - loja {}", l.getCnpj());
            }
        }
        if (n > 0) log.info("[cobranca] {} loja(s) suspensa(s) por inadimplência", n);
        return n;
    }

    private boolean apply(String cnpj, java.util.function.Consumer<Loja> fn, String motivo) {
        Loja l = carregar(cnpj);
        fn.accept(l);
        lojas.save(l);
        log.info("[cobranca] {} - loja {}", motivo, l.getCnpj());
        return true;
    }

    private void liberar(Loja l) {
        l.setUltimoPagamento(Instant.now());
        l.setBloqueada(false);
        l.setMotivoBloqueio(null);
    }

    private Loja carregar(String cnpj) {
        String c = cnpj == null ? "" : cnpj.replaceAll("\\D", "");
        return lojas.findById(c).orElseThrow(() -> new IllegalArgumentException("Loja não encontrada"));
    }

    /** 1º vencimento depois da instalação (instalou antes do dia → mesmo mês; senão mês seguinte). */
    private static LocalDate primeiroVenc(LocalDate ativacao, int dia) {
        LocalDate d = ativacao.withDayOfMonth(dia);
        if (!ativacao.isBefore(d)) d = d.plusMonths(1).withDayOfMonth(dia);
        return d;
    }

    private static LocalDate proximoVenc(LocalDate from, int dia) {
        LocalDate d = from.withDayOfMonth(dia);
        if (d.isBefore(from)) d = from.plusMonths(1).withDayOfMonth(dia);
        return d;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
