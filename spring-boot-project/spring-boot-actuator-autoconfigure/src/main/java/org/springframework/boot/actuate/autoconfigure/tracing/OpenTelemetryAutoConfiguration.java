/*
 * Copyright 2012-2023 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.boot.actuate.autoconfigure.tracing;

import java.util.Collections;
import java.util.List;

import io.micrometer.tracing.SpanCustomizer;
import io.micrometer.tracing.exporter.SpanExportingPredicate;
import io.micrometer.tracing.exporter.SpanFilter;
import io.micrometer.tracing.exporter.SpanReporter;
import io.micrometer.tracing.otel.bridge.CompositeSpanExporter;
import io.micrometer.tracing.otel.bridge.EventListener;
import io.micrometer.tracing.otel.bridge.EventPublishingContextWrapper;
import io.micrometer.tracing.otel.bridge.OtelBaggageManager;
import io.micrometer.tracing.otel.bridge.OtelCurrentTraceContext;
import io.micrometer.tracing.otel.bridge.OtelPropagator;
import io.micrometer.tracing.otel.bridge.OtelSpanCustomizer;
import io.micrometer.tracing.otel.bridge.OtelTracer;
import io.micrometer.tracing.otel.bridge.OtelTracer.EventPublisher;
import io.micrometer.tracing.otel.bridge.Slf4JBaggageEventListener;
import io.micrometer.tracing.otel.bridge.Slf4JEventListener;
import io.micrometer.tracing.otel.propagation.BaggageTextMapPropagator;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.ContextStorage;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SdkTracerProviderBuilder;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import io.opentelemetry.semconv.resource.attributes.ResourceAttributes;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.SpringBootVersion;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * {@link EnableAutoConfiguration Auto-configuration} for OpenTelemetry.
 *
 * @author Moritz Halbritter
 * @author Marcin Grzejszczak
 * @author Yanming Zhou
 * @since 3.0.0
 */
@AutoConfiguration(before = MicrometerTracingAutoConfiguration.class)
@ConditionalOnEnabledTracing
@ConditionalOnClass({ OtelTracer.class, SdkTracerProvider.class, OpenTelemetry.class })
@EnableConfigurationProperties(TracingProperties.class)
public class OpenTelemetryAutoConfiguration {

	/**
	 * Default value for application name if {@code spring.application.name} is not set.
	 */
	private static final String DEFAULT_APPLICATION_NAME = "application";

	private final TracingProperties tracingProperties;

	OpenTelemetryAutoConfiguration(TracingProperties tracingProperties) {
		this.tracingProperties = tracingProperties;
	}

	@Bean
	@ConditionalOnMissingBean
	OpenTelemetry openTelemetry(SdkTracerProvider sdkTracerProvider, ContextPropagators contextPropagators) {
		return OpenTelemetrySdk.builder()
			.setTracerProvider(sdkTracerProvider)
			.setPropagators(contextPropagators)
			.build();
	}

	@Bean
	@ConditionalOnMissingBean
	SdkTracerProvider otelSdkTracerProvider(Environment environment, ObjectProvider<SpanProcessor> spanProcessors,
			Sampler sampler, ObjectProvider<SdkTracerProviderBuilderCustomizer> customizers) {
		String applicationName = environment.getProperty("spring.application.name", DEFAULT_APPLICATION_NAME);
		SdkTracerProviderBuilder builder = SdkTracerProvider.builder()
			.setSampler(sampler)
			.setResource(Resource.create(Attributes.of(ResourceAttributes.SERVICE_NAME, applicationName)));
		spanProcessors.orderedStream().forEach(builder::addSpanProcessor);
		customizers.orderedStream().forEach((customizer) -> customizer.customize(builder));
		return builder.build();
	}

	@Bean
	@ConditionalOnMissingBean
	ContextPropagators otelContextPropagators(ObjectProvider<TextMapPropagator> textMapPropagators) {
		return ContextPropagators.create(TextMapPropagator.composite(textMapPropagators.orderedStream().toList()));
	}

	@Bean
	@ConditionalOnMissingBean
	Sampler otelSampler() {
		Sampler rootSampler = Sampler.traceIdRatioBased(this.tracingProperties.getSampling().getProbability());
		return Sampler.parentBased(rootSampler);
	}

