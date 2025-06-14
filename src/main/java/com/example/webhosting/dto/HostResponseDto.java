package com.example.webhosting.dto;

import lombok.Data;
import lombok.AllArgsConstructor;
import com.example.webhosting.entity.Host;
import java.time.LocalDateTime;

@Data
@AllArgsConstructor
public class HostResponseDto {
    private Long id;
    private String hostName;
    private String vmId;
    private String vmIP;
    private Integer port80;
    private Integer port22;
    private Host.HostStatus status;
    private LocalDateTime createdAt;
    private String webUrl; // 웹 접속 URL
    private String sshCommand; // SSH 접속 명령어
    
    public static HostResponseDto from(Host host) {
        String webUrl = null;
        String sshCommand = null;
        
        if (host.getPort80() != null && host.getStatus() == Host.HostStatus.RUNNING) {
            webUrl = "http://localhost:" + host.getPort80();
        }
        
        if (host.getPort22() != null && host.getVmIP() != null) {
            sshCommand = "ssh -p " + host.getPort22() + " webuser@localhost";
        }
        
        return new HostResponseDto(
            host.getId(),
            host.getHostName(),
            host.getVmId(),
            host.getVmIP(),
            host.getPort80(),
            host.getPort22(),
            host.getStatus(),
            host.getCreatedAt(),
            webUrl,
            sshCommand
        );
    }
}