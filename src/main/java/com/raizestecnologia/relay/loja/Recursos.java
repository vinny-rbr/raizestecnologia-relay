package com.raizestecnologia.relay.loja;

import java.util.Set;

/**
 * Recursos extras que o master ou a revenda liberam por loja no site. Sem liberar, o app nao
 * mostra o atalho e a central recusa as rotas.
 */
public final class Recursos {

    public static final String SALAO = "salao";
    public static final String PAINEL_TV = "painel_tv";
    public static final String FORCA_VENDAS = "forca_vendas";
    /** Estudio de Etiquetas (Host): layout das etiquetas de gondola e impressao pelo painel. */
    public static final String ETIQUETAS = "etiquetas";
    public static final Set<String> TODOS = Set.of(SALAO, PAINEL_TV, FORCA_VENDAS, ETIQUETAS);

    private Recursos() {}

    public static String nome(String r) {
        if (SALAO.equals(r)) return "Salao";
        if (PAINEL_TV.equals(r)) return "Painel TV";
        if (FORCA_VENDAS.equals(r)) return "Forca de vendas";
        if (ETIQUETAS.equals(r)) return "Estudio de Impressao";
        return r;
    }

    /**
     * Recurso exigido por uma rota repassada ao agente (null = livre). /api/salao/disponivel fica
     * livre: o app usa so pra saber se a loja e Link (central de relatorios).
     */
    public static String daRota(String path) {
        if (path == null) return null;
        // cadastro de cliente (Pessoas > Novo cliente) usa as rotas da forca de vendas, mas vale pra qualquer loja Host
        if (path.startsWith("/api/forca/clientes") || path.equals("/api/forca/padroes") || path.startsWith("/api/forca/cidades")) return null;
        if (path.startsWith("/api/forca/") || path.equals("/api/forca")) return FORCA_VENDAS;
        if (path.startsWith("/api/etiquetas/") || path.equals("/api/etiquetas")) return ETIQUETAS;
        if (path.startsWith("/api/salao/") && !path.equals("/api/salao/disponivel")) return SALAO;
        return null;
    }
}
