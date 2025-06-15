package com.example.webhosting.service;

import com.example.webhosting.entity.Host;
import com.example.webhosting.entity.User;
import com.example.webhosting.dto.HostCreationDto;
import com.example.webhosting.dto.HostResponseDto;
import com.example.webhosting.repository.HostRepository;
import com.example.webhosting.service.VirtualBoxService.VmCreationResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.scheduling.annotation.Async;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@Service
@Transactional
public class HostService {
    
    @Autowired
    private HostRepository hostRepository;
    
    @Autowired
    private VirtualBoxService virtualBoxService;
    
    @Autowired
    private VMProvisioningService provisioningService;
    
    public CompletableFuture<HostResponseDto> createHost(HostCreationDto dto, User user) {
        // 호스트명 중복 검사
        if (hostRepository.existsByHostNameAndUser(dto.getHostName(), user)) {
            throw new IllegalArgumentException("이미 존재하는 호스트명입니다");
        }
        
        // 호스트 생성 (초기 상태: CREATING)
        Host host = new Host();
        host.setHostName(dto.getHostName());
        host.setUser(user);
        host.setStatus(Host.HostStatus.CREATING);
        host = hostRepository.save(host);
        
        final Host savedHost = host;
        final Long userId = user.getId(); // 사용자 ID 저장
        
        // 비동기로 실제 VirtualBox VM 생성
        return virtualBoxService.createVM(dto.getHostName())
            .thenApply(result -> {
                // 비동기 콜백에서 SecurityContext 없이 처리
                return processVMCreationResult(result, dto.getHostName(), savedHost.getId(), userId);
            });
    }
    
    @Transactional
    public HostResponseDto processVMCreationResult(VmCreationResult result, String hostName, Long hostId, Long userId) {
        // 호스트 재조회 (비동기 처리에서는 엔티티가 detached 상태일 수 있음)
        Host savedHost = hostRepository.findById(hostId)
            .orElseThrow(() -> new IllegalArgumentException("호스트를 찾을 수 없습니다"));
        
        if (result.success) {
            // VM 생성 성공 시 정보 저장
            savedHost.setVmId(result.vmId);
            savedHost.setVmName(result.vmName);
            savedHost.setVmIP(result.vmIP);
            savedHost.setPort80(result.port80);
            savedHost.setPort22(result.port22);
            savedHost.setStatus(Host.HostStatus.RUNNING);
            savedHost.setUpdatedAt(LocalDateTime.now());
            
            // 성공 로그
            logVMCreationSuccess(result, hostName);
            
            // 웹서버 설정을 별도 스레드에서 비동기 실행 (SecurityContext 독립)
            setupWebServerAsync(result.vmIP, hostName, result.port22, hostId);
            
        } else {
            // VM 생성 실패 시
            savedHost.setStatus(Host.HostStatus.ERROR);
            savedHost.setUpdatedAt(LocalDateTime.now());
            
            // 실패 로그
            logVMCreationFailure(result, hostName);
        }
        
        Host updatedHost = hostRepository.save(savedHost);
        return HostResponseDto.from(updatedHost);
    }
    
    private void setupWebServerAsync(String vmIP, String hostName, int sshPort, Long hostId) {
        System.out.println("=== 실제 웹서버 설정 시작 ===");
        System.out.println("VM IP: " + vmIP);
        System.out.println("호스트명: " + hostName);
        System.out.println("SSH 포트: " + sshPort);
        
        // 완전히 독립적인 비동기 처리 (SecurityContext 없음)
        CompletableFuture.runAsync(() -> {
            try {
                System.out.println("VM 부팅 대기 중... (60초)");
                Thread.sleep(60000); // VM 부팅 대기 (1분)
                
                System.out.println("SSH를 통한 웹서버 설정 시작");
                provisioningService.setupWebServer(vmIP, hostName, sshPort);
                
                // 웹서버 설정 성공 시 호스트 상태 업데이트
                updateHostWebServerStatus(hostId, true);
                
                System.out.println("=== 웹서버 설정 완료 ===");
                
            } catch (Exception e) {
                System.err.println("웹서버 설정 실패: " + e.getMessage());
                e.printStackTrace();
                
                // 웹서버 설정 실패 시 호스트 상태 업데이트
                updateHostWebServerStatus(hostId, false);
            }
        });
    }
    
    @Transactional
    public void updateHostWebServerStatus(Long hostId, boolean success) {
        try {
            Host host = hostRepository.findById(hostId).orElse(null);
            if (host != null) {
                if (success) {
                    host.setStatus(Host.HostStatus.RUNNING);
                    System.out.println("호스트 " + host.getHostName() + " 웹서버 설정 완료");
                } else {
                    host.setStatus(Host.HostStatus.ERROR);
                    System.out.println("호스트 " + host.getHostName() + " 웹서버 설정 실패");
                }
                host.setUpdatedAt(LocalDateTime.now());
                hostRepository.save(host);
            }
        } catch (Exception e) {
            System.err.println("호스트 상태 업데이트 실패: " + e.getMessage());
        }
    }
    
