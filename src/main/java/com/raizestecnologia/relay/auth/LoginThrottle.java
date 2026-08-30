package com.raizestecnologia.relay.auth;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Anti força-bruta simples (em memória): após MAX falhas de login numa janela,
 * bloqueia novas tentativas daquela chave (e-mail) por JANELA_MS. Sucesso zera.
 */
@Component
public class LoginThrottle {

    private static final int MAX = 6;
    private static final long JANELA_MS = 15 * 60 * 1000L; // 15 min

    // chave -> [falhas, inicioDaJanelaMs]
    private final ConcurrentHashMap<String, long[]> tentativas = new ConcurrentHashMap<>();

    private static String norm(String chave) {
        return chave == null ? "" : chave.trim().toLowerCase();
    }

    public boolean bloqueado(String chave) {
        long[] t = tentativas.get(norm(chave));
        if (t == null) return false;
        if (System.currentTimeMillis() - t[1] > JANELA_MS) { tentativas.remove(norm(chave)); return false; }
        return t[0] >= MAX;
    }

    public void falhou(String chave) {
        String k = norm(chave);
        long agora = System.currentTimeMillis();
        tentativas.compute(k, (kk, t) ->
                (t == null || agora - t[1] > JANELA_MS) ? new long[]{1, agora} : new long[]{t[0] + 1, t[1]});
    }

    public void ok(String chave) {
        tentativas.remove(norm(chave));
    }
}
