package com.example.webhosting.service;

import com.example.webhosting.entity.Host;
import com.example.webhosting.entity.User;
import com.example.webhosting.dto.HostCreationDto;
import com.example.webhosting.dto.HostResponseDto;
import com.example.webhosting.repository.HostRepository;
import com.example.webhosting.service.VirtualMachineService.VmCreationResult;
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
    private VirtualMachineService vmService;
    
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
        
        // 비동기로 VM 생성
        return vmService.createVM(dto.getHostName())
            .thenApply(result -> {
                if (result.success) {
                    savedHost.setVmId(result.vmId);
                    savedHost.setPort80(result.port80);
                    savedHost.setPort22(result.port22);
                    savedHost.setStatus(Host.HostStatus.RUNNING);
                    savedHost.setUpdatedAt(LocalDateTime.now());
                    
                    // 웹서버 설정 시뮬레이션
                    setupWebServer(result.vmId, result.port80);
                } else {
                    savedHost.setStatus(Host.HostStatus.ERROR);
                    savedHost.setUpdatedAt(LocalDateTime.now());
                }
                
                Host updatedHost = hostRepository.save(savedHost);
                return HostResponseDto.from(updatedHost);
            });
    }
    
    private void setupWebServer(String vmId, int port) {
        // 실제로는 VM에 Nginx/Apache 설정하는 로직
        System.out.println("=== 웹서버 설정 시작 ===");
        System.out.println("VM ID: " + vmId);
        System.out.println("VM 내부 포트: 80 (Nginx/Apache)");
        System.out.println("호스트 포워딩 포트: " + port);
        System.out.println("웹 루트 디렉토리: /var/www/html");
        System.out.println("SSH 접속 (VM 내부 22번 포트)");
        System.out.println("웹 접속 URL: http://localhost:" + port);
        System.out.println("=== 웹서버 설정 완료 ===");
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
            return vmService.deleteVM(host.getVmId())
                .thenApply(success -> {
                    if (success) {
                        hostRepository.delete(host);
                    }
                    return success;
                });
        } else {
            hostRepository.delete(host);
            return CompletableFuture.completedFuture(true);
        }
    }
}