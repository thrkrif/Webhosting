package com.example.webhosting.service;

import com.example.webhosting.config.VirtualBoxConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

@Service
@Slf4j
public class VirtualBoxService {
    
    @Autowired
    private VirtualBoxConfig config;
    
    @Autowired
    private VMProvisioningService provisioningService;
    
    // 포트 할당을 위한 카운터
    private final AtomicInteger portCounter = new AtomicInteger(8000);
    private final AtomicInteger sshPortCounter = new AtomicInteger(2200);
    
    public CompletableFuture<VmCreationResult> createVM(String hostName) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                log.info("VirtualBox VM 생성 시작: {}", hostName);
                
                String vmName = config.getVm().getBaseName() + "-" + hostName + "-" + System.currentTimeMillis();
                
                // 1. VM 생성
                String vmId = createVirtualMachine(vmName);
                if (vmId == null) {
                    throw new RuntimeException("VM 생성 실패");
                }
                
                // 2. 포트 할당
                int webPort = allocateWebPort();
                int sshPort = allocateSSHPort();
                
                // 3. 네트워크 설정
                setupNetworking(vmName, webPort, sshPort);
                
                // 4. VM 시작
                startVM(vmName);
                
                // 5. VM IP 대기 및 획득
                String vmIP = waitForVMIP(vmName);
                
                // 6. 웹서버 설정 (비동기)
                CompletableFuture.runAsync(() -> {
                    try {
                        Thread.sleep(60000); // VM 부팅 대기 (1분)
                        provisioningService.setupWebServer(vmIP, hostName, sshPort);
                    } catch (Exception e) {
                        log.error("웹서버 설정 실패", e);
                    }
                });
                
                log.info("VM 생성 완료 - ID: {}, IP: {}, Web: {}, SSH: {}", vmId, vmIP, webPort, sshPort);
                
