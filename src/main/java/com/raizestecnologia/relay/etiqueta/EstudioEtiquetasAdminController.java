package com.raizestecnologia.relay.etiqueta;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Estudio de Etiquetas do master: /api/admin/etiquetas/{cnpj}/{acao} (admin ja e so DONO no SecurityConfig). */
@RestController
@RequestMapping("/api/admin/etiquetas")
public class EstudioEtiquetasAdminController {

    private final EstudioEtiquetas estudio;

    public EstudioEtiquetasAdminController(EstudioEtiquetas estudio) {
        this.estudio = estudio;
    }

    @RequestMapping(value = "/{cnpj}/{acao}", method = {RequestMethod.GET, RequestMethod.POST})
    public ResponseEntity<String> acao(@PathVariable String cnpj, @PathVariable String acao,
                                       @RequestParam(required = false) String q,
                                       @RequestBody(required = false) String body,
                                       jakarta.servlet.http.HttpServletRequest req) {
        return estudio.repassar(cnpj, req.getMethod(), acao, q, body);
    }
}
