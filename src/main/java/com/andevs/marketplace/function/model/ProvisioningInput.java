package com.andevs.marketplace.function.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ProvisioningInput(
    String clientName,
    String password
) {
    
    public ProvisioningInput {
        if (clientName == null || clientName.trim().isEmpty()) {
            throw new IllegalArgumentException("clientName no puede ser null o vacío");
        }
        if (password == null || password.trim().isEmpty()) {
            throw new IllegalArgumentException("password no puede ser null o vacío");
        }
    }
    
    /**
     * Genera el nombre de la base de datos basado en el nombre del cliente
     * Convierte a minúsculas y reemplaza espacios con guiones bajos
     */
    public String getDatabaseName() {
        return clientName.toLowerCase().replaceAll("\\s+", "_");
    }
    
    /**
     * Genera el nombre de usuario basado en el nombre de la base de datos
     */
    public String getUserName() {
        return getDatabaseName() + "_user";
    }
}