                return new VmCreationResult(vmId, vmName, vmIP, webPort, sshPort, true, null);
                
            } catch (Exception e) {
                log.error("VirtualBox VM 생성 실패", e);
                return new VmCreationResult(null, null, null, 0, 0, false, e.getMessage());
            }
        });
    }
    
    private String createVirtualMachine(String vmName) {
        try {
            log.info("VM 생성 중: {}", vmName);
            
            // VirtualBox VM 생성 명령어
            ProcessBuilder pb = new ProcessBuilder(
                "VBoxManage", "createvm",
                "--name", vmName,
                "--ostype", "Ubuntu_64",
                "--register"
            );
            
            Process process = pb.start();
            int exitCode = process.waitFor();
            
            if (exitCode != 0) {
                throw new RuntimeException("VM 생성 실패: exit code " + exitCode);
            }
            
            // VM 설정
            configureVM(vmName);
            
            // VM UUID 반환
            return getVMUUID(vmName);
            
        } catch (Exception e) {
            log.error("VM 생성 오류", e);
            return null;
        }
    }
    
    private void configureVM(String vmName) throws Exception {
        log.info("VM 설정 중: {}", vmName);
        
        // 메모리 설정
        executeVBoxCommand("modifyvm", vmName, "--memory", String.valueOf(config.getVm().getMemory()));
        
        // 하드디스크 생성
        String diskPath = "/tmp/" + vmName + ".vdi";
        executeVBoxCommand("createhd", "--filename", diskPath, "--size", String.valueOf(config.getVm().getDiskSize()));
        
        // 스토리지 컨트롤러 추가
        executeVBoxCommand("storagectl", vmName, "--name", "SATA", "--add", "sata", "--controller", "IntelAhci");
        
        // 하드디스크 연결
        executeVBoxCommand("storageattach", vmName, "--storagectl", "SATA", "--port", "0", "--device", "0", "--type", "hdd", "--medium", diskPath);
        
        // Ubuntu ISO 연결 (DVD)
        executeVBoxCommand("storagectl", vmName, "--name", "IDE", "--add", "ide");
        executeVBoxCommand("storageattach", vmName, "--storagectl", "IDE", "--port", "0", "--device", "0", "--type", "dvddrive", "--medium", config.getVm().getBaseImage());
        
        // 부트 순서 설정
        executeVBoxCommand("modifyvm", vmName, "--boot1", "dvd", "--boot2", "disk");
        
        // 네트워크 설정 (NAT)
        executeVBoxCommand("modifyvm", vmName, "--nic1", "nat");
        
        log.info("VM 설정 완료: {}", vmName);
    }
    
    private void setupNetworking(String vmName, int webPort, int sshPort) throws Exception {
        log.info("네트워크 설정 중: {} - Web:{}, SSH:{}", vmName, webPort, sshPort);
        
        // 포트 포워딩 설정
        executeVBoxCommand("modifyvm", vmName, 
            "--natpf1", "web,tcp,," + webPort + ",,80",
            "--natpf1", "ssh,tcp,," + sshPort + ",,22"
        );
        
        log.info("포트 포워딩 설정 완료: {}", vmName);
    }
    
    private void startVM(String vmName) throws Exception {
        log.info("VM 시작 중: {}", vmName);
        
        executeVBoxCommand("startvm", vmName, "--type", "headless");
        
        log.info("VM 시작 완료: {}", vmName);
    }
    
    private String waitForVMIP(String vmName) {
        log.info("VM IP 대기 중: {}", vmName);
        
        for (int i = 0; i < 30; i++) { // 5분 대기
            try {
                Thread.sleep(10000); // 10초 대기
                
                ProcessBuilder pb = new ProcessBuilder("VBoxManage", "guestproperty", "get", vmName, "/VirtualBox/GuestInfo/Net/0/V4/IP");
                Process process = pb.start();
                
                BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                String output = reader.readLine();
                
                if (output != null && !output.contains("No value set")) {
                    String ip = output.split("Value: ")[1].trim();
                    log.info("VM IP 획득: {} -> {}", vmName, ip);
                    return ip;
                }
                
            } catch (Exception e) {
                log.warn("IP 확인 중 오류: {}", e.getMessage());
            }
        }
        
        log.warn("VM IP 획득 실패: {}", vmName);
        return "10.0.2.15"; // 기본 NAT IP
    }
    
    private String getVMUUID(String vmName) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("VBoxManage", "showvminfo", vmName, "--machinereadable");
        Process process = pb.start();
        
        BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
        String line;
        
        while ((line = reader.readLine()) != null) {
            if (line.startsWith("UUID=")) {
                return line.split("=")[1].replace("\"", "");
            }
        }
        
        throw new RuntimeException("VM UUID를 찾을 수 없습니다");
    }
    
    private void executeVBoxCommand(String... args) throws Exception {
        String[] command = new String[args.length + 1];
        command[0] = "VBoxManage";
        System.arraycopy(args, 0, command, 1, args.length);
        
        ProcessBuilder pb = new ProcessBuilder(command);
        Process process = pb.start();
        int exitCode = process.waitFor();
        
        if (exitCode != 0) {
            BufferedReader errorReader = new BufferedReader(new InputStreamReader(process.getErrorStream()));
            StringBuilder error = new StringBuilder();
            String line;
            while ((line = errorReader.readLine()) != null) {
                error.append(line).append("\n");
            }
            throw new RuntimeException("VBoxManage 명령 실패: " + error.toString());
        }
    }
    
    private int allocateWebPort() {
        return portCounter.getAndIncrement();
    }
    
    private int allocateSSHPort() {
        return sshPortCounter.getAndIncrement();
    }
    
    public CompletableFuture<Boolean> deleteVM(String vmId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                log.info("VM 삭제 시작: {}", vmId);
                
                // VM 정지
                executeVBoxCommand("controlvm", vmId, "poweroff");
                Thread.sleep(5000);
                
                // VM 삭제
                executeVBoxCommand("unregistervm", vmId, "--delete");
                
                log.info("VM 삭제 완료: {}", vmId);
                return true;
                
            } catch (Exception e) {
                log.error("VM 삭제 실패", e);
                return false;
            }
        });
    }
    
    public CompletableFuture<String> getVMStatus(String vmId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                ProcessBuilder pb = new ProcessBuilder("VBoxManage", "showvminfo", vmId, "--machinereadable");
                Process process = pb.start();
                
                BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                String line;
                
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("VMState=")) {
                        String state = line.split("=")[1].replace("\"", "");
                        return state.equals("running") ? "RUNNING" : "STOPPED";
                    }
                }
                
                return "ERROR";
            } catch (Exception e) {
                log.error("VM 상태 조회 실패", e);
                return "ERROR";
            }
        });
    }
    
    // VM 생성 결과 클래스
    public static class VmCreationResult {
        public final String vmId;
        public final String vmName;
        public final String vmIP;
        public final int port80;
        public final int port22;
        public final boolean success;
        public final String errorMessage;
        
        public VmCreationResult(String vmId, String vmName, String vmIP, int port80, int port22, boolean success, String errorMessage) {
            this.vmId = vmId;
            this.vmName = vmName;
            this.vmIP = vmIP;
            this.port80 = port80;
            this.port22 = port22;
            this.success = success;
            this.errorMessage = errorMessage;
        }
    }
}
