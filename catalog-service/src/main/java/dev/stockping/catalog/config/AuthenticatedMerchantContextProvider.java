package dev.stockping.catalog.config;

import dev.stockping.catalog.grpc.CatalogAuthenticationInterceptor;
import dev.stockping.security.Identity;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class AuthenticatedMerchantContextProvider implements MerchantContextProvider {
    @Override public UUID currentMerchantId() {
        Identity rpc = CatalogAuthenticationInterceptor.IDENTITY.get();
        return (rpc == null ? Identity.current() : rpc).merchantId();
    }
}
