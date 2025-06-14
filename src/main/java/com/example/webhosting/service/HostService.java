// HostService.java (완전한 코드)
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
        
        // 비동기로 실제 VirtualBox VM 생성
        return virtualBoxService.createVM(dto.getHostName())
            .thenApply(result -> {
                if (result.success) {
                    // VM 생성 성공 시 정보 저장
                    savedHost.setVmId(result.vmId);
                    savedHost.setVmName(result.vmName);
                    savedHost.setVmIP(result.vmIP);
                    savedHost.setPort80(result.port80);
                    savedHost.setPort22(result.port22);
                    savedHost.setStatus(Host.HostStatus.RUNNING);
                    savedHost.setUpdatedAt(LocalDateTime.now());
                    
                    // 실제 웹서버 설정 (비동기)
                    setupWebServer(result.vmIP, dto.getHostName(), result.port22);
                    
                    // 성공 로그
                    logVMCreationSuccess(result, dto.getHostName());
                } else {
                    // VM 생성 실패 시
                    savedHost.setStatus(Host.HostStatus.ERROR);
                    savedHost.setUpdatedAt(LocalDateTime.now());
                    
                    // 실패 로그
                    logVMCreationFailure(result, dto.getHostName());
                }
                
                Host updatedHost = hostRepository.save(savedHost);
                return HostResponseDto.from(updatedHost);
            });
    }
    
    private void setupWebServer(String vmIP, String hostName, int sshPort) {
        System.out.println("=== 실제 웹서버 설정 시작 ===");
        System.out.println("VM IP: " + vmIP);
        System.out.println("호스트명: " + hostName);
        System.out.println("SSH 포트: " + sshPort);
        
        // 비동기로 실제 웹서버 설정
        CompletableFuture.runAsync(() -> {
            try {
                System.out.println("VM 부팅 대기 중... (60초)");
                Thread.sleep(60000); // VM 부팅 대기 (1분)
                
                System.out.println("SSH를 통한 웹서버 설정 시작");
                provisioningService.setupWebServer(vmIP, hostName, sshPort);
                
                System.out.println("=== 웹서버 설정 완료 ===");
            } catch (Exception e) {
                System.err.println("웹서버 설정 실패: " + e.getMessage());
                e.printStackTrace();
            }
        });
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
                    if (success) {
                        hostRepository.delete(host);
                        System.out.println("=== VirtualBox VM 삭제 완료 ===");
                        System.out.println("호스트 '" + host.getHostName() + "' 삭제됨");
                    } else {
                        System.out.println("=== VirtualBox VM 삭제 실패 ===");
                        System.out.println("호스트: " + host.getHostName());
                    }
                    return success;
                });
        } else {
            // VM ID가 없는 경우 (생성 실패한 호스트)
            hostRepository.delete(host);
            System.out.println("호스트 데이터만 삭제됨: " + host.getHostName());
            return CompletableFuture.completedFuture(true);
        }
    }
    
    public CompletableFuture<String> getHostStatus(Long hostId, User user) {
        Host host = hostRepository.findByIdAndUser(hostId, user)
            .orElseThrow(() -> new IllegalArgumentException("호스트를 찾을 수 없습니다"));
        
        if (host.getVmId() != null) {
            return virtualBoxService.getVMStatus(host.getVmId())
                .thenApply(status -> {
                    // VM 상태를 Host 상태로 동기화
                    Host.HostStatus hostStatus;
                    switch (status) {
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
                    
                    return status;
                });
        } else {
            return CompletableFuture.completedFuture(host.getStatus().name());
        }
    }
}