package com.raizestecnologia.relay.audit;

import com.raizestecnologia.relay.auth.CurrentUser;
import com.raizestecnologia.relay.auth.RelayPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Registra eventos de auditoria. Nunca lanca excecao para o chamador — auditoria
 * falhando jamais pode derrubar a operacao principal (login, ajuste, etc.).
 */
@Service
public class AuditoriaService {

    private static final Logger log = LoggerFactory.getLogger(AuditoriaService.class);

    private final AuditoriaRepository repo;

    public AuditoriaService(AuditoriaRepository repo) {
        this.repo = repo;
    }

    /** Registro completo. */
    public void registrar(Long userId, String email, String nome, String cnpj, String acao, String detalhe) {
        try {
            repo.save(new Auditoria(userId, email, nome, cnpj, acao, corta(detalhe)));
        } catch (Exception e) {
            log.warn("[auditoria] falha ao registrar '{}': {}", acao, e.getMessage());
        }
    }

    /** Usa o usuario autenticado do request atual (para acoes via relay). */
    public void registrarAtual(String cnpj, String acao, String detalhe) {
        RelayPrincipal p = CurrentUser.get();
        if (p == null) return;
        Long id = null;
        try { id = Long.valueOf(p.userId()); } catch (Exception ignore) {}
        registrar(id, p.email(), null, cnpj, acao, detalhe);
    }

    private static String corta(String s) {
        if (s == null) return null;
        return s.length() > 1000 ? s.substring(0, 1000) : s;
    }
}