	@Bean
	SpanProcessor otelSpanProcessor(ObjectProvider<SpanExporter> spanExporters,
			ObjectProvider<SpanExportingPredicate> spanExportingPredicates, ObjectProvider<SpanReporter> spanReporters,
			ObjectProvider<SpanFilter> spanFilters) {
		return BatchSpanProcessor
			.builder(new CompositeSpanExporter(spanExporters.orderedStream().toList(),
					spanExportingPredicates.orderedStream().toList(), spanReporters.orderedStream().toList(),
					spanFilters.orderedStream().toList()))
			.build();
	}

	@Bean
	@ConditionalOnMissingBean
	Tracer otelTracer(OpenTelemetry openTelemetry) {
		return openTelemetry.getTracer("org.springframework.boot", SpringBootVersion.getVersion());
	}

	@Bean
	@ConditionalOnMissingBean(io.micrometer.tracing.Tracer.class)
	OtelTracer micrometerOtelTracer(Tracer tracer, EventPublisher eventPublisher,
			OtelCurrentTraceContext otelCurrentTraceContext) {
		return new OtelTracer(tracer, otelCurrentTraceContext, eventPublisher,
				new OtelBaggageManager(otelCurrentTraceContext, this.tracingProperties.getBaggage().getRemoteFields(),
						Collections.emptyList()));
	}

	@Bean
	@ConditionalOnMissingBean
	OtelPropagator otelPropagator(ContextPropagators contextPropagators, Tracer tracer) {
		return new OtelPropagator(contextPropagators, tracer);
	}

	@Bean
	@ConditionalOnMissingBean
	EventPublisher otelTracerEventPublisher(List<EventListener> eventListeners) {
		return new OTelEventPublisher(eventListeners);
	}

	@Bean
	@ConditionalOnMissingBean
	OtelCurrentTraceContext otelCurrentTraceContext(EventPublisher publisher) {
		ContextStorage.addWrapper(new EventPublishingContextWrapper(publisher));
		return new OtelCurrentTraceContext();
	}

	@Bean
	@ConditionalOnMissingBean
	Slf4JEventListener otelSlf4JEventListener() {
		return new Slf4JEventListener();
	}

	@Bean
	@ConditionalOnMissingBean(SpanCustomizer.class)
	OtelSpanCustomizer otelSpanCustomizer() {
		return new OtelSpanCustomizer();
	}

	@Configuration(proxyBeanMethods = false)
	@ConditionalOnProperty(prefix = "management.tracing.baggage", name = "enabled", matchIfMissing = true)
	static class BaggageConfiguration {

		private final TracingProperties tracingProperties;

		BaggageConfiguration(TracingProperties tracingProperties) {
			this.tracingProperties = tracingProperties;
		}

		@Bean
		TextMapPropagator textMapPropagatorWithBaggage(OtelCurrentTraceContext otelCurrentTraceContext) {
			List<String> remoteFields = this.tracingProperties.getBaggage().getRemoteFields();
			BaggageTextMapPropagator baggagePropagator = new BaggageTextMapPropagator(remoteFields,
					new OtelBaggageManager(otelCurrentTraceContext, remoteFields, Collections.emptyList()));
			return CompositeTextMapPropagator.create(baggagePropagator,
					this.tracingProperties.getPropagation().getEffectiveProducedTypes(),
					this.tracingProperties.getPropagation().getEffectiveConsumedTypes());
		}

		@Bean
		@ConditionalOnMissingBean
		@ConditionalOnProperty(prefix = "management.tracing.baggage.correlation", name = "enabled",
				matchIfMissing = true)
		Slf4JBaggageEventListener otelSlf4JBaggageEventListener() {
			return new Slf4JBaggageEventListener(this.tracingProperties.getBaggage().getCorrelation().getFields());
		}

	}

	@Configuration(proxyBeanMethods = false)
	@ConditionalOnProperty(prefix = "management.tracing.baggage", name = "enabled", havingValue = "false")
	static class NoBaggageConfiguration {

		@Bean
		TextMapPropagator textMapPropagator(TracingProperties properties) {
			return CompositeTextMapPropagator.create(properties.getPropagation().getEffectiveProducedTypes(),
					properties.getPropagation().getEffectiveConsumedTypes());
		}

	}

	static class OTelEventPublisher implements EventPublisher {

		private final List<EventListener> listeners;

		OTelEventPublisher(List<EventListener> listeners) {
			this.listeners = listeners;
		}

		@Override
		public void publishEvent(Object event) {
			for (EventListener listener : this.listeners) {
				listener.onEvent(event);
			}
		}

	}

}
