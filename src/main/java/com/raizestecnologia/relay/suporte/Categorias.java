package com.raizestecnologia.relay.suporte;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tipo de problema do chamado pelas palavras que o cliente escreveu (sem acento, minúsculas).
 * Vence a categoria com mais palavras encontradas; empate fica com a que vem primeiro na lista.
 */
final class Categorias {

    static final String OUTROS = "Outros";

    private static final Map<String, List<String>> PALAVRAS = new LinkedHashMap<>();
    static {
        PALAVRAS.put("Bomba / integração do posto", List.of("bomba", "bico", "encerrante", "concentrador", "abastec", "integra"));
        PALAVRAS.put("Nota fiscal / SEFAZ", List.of("nota", "nfe", "nfc", "nf-e", "nfce", "rejeic", "sefaz", "xml", "cupom fiscal", "sat ", "contingencia", "danfe", "cfop", "ncm"));
        PALAVRAS.put("Cartão / TEF / Pix", List.of("tef", "cartao", "maquininha", "pix", "pagpix", "stone", "cielo", "rede ", "getnet", "credito", "debito"));
        PALAVRAS.put("Impressora", List.of("impressora", "imprim", "bobina", "nao sai o cupom", "nao imprime"));
        PALAVRAS.put("Etiqueta / balança", List.of("etiqueta", "balanca", "etiquetadora", "argox", "elgin"));
        PALAVRAS.put("Usuário / senha / acesso", List.of("senha", "usuario", "login", "acesso", "permiss", "bloquead"));
        PALAVRAS.put("Cadastro / preço / estoque", List.of("cadastr", "preco", "estoque", "produto", "codigo de barras", "promocao"));
        PALAVRAS.put("Relatório / financeiro", List.of("relatorio", "fechamento", "financeiro", "contas a", "boleto", "dre", "caixa do dia"));
        PALAVRAS.put("Lento / sem conexão", List.of("lent", "demor", "fora do ar", "nao conecta", "sem internet", "internet", "servidor", "conexao", "offline"));
        PALAVRAS.put("Caixa / PDV travado", List.of("trav", "pdv", "nao abre", "congel", "fechou sozinho", "tela preta", "nao liga", "erro"));
        PALAVRAS.put("Backup / banco de dados", List.of("backup", "banco de dados", "firebird", "postgres"));
    }

    private Categorias() {}

    /** Todas as categorias (pra escolher no painel), "Outros" por último. */
    static List<String> nomes() {
        List<String> l = new java.util.ArrayList<>(PALAVRAS.keySet());
        l.add(OUTROS);
        return l;
    }

    static String de(String textoCliente) {
        String t = " " + SuporteService.normalizar(textoCliente) + " ";
        String melhor = OUTROS;
        int max = 0;
        for (var e : PALAVRAS.entrySet()) {
            int n = 0;
            for (String p : e.getValue()) if (t.contains(p)) n++;
            if (n > max) { max = n; melhor = e.getKey(); }
        }
        return melhor;
    }
}
