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
    /** Dias de tolerância após o vencimento antes de suspender por inadimplência. */
    private static final int TOLERANCIA_DIAS = 5;

    private final AsaasClient asaas;
    private final LojaRepository lojas;

    public CobrancaService(AsaasClient asaas, LojaRepository lojas) {
        this.asaas = asaas;
        this.lojas = lojas;
    }

    // Diagnóstico do Asaas (só leitura).
    public boolean asaasEnabled() { return asaas.enabled(); }
    public String asaasBaseUrl() { return asaas.baseUrl(); }
    public String asaasContaNome() throws Exception { return asaas.contaNome(); }

    /** Estado de cobrança atual da loja (o que está pendente agora). */
    public record Estado(String fase, boolean implantacaoPaga, String item, double valor,
                         String vencimento, boolean primeiraMensalidade) {}

    public Estado estado(Loja l) {
        double mens = l.getMensalidade() != null ? l.getMensalidade() : MENSALIDADE_PADRAO;
        int dia = l.getDiaVencimento();
        LocalDate ativ = l.getAtivadaEm() != null ? l.getAtivadaEm().atZone(BRT).toLocalDate() : LocalDate.now(BRT);
        LocalDate hoje = LocalDate.now(BRT);

        if (!l.isImplantacaoPaga()) {
            // implantação é "à vista" (na instalação); dá uma folga de 3 dias no vencimento.
            return new Estado("implantacao", false, "Implantação", IMPLANTACAO, hoje.plusDays(3).toString(), false);
        }
        LocalDate primeira = primeiroVenc(ativ, dia);
        if (l.getMensalidadePagaAte() == null) {
            long dias = ChronoUnit.DAYS.between(ativ, primeira);
            double valor = round2(mens * dias / 30.0);
            return new Estado("mensalidade", true, "1ª mensalidade (proporcional)", valor, primeira.toString(), true);
        }
        LocalDate prox = proximoVenc(l.getMensalidadePagaAte().plusDays(1), dia);
        return new Estado("mensalidade", true, "Mensalidade", round2(mens), prox.toString(), false);
    }

    public record Resultado(String linkPagamento, double valor, String vencimento, String item, boolean assinaturaCriada) {}

    /** Gera a cobrança do item pendente (implantação ou mensalidade) no Asaas + assinatura mensal. */
    @Transactional
    public Resultado gerar(String cnpj, String email) throws Exception {
        if (!asaas.enabled()) throw new IllegalStateException("Asaas não configurado (defina ASAAS_API_KEY no servidor).");
        Loja l = carregar(cnpj);
        if (l.getAtivadaEm() == null) l.setAtivadaEm(Instant.now());
        Estado est = estado(l);

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

    // ---- Baixa de pagamento (manual ou via webhook do Asaas) ----

    /** Marca a IMPLANTAÇÃO como paga (Pix/dinheiro ou Asaas) e libera a loja. */
    @Transactional
    public boolean marcarImplantacaoPaga(String cnpj, boolean manual) {
        return apply(cnpj, l -> {
            l.setImplantacaoPaga(true);
            l.setImplantacaoPagaEm(Instant.now());
            liberar(l);
        }, manual ? "implantacao_paga_manual" : "implantacao_paga_asaas");
    }

    /** Marca a MENSALIDADE atual como paga: avança o "pago até" para o próximo dia 5. */
    @Transactional
    public boolean marcarMensalidadePaga(String cnpj, boolean manual) {
        return apply(cnpj, l -> {
            int dia = l.getDiaVencimento();
            LocalDate ativ = l.getAtivadaEm() != null ? l.getAtivadaEm().atZone(BRT).toLocalDate() : LocalDate.now(BRT);
            LocalDate novaAte = l.getMensalidadePagaAte() == null
                    ? primeiroVenc(ativ, dia)
                    : proximoVenc(l.getMensalidadePagaAte().plusDays(1), dia);
            l.setMensalidadePagaAte(novaAte);
            liberar(l);
        }, manual ? "mensalidade_paga_manual" : "mensalidade_paga_asaas");
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
