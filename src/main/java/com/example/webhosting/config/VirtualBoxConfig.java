package com.example.webhosting.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "virtualbox")
@Data
public class VirtualBoxConfig {
    private String host = "localhost";
    private int port = 18083;
    private String username = "";
    private String password = "";
    
    private Vm vm = new Vm();
    private Network network = new Network();
    private Ssh ssh = new Ssh();
    
    @Data
    public static class Vm {
        private String baseName = "webhosting";
        private String baseImage = "/opt/ubuntu-20.04-server.iso";
        private int memory = 1024; // MB
        private int diskSize = 8192; // MB
    }
    
    @Data
    public static class Network {
        private int startPort = 8000;
        private int endPort = 8999;
    }
    
    @Data
    public static class Ssh {
        private int startPort = 2200;
        private int endPort = 2999;
    }
}
