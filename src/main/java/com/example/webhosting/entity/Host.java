package com.example.webhosting.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.time.LocalDateTime;

@Entity
@Table(name = "hosts")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Host {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(nullable = false)
    private String hostName;
    
    private String vmId; // VirtualBox VM UUID
    private String vmName; // VirtualBox VM 이름
    private String vmIP; // VM IP 주소
    
    private Integer port80; // 웹서버 포트 포워딩
    private Integer port22; // SSH 포트 포워딩
    
    @Enumerated(EnumType.STRING)
    private HostStatus status = HostStatus.CREATING;
    
    private LocalDateTime createdAt = LocalDateTime.now();
    private LocalDateTime updatedAt = LocalDateTime.now();
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;
    
    public enum HostStatus {
        CREATING, RUNNING, STOPPED, ERROR
    }
}