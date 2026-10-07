package com.raizestecnologia.relay.revenda;

import com.raizestecnologia.relay.cobranca.CobrancaService;
import com.raizestecnologia.relay.loja.Loja;
import com.raizestecnologia.relay.loja.LojaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Máquina com o MESMO CNPJ da própria revenda (teste/uso interno dela) não é cobrada:
 * fica marcada como cortesia (sem R$30, sem bloqueio, fora do boleto).
 * Roda ao subir e a cada 10 min; o ativar também chama {@link #aplicar} na hora.
 */
@Component
public class CortesiaRevenda {

    private static final Logger log = LoggerFactory.getLogger(CortesiaRevenda.class);

    private final LojaRepository lojas;
    private final RevendaRepository revendas;

    public CortesiaRevenda(LojaRepository lojas, RevendaRepository revendas) {
        this.lojas = lojas;
        this.revendas = revendas;
    }

    /** A loja é da própria revenda (CNPJ igual ao da revenda dona)? */
    public boolean propria(Loja l) {
        if (l.getRevendaCodigo() == null) return false;
        Revenda r = revendas.findByCodigo(l.getRevendaCodigo()).orElse(null);
        if (r == null || r.getCpfCnpj() == null) return false;
        String doc = r.getCpfCnpj().replaceAll("\\D", "");
        return !doc.isBlank() && doc.equals(l.getCnpjReal());
    }

    /** Acerta a marca de cortesia; se virou cortesia, tira bloqueio por pagamento e o prazo de 24h. */
    @Transactional
    public boolean aplicar(Loja l) {
        boolean p = propria(l);
        boolean mudou = p != l.isCortesia();
        l.setCortesia(p);
        if (p) {
            if (l.getRevendaLiberadaAte() != null) { l.setRevendaLiberadaAte(null); mudou = true; }
            if (CobrancaService.bloqueioPorPagamento(l)) {
                l.setBloqueada(false);
                l.setMotivoBloqueio(null);
                mudou = true;
            }
        }
        if (mudou) {
            lojas.save(l);
            log.info("[cortesia] loja {} ({}) cortesia={}", l.getCnpj(), l.getNome(), p);
        }
        return p;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Scheduled(initialDelay = 600_000, fixedDelay = 600_000)
    @Transactional
    public void varrer() {
        try {
            for (Loja l : lojas.findAll()) {
                if (l.getRevendaCodigo() != null || l.isCortesia()) aplicar(l);
            }
        } catch (Exception e) {
            log.warn("[cortesia] falha ao varrer: {}", e.getMessage());
        }
    }
}
