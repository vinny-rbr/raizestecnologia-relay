package com.raizestecnologia.relay;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Servidor central (nuvem) da Raizes Tecnologia.
 * Faz a ponte entre o app publicado e os agentes instalados no PC de cada loja.
 */
@SpringBootApplication
public class RelayApplication {
    public static void main(String[] args) {
        SpringApplication.run(RelayApplication.class, args);
    }
}
