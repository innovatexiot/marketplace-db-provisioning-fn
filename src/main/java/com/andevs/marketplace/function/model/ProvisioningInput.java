package com.andevs.marketplace.function.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ProvisioningInput(String clientName) {
}
