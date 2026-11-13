package kr.ac.chosun.mspace.assistant.mcp.campus.application;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CampusProperties.class)
class CampusConfig {
}
