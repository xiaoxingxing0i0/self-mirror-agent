package com.mirror.agent.config;

import io.milvus.client.MilvusServiceClient;
import io.milvus.param.ConnectParam;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * Milvus 2.4 客户端配置。连接生命周期由 Spring 管理（禁止手工 new 横切组件）。
 */
@Configuration
@RequiredArgsConstructor
public class MilvusConfig {

    private final MilvusProperties milvusProperties;

    private MilvusServiceClient milvusServiceClient;

    @Bean
    public MilvusServiceClient milvusServiceClient() {
        ConnectParam connectParam = ConnectParam.newBuilder()
                .withHost(milvusProperties.getHost())
                .withPort(milvusProperties.getPort())
                .withConnectTimeout(10_000L, TimeUnit.MILLISECONDS)
                .build();
        this.milvusServiceClient = new MilvusServiceClient(connectParam);
        return this.milvusServiceClient;
    }

    @PreDestroy
    public void close() {
        if (milvusServiceClient != null) {
            milvusServiceClient.close();
        }
    }
}
