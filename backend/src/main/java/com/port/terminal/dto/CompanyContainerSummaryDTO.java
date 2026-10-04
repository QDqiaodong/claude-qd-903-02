package com.port.terminal.dto;

public class CompanyContainerSummaryDTO {
    private final String shippingCompany;
    private final Long containerCount;
    private final Long teu;

    public CompanyContainerSummaryDTO(String shippingCompany, Long containerCount, Long teu) {
        this.shippingCompany = shippingCompany;
        this.containerCount = containerCount;
        this.teu = teu;
    }

    public String getShippingCompany() { return shippingCompany; }
    public Long getContainerCount() { return containerCount; }
    public Long getTeu() { return teu; }
}
