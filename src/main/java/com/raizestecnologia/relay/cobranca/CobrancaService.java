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

/**
 * Regras de cobrança do lojista via Asaas: gera a fatura atual (implantação +
 * proporcional na 1ª; mensalidade nas demais) e cria a assinatura mensal (dia 5).
 * O pagamento é confirmado pelo webhook, que desbloqueia a loja.
 */
@Service
public class CobrancaService {

    private static final Logger log = LoggerFactory.getLogger(CobrancaService.class);
    private static final ZoneId BRT = ZoneId.of("America/Sao_Paulo");
    public static final double IMPLANTACAO = 50.0;
    public static final double MENSALIDADE_PADRAO = 30.0;
    private static final int DIA_COBRANCA = 5;

    private final AsaasClient asaas;
    private final LojaRepository lojas;

    public CobrancaService(AsaasClient asaas, LojaRepository lojas) {
        this.asaas = asaas;
        this.lojas = lojas;
    }

    public record Resultado(String linkPagamento, double valor, String vencimento, boolean primeira, boolean assinaturaCriada) {}

    /** Gera a cobrança da fatura atual + a assinatura mensal (se ainda não houver). */
    @Transactional
    public Resultado gerar(String cnpj, String email) throws Exception {
        if (!asaas.enabled()) throw new IllegalStateException("Asaas não configurado (defina ASAAS_API_KEY no servidor).");
        String c = cnpj == null ? "" : cnpj.replaceAll("\\D", "");
        Loja l = lojas.findById(c).orElseThrow(() -> new IllegalArgumentException("Loja não encontrada"));

        // garante data de início (base do proporcional)
        if (l.getAtivadaEm() == null) l.setAtivadaEm(Instant.now());
        LocalDate ativ = l.getAtivadaEm().atZone(BRT).toLocalDate();
        LocalDate hoje = LocalDate.now(BRT);
        double mens = l.getMensalidade() != null ? l.getMensalidade() : MENSALIDADE_PADRAO;

        // 1a cobrança = primeiro dia 5 depois da instalação
        LocalDate primeiraData = ativ.withDayOfMonth(DIA_COBRANCA);
        if (!ativ.isBefore(primeiraData)) primeiraData = primeiraData.plusMonths(1);
        boolean ehPrimeira = !hoje.isAfter(primeiraData);

        LocalDate vencimento;
        double valor;
        String descricao;
        if (ehPrimeira) {
            vencimento = primeiraData;
            long dias = ChronoUnit.DAYS.between(ativ, primeiraData);
            double proporcional = round2(mens * dias / 30.0);
            valor = round2(IMPLANTACAO + proporcional);
            descricao = "Meu Giro - Implantação (R$ " + IMPLANTACAO + ") + " + dias + " dias proporcionais";
        } else {
            vencimento = proximoDia5(hoje);
            valor = round2(mens);
            descricao = "Meu Giro - Mensalidade";
        }

        // garante cliente no Asaas
        String cust = l.getAsaasCustomerId();
        if (cust == null || cust.isBlank()) {
            cust = asaas.criarCliente(l.getNome(), c, email);
            l.setAsaasCustomerId(cust);
        }

        // fatura atual
        AsaasClient.Cobranca cob = asaas.criarCobranca(cust, valor, vencimento, descricao);

        // assinatura mensal (recorrência a partir do mês seguinte à fatura gerada)
        boolean assinaturaCriada = false;
        if (l.getAsaasSubscriptionId() == null || l.getAsaasSubscriptionId().isBlank()) {
            LocalDate proxAssinatura = vencimento.plusMonths(1);
            String sub = asaas.criarAssinatura(cust, mens, proxAssinatura, "Meu Giro - Mensalidade");
            l.setAsaasSubscriptionId(sub);
            assinaturaCriada = true;
        }
        lojas.save(l);
        log.info("[cobranca] loja {} cobranca {} R$ {} venc {} (assinatura={})", c, cob.id(), valor, vencimento, l.getAsaasSubscriptionId());
        return new Resultado(cob.linkPagamento(), valor, vencimento.toString(), ehPrimeira, assinaturaCriada);
    }

    /** Webhook confirmou pagamento: registra e desbloqueia a loja daquele cliente. */
    @Transactional
    public boolean confirmarPagamento(String customerId) {
        if (customerId == null || customerId.isBlank()) return false;
        return lojas.findByAsaasCustomerId(customerId).map(l -> {
            l.setUltimoPagamento(Instant.now());
            l.setBloqueada(false);
            l.setMotivoBloqueio(null);
            lojas.save(l);
            log.info("[cobranca] pagamento confirmado - loja {} desbloqueada", l.getCnpj());
            return true;
        }).orElse(false);
    }

    private static LocalDate proximoDia5(LocalDate from) {
        LocalDate d = from.withDayOfMonth(DIA_COBRANCA);
        if (d.isBefore(from)) d = from.plusMonths(1).withDayOfMonth(DIA_COBRANCA);
        return d;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
