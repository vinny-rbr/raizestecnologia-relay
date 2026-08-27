package com.raizestecnologia.relay.notify;

/** Disparado quando um agente de loja (re)conecta no relay. [revenda] = codigo do revendedor (opcional). */
public record AgentConnectedEvent(String cnpj, String nome, String revenda) {}