    private void logVMCreationSuccess(VmCreationResult result, String hostName) {
        System.out.println("=== 실제 VirtualBox VM 생성 완료 ===");
        System.out.println("호스트명: " + hostName);
        System.out.println("VM ID: " + result.vmId);
        System.out.println("VM 이름: " + result.vmName);
        System.out.println("VM IP: " + result.vmIP);
        System.out.println("웹 포트 포워딩: localhost:" + result.port80 + " → VM:80");
        System.out.println("SSH 포트 포워딩: localhost:" + result.port22 + " → VM:22");
        System.out.println("웹 접속 URL: http://localhost:" + result.port80);
        System.out.println("SSH 접속: ssh -p " + result.port22 + " webuser@localhost");
        System.out.println("웹 디렉토리: /var/www/html/" + hostName);
        System.out.println("=== VM 설정 진행 중 ===");
    }
    
    private void logVMCreationFailure(VmCreationResult result, String hostName) {
        System.out.println("=== VirtualBox VM 생성 실패 ===");
        System.out.println("호스트명: " + hostName);
        System.out.println("오류 메시지: " + result.errorMessage);
        System.out.println("=== VM 생성 실패 ===");
    }
    
    public List<HostResponseDto> getUserHosts(User user) {
        return hostRepository.findByUserOrderByCreatedAtDesc(user)
            .stream()
            .map(HostResponseDto::from)
            .collect(Collectors.toList());
    }
    
    public HostResponseDto getHost(Long hostId, User user) {
        Host host = hostRepository.findByIdAndUser(hostId, user)
            .orElseThrow(() -> new IllegalArgumentException("호스트를 찾을 수 없습니다"));
        return HostResponseDto.from(host);
    }
    
    public CompletableFuture<Boolean> deleteHost(Long hostId, User user) {
        Host host = hostRepository.findByIdAndUser(hostId, user)
            .orElseThrow(() -> new IllegalArgumentException("호스트를 찾을 수 없습니다"));
        
        if (host.getVmId() != null) {
            System.out.println("=== 실제 VirtualBox VM 삭제 시작 ===");
            System.out.println("호스트명: " + host.getHostName());
            System.out.println("VM ID: " + host.getVmId());
            System.out.println("VM 이름: " + host.getVmName());
            
            return virtualBoxService.deleteVM(host.getVmId())
                .thenApply(success -> {
                    // 삭제 결과를 별도 트랜잭션에서 처리
                    return processVMDeletionResult(hostId, success, host.getHostName());
                });
        } else {
            // VM ID가 없는 경우 (생성 실패한 호스트)
            hostRepository.delete(host);
            System.out.println("호스트 데이터만 삭제됨: " + host.getHostName());
            return CompletableFuture.completedFuture(true);
        }
    }
    
    @Transactional
    public Boolean processVMDeletionResult(Long hostId, Boolean success, String hostName) {
        if (success) {
            hostRepository.deleteById(hostId);
            System.out.println("=== VirtualBox VM 삭제 완료 ===");
            System.out.println("호스트 '" + hostName + "' 삭제됨");
        } else {
            System.out.println("=== VirtualBox VM 삭제 실패 ===");
            System.out.println("호스트: " + hostName);
        }
        return success;
    }
    
    public CompletableFuture<String> getHostStatus(Long hostId, User user) {
        Host host = hostRepository.findByIdAndUser(hostId, user)
            .orElseThrow(() -> new IllegalArgumentException("호스트를 찾을 수 없습니다"));
        
        if (host.getVmId() != null) {
            return virtualBoxService.getVMStatus(host.getVmId())
                .thenApply(status -> {
                    // VM 상태를 Host 상태로 동기화
                    updateHostStatusFromVM(hostId, status);
                    return status;
                });
        } else {
            return CompletableFuture.completedFuture(host.getStatus().name());
        }
    }
    
    @Transactional
    public void updateHostStatusFromVM(Long hostId, String vmStatus) {
        try {
            Host host = hostRepository.findById(hostId).orElse(null);
            if (host != null) {
                Host.HostStatus hostStatus;
                switch (vmStatus) {
                    case "RUNNING":
                        hostStatus = Host.HostStatus.RUNNING;
                        break;
                    case "STOPPED":
                        hostStatus = Host.HostStatus.STOPPED;
                        break;
                    default:
                        hostStatus = Host.HostStatus.ERROR;
                }
                
                if (!host.getStatus().equals(hostStatus)) {
                    host.setStatus(hostStatus);
                    host.setUpdatedAt(LocalDateTime.now());
                    hostRepository.save(host);
                }
            }
        } catch (Exception e) {
            System.err.println("호스트 상태 동기화 실패: " + e.getMessage());
        }
    }
}