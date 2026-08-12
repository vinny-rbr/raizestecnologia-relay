package com.raizestecnologia.relay.auth;

import java.util.Set;

/**
 * Mapa entre as rotas da API (repassadas ao agente) e os "modulos" de permissao.
 * Um usuario restrito (permissoes nao vazias) so acessa as rotas cujos modulos ele possui.
 * DONO e usuario sem restricao (permissoes vazias) passam por tudo.
 *
 * Chaves canonicas de modulo (as mesmas usadas no app):
 *   dashboard, pessoas, produtos, contagem, vendas, contas_pagar, contas_receber,
 *   fechamento, estatisticas, cancelamentos, fluxo_caixa, comparativo, compra_junto
 */
public final class Modulos {

    private Modulos() {}

    /** Todos os modulos conhecidos (para validar/pré-preencher no app). */
    public static final Set<String> TODOS = Set.of(
            "dashboard", "pessoas", "produtos", "contagem", "vendas",
            "contas_pagar", "contas_receber", "fechamento", "estatisticas",
            "cancelamentos", "fluxo_caixa", "comparativo", "compra_junto");

    /**
     * Modulos que dao acesso a esta rota (basta o usuario ter QUALQUER um deles).
     * Set vazio = rota livre (auth/version/push/empresas/admin/desconhecidas).
     */
    public static Set<String> requeridos(String method, String path) {
        if (path == null) return Set.of();
        String p = path;

        // Rotas de infra / sempre liberadas (admin ja e restrito a DONO no SecurityConfig)
        if (p.startsWith("/api/auth") || p.equals("/api/version") || p.startsWith("/api/push")
                || p.equals("/api/empresas") || p.equals("/api/health") || p.startsWith("/api/admin")) {
            return Set.of();
        }

        // Produtos / Contagem (a contagem precisa ler produtos)
        if (p.startsWith("/api/produtos")) {
            if ("POST".equalsIgnoreCase(method) && p.endsWith("/ajuste-estoque")) {
                return Set.of("contagem");
            }
            if (p.startsWith("/api/produtos/insights") || p.startsWith("/api/produtos/encalhados")
                    || p.startsWith("/api/produtos/curva-abc") || p.startsWith("/api/produtos/reposicao")) {
                return Set.of("produtos");
            }
            // lista / detalhe / count / barras: produtos OU contagem
            return Set.of("produtos", "contagem");
        }

        if (p.startsWith("/api/dashboard")) return Set.of("dashboard");
        if (p.startsWith("/api/pessoas")) return Set.of("pessoas");
        if (p.startsWith("/api/vendas")) return Set.of("vendas");
        if (p.startsWith("/api/contas-pagar")) return Set.of("contas_pagar");
        if (p.startsWith("/api/contas-receber")) return Set.of("contas_receber");
        if (p.startsWith("/api/caixa")) return Set.of("fechamento");
        if (p.startsWith("/api/estatisticas") || p.startsWith("/api/relatorio")) return Set.of("estatisticas");
        if (p.startsWith("/api/cancelamentos")) return Set.of("cancelamentos");
        if (p.startsWith("/api/fluxo-caixa")) return Set.of("fluxo_caixa");
        if (p.startsWith("/api/comparativo")) return Set.of("comparativo");
        if (p.startsWith("/api/compra-junto")) return Set.of("compra_junto");

        // desconhecida: nao bloqueia (evita quebrar por rota nova nao mapeada)
        return Set.of();
    }
}
