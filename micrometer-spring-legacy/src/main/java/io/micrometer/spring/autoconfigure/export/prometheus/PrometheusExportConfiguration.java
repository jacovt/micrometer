/**
 * Copyright 2017 Pivotal Software, Inc.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micrometer.spring.autoconfigure.export.prometheus;

import io.micrometer.core.annotation.Incubating;
import io.micrometer.core.instrument.Clock;
import io.micrometer.prometheus.PrometheusConfig;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import io.micrometer.spring.autoconfigure.export.StringToDurationConverter;
import io.prometheus.client.CollectorRegistry;
import io.prometheus.client.exporter.PushGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.autoconfigure.ManagementContextConfiguration;
import org.springframework.boot.actuate.endpoint.AbstractEndpoint;
import org.springframework.boot.autoconfigure.condition.AllNestedConditions;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;

import java.net.UnknownHostException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Configuration for exporting metrics to Prometheus.
 *
 * @author Jon Schneider
 */
@Configuration
@ConditionalOnClass(PrometheusMeterRegistry.class)
@EnableConfigurationProperties(PrometheusProperties.class)
@Import(StringToDurationConverter.class)
public class PrometheusExportConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public PrometheusConfig prometheusConfig(PrometheusProperties props) {
        return new PrometheusPropertiesConfigAdapter(props);
    }

    @Bean
    @ConditionalOnProperty(value = "management.metrics.export.prometheus.enabled", matchIfMissing = true)
    @ConditionalOnMissingBean
    public PrometheusMeterRegistry prometheusMeterRegistry(PrometheusConfig config, CollectorRegistry collectorRegistry,
                                                           Clock clock) {
        return new PrometheusMeterRegistry(config, collectorRegistry, clock);
    }

    @Bean
    @ConditionalOnMissingBean
    public CollectorRegistry collectorRegistry() {
        return new CollectorRegistry(true);
    }

    @ManagementContextConfiguration
    @ConditionalOnClass(AbstractEndpoint.class)
    public static class PrometheusScrapeEndpointConfiguration {
        @Bean
        public PrometheusScrapeEndpoint prometheusEndpoint(
                CollectorRegistry collectorRegistry) {
            return new PrometheusScrapeEndpoint(collectorRegistry);
        }

        @Bean
        public PrometheusScrapeMvcEndpoint prometheusMvcEndpoint(PrometheusScrapeEndpoint delegate) {
            return new PrometheusScrapeMvcEndpoint(delegate);
        }
    }

    static class PrometheusPushGatewayEnabledCondition extends AllNestedConditions  {
        public PrometheusPushGatewayEnabledCondition() {
            super(ConfigurationPhase.PARSE_CONFIGURATION);
        }

        @ConditionalOnProperty(value = "management.metrics.export.prometheus.enabled", matchIfMissing = true)
        static class PrometheusMeterRegistryEnabled {
            //
        }

        @ConditionalOnProperty("management.metrics.export.prometheus.pushgateway.enabled")
        static class PushGatewayEnabled {
            //
        }
    }

    /**
     * Configuration for <a href="https://github.com/prometheus/pushgateway">Prometheus Pushgateway</a>.
     *
     * @author David J. M. Karlsen
     */
    @Configuration
    @ConditionalOnClass(PushGateway.class)
    @Conditional(PrometheusPushGatewayEnabledCondition.class)
    @Incubating(since = "1.0.0")
    public class PrometheusPushGatewayConfiguration {
        private final Logger logger = LoggerFactory.getLogger(PrometheusPushGatewayConfiguration.class);
        private final CollectorRegistry collectorRegistry;
        private final PrometheusProperties.PushgatewayProperties pushgatewayProperties;
        private final PushGateway pushGateway;
        private final Environment environment;
        private final ScheduledFuture<?> pushFuture;

        PrometheusPushGatewayConfiguration(CollectorRegistry collectorRegistry, PrometheusProperties prometheusProperties,
                                           Environment environment) {
            this.collectorRegistry = collectorRegistry;
            this.pushgatewayProperties = prometheusProperties.getPushgateway();
            this.pushGateway = new PushGateway(pushgatewayProperties.getBaseUrl());
            this.environment = environment;
            this.pushFuture = Executors.newSingleThreadScheduledExecutor()
                    .scheduleAtFixedRate(this::push, pushgatewayProperties.getPushRate().toMillis(),
                            pushgatewayProperties.getPushRate().toMillis(), TimeUnit.MILLISECONDS);
        }

        void push() {
            try {
                pushGateway.pushAdd(collectorRegistry, job(), pushgatewayProperties.getGroupingKeys());
            } catch (UnknownHostException e) {
                logger.error("Unable to locate host '" + pushgatewayProperties.getBaseUrl() + "'. No longer attempting metrics publication to this host");
                pushFuture.cancel(false);
            } catch (Throwable t) {
                logger.error("Unable to push metrics to Prometheus Pushgateway", t);
            }
        }

        private String job() {
            String job = pushgatewayProperties.getJob();
            if (job == null) {
                job = environment.getProperty("spring.application.name");
            }
            if (job == null) {
                // There's a history of Prometheus spring integration defaulting the job name to "spring" from when
                // Prometheus integration didn't exist in Spring itself.
                job = "spring";
            }
            return job;
        }
    }
}
