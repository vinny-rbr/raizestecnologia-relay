package com.raizestecnologia.relay.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * user_empresa.cnpj nasceu com 14 caracteres, mas a loja de um CNPJ com varias lojas tem o
 * identificador CNPJ + numero da loja (ex.: "loja 5", ate 20 como loja.cnpj): vincular usuario
 * a ela dava "value too long". O ddl-auto: update nao aumenta coluna existente, entao aumenta aqui
 * (so quando ainda esta menor).
 */
@Component
public class AjusteSchemaUserEmpresa implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AjusteSchemaUserEmpresa.class);

    private final JdbcTemplate db;

    public AjusteSchemaUserEmpresa(JdbcTemplate db) {
        this.db = db;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            Integer tam = db.queryForObject(
                    "SELECT character_maximum_length FROM information_schema.columns "
                            + "WHERE table_name = 'user_empresa' AND column_name = 'cnpj'", Integer.class);
            if (tam != null && tam < 20) {
                db.execute("ALTER TABLE user_empresa ALTER COLUMN cnpj TYPE varchar(20)");
                log.info("user_empresa.cnpj aumentado de {} para 20", tam);
            }
        } catch (Exception e) {
            log.warn("Nao deu pra ajustar user_empresa.cnpj: {}", e.getMessage());
        }
    }
}
