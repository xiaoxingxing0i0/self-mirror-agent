package com.mirror.agent;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 自我镜像智能体（人格回溯 Agent）启动类。
 *
 * <p>硬性约束：禁止临床心理诊断；所有结论必须可溯源到用户原始私有数据。</p>
 */
@SpringBootApplication
@ConfigurationPropertiesScan("com.mirror.agent.config")
@MapperScan("com.mirror.agent.repository")
@EnableAsync
@EnableScheduling
public class SelfMirrorAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(SelfMirrorAgentApplication.class, args);
    }
}
