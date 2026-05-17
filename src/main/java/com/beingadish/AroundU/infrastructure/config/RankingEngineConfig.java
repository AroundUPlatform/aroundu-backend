package com.beingadish.AroundU.infrastructure.config;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PreDestroy;
import java.util.concurrent.TimeUnit;

@Configuration
public class RankingEngineConfig {

    @Value("${ranking-engine.host:localhost}")
    private String host;

    @Value("${ranking-engine.port:50052}")
    private int port;

    @Value("${ranking-engine.enabled:false}")
    private boolean enabled;

    private ManagedChannel channel;

    @Bean
    public ManagedChannel rankingEngineChannel() {
        if (!enabled) {
            return null;
        }
        channel = ManagedChannelBuilder.forAddress(host, port)
                .usePlaintext()
                .keepAliveTime(30, TimeUnit.SECONDS)
                .keepAliveTimeout(5, TimeUnit.SECONDS)
                .maxInboundMessageSize(4 * 1024 * 1024)
                .build();
        return channel;
    }

    @Bean
    public boolean rankingEngineEnabled() {
        return enabled;
    }

    @PreDestroy
    public void shutdown() {
        if (channel != null && !channel.isShutdown()) {
            channel.shutdown();
            try {
                if (!channel.awaitTermination(5, TimeUnit.SECONDS)) {
                    channel.shutdownNow();
                }
            } catch (InterruptedException e) {
                channel.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }
}
