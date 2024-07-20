package io.quarkus.keycloak.pep.runtime;

import static io.quarkus.keycloak.pep.runtime.KeycloakPolicyEnforcerUtil.createPolicyEnforcer;
import static io.quarkus.keycloak.pep.runtime.KeycloakPolicyEnforcerUtil.getOidcTenantConfig;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

import jakarta.enterprise.inject.Instance;
import jakarta.inject.Singleton;

import org.keycloak.adapters.authorization.PolicyEnforcer;

import io.quarkus.arc.InjectableInstance;
import io.quarkus.keycloak.pep.PolicyEnforcerResolver;
import io.quarkus.keycloak.pep.TenantPolicyConfigResolver;
import io.quarkus.oidc.OidcTenantConfig;
import io.quarkus.oidc.runtime.BlockingTaskRunner;
import io.quarkus.oidc.runtime.OidcConfig;
import io.quarkus.security.spi.runtime.BlockingSecurityExecutor;
import io.quarkus.tls.TlsConfiguration;
import io.quarkus.tls.TlsConfigurationRegistry;
import io.quarkus.vertx.http.runtime.HttpConfiguration;
import io.smallrye.mutiny.Uni;
import io.vertx.ext.web.RoutingContext;

@Singleton
public class DefaultPolicyEnforcerResolver implements PolicyEnforcerResolver {

    private final TenantPolicyConfigResolver dynamicConfigResolver;
    private final BlockingTaskRunner<KeycloakPolicyEnforcerTenantConfig> requestContext;
    private final Map<String, PolicyEnforcer> namedPolicyEnforcers;
    private final PolicyEnforcer defaultPolicyEnforcer;
    private final long readTimeout;
    private final boolean globalTrustAll;

    DefaultPolicyEnforcerResolver(OidcConfig oidcConfig, KeycloakPolicyEnforcerConfig config,
            HttpConfiguration httpConfiguration, BlockingSecurityExecutor blockingSecurityExecutor,
            Instance<TenantPolicyConfigResolver> configResolver,
            InjectableInstance<TlsConfigurationRegistry> tlsConfigRegistryInstance) {
        this.readTimeout = httpConfiguration.readTimeout.toMillis();

        if (tlsConfigRegistryInstance.isResolvable()) {
            this.globalTrustAll = tlsConfigRegistryInstance.get().getDefault().map(TlsConfiguration::isTrustAll).orElse(false);
        } else {
            this.globalTrustAll = false;
        }

        this.defaultPolicyEnforcer = createPolicyEnforcer(oidcConfig.defaultTenant, config.defaultTenant(), globalTrustAll);
        this.namedPolicyEnforcers = createNamedPolicyEnforcers(oidcConfig, config, globalTrustAll);
        if (configResolver.isResolvable()) {
            this.dynamicConfigResolver = configResolver.get();
            this.requestContext = new BlockingTaskRunner<>(blockingSecurityExecutor);
        } else {
            this.dynamicConfigResolver = null;
            this.requestContext = null;
        }
    }

    @Override
    public Uni<PolicyEnforcer> resolvePolicyEnforcer(RoutingContext routingContext, OidcTenantConfig tenantConfig) {
        if (tenantConfig == null) {
            return Uni.createFrom().item(defaultPolicyEnforcer);
        }
        if (dynamicConfigResolver == null) {
            return Uni.createFrom().item(getStaticPolicyEnforcer(tenantConfig.tenantId.get()));
        } else {
            return getDynamicPolicyEnforcer(routingContext, tenantConfig)
                    .onItem().ifNull().continueWith(new Supplier<PolicyEnforcer>() {
                        @Override
                        public PolicyEnforcer get() {
                            return getStaticPolicyEnforcer(tenantConfig.tenantId.get());
                        }
                    });
        }
    }

    @Override
    public long getReadTimeout() {
        return readTimeout;
    }

    PolicyEnforcer getStaticPolicyEnforcer(String tenantId) {
        return tenantId != null && namedPolicyEnforcers.containsKey(tenantId)
                ? namedPolicyEnforcers.get(tenantId)
                : defaultPolicyEnforcer;
    }

    boolean hasDynamicPolicyEnforcers() {
        return dynamicConfigResolver != null;
    }

    private Uni<PolicyEnforcer> getDynamicPolicyEnforcer(RoutingContext routingContext, OidcTenantConfig config) {
        return dynamicConfigResolver.resolve(routingContext, config, requestContext)
                .onItem().ifNotNull().transform(new Function<KeycloakPolicyEnforcerTenantConfig, PolicyEnforcer>() {
                    @Override
                    public PolicyEnforcer apply(KeycloakPolicyEnforcerTenantConfig tenant) {
                        return createPolicyEnforcer(config, tenant, globalTrustAll);
                    }
                });
    }

    private static Map<String, PolicyEnforcer> createNamedPolicyEnforcers(OidcConfig oidcConfig,
            KeycloakPolicyEnforcerConfig config, boolean tlsConfigTrustAll) {
        if (config.namedTenants().isEmpty()) {
            return Map.of();
        }

        Map<String, PolicyEnforcer> policyEnforcerTenants = new HashMap<>();
        for (Map.Entry<String, KeycloakPolicyEnforcerTenantConfig> tenant : config.namedTenants().entrySet()) {
            OidcTenantConfig oidcTenantConfig = getOidcTenantConfig(oidcConfig, tenant.getKey());
            policyEnforcerTenants.put(tenant.getKey(),
                    createPolicyEnforcer(oidcTenantConfig, tenant.getValue(), tlsConfigTrustAll));
        }
        return Map.copyOf(policyEnforcerTenants);
    }
}
