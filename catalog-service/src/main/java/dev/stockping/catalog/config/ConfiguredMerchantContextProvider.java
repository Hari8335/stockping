package dev.stockping.catalog.config;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
@Component
public class ConfiguredMerchantContextProvider implements MerchantContextProvider {
    private final UUID merchantId;
    public ConfiguredMerchantContextProvider(@Value("${catalog.demo-merchant-id}") UUID merchantId) { this.merchantId = merchantId; }
    @Override public UUID currentMerchantId() { return merchantId; }
}
