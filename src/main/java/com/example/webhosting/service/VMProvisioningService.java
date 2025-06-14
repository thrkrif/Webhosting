package com.example.webhosting.service;

import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;

@Service
@Slf4j
public class VMProvisioningService {
    
    public void setupWebServer(String vmIP, String hostName, int sshPort) {
        try {
            log.info("웹서버 설정 시작 - VM IP: {}, 호스트명: {}, SSH 포트: {}", vmIP, hostName, sshPort);
            
            // SSH 연결 (학교 VM 환경에서는 password 인증 사용)
            JSch jsch = new JSch();
            Session session = jsch.getSession("webuser", "localhost", sshPort);
            session.setPassword("webuser123"); // VM 기본 패스워드
            session.setConfig("StrictHostKeyChecking", "no");
            session.connect();
            
            log.info("SSH 연결 성공: {}:{}", vmIP, sshPort);
            
            // 웹서버 설정 명령어들
            String[] commands = {
                "sudo apt update",
                "sudo apt install -y nginx",
                "sudo systemctl enable nginx",
                "sudo systemctl start nginx",
                "sudo mkdir -p /var/www/html/" + hostName,
                "sudo chown -R www-data:www-data /var/www/html/" + hostName,
                "echo '<h1>Welcome to " + hostName + "!</h1><p>웹 호스팅 서비스가 정상적으로 동작하고 있습니다.</p><p>VM IP: " + vmIP + "</p>' | sudo tee /var/www/html/" + hostName + "/index.html",
                "sudo systemctl reload nginx",
                "sudo ufw allow 22",
                "sudo ufw allow 80",
                "sudo ufw --force enable"
            };
            
            for (String command : commands) {
                executeSSHCommand(session, command);
                Thread.sleep(1000); // 명령어 간 대기
            }
            
            session.disconnect();
            log.info("웹서버 설정 완료 - {}", hostName);
            
        } catch (Exception e) {
            log.error("웹서버 설정 실패", e);
        }
    }
    
    private void executeSSHCommand(Session session, String command) throws Exception {
        log.info("SSH 명령 실행: {}", command);
        
        ChannelExec channel = (ChannelExec) session.openChannel("exec");
        channel.setCommand(command);
        
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        channel.setOutputStream(out);
        channel.setErrStream(err);
        
        channel.connect();
        
        while (!channel.isClosed()) {
            Thread.sleep(100);
        }
        
        String output = out.toString();
        String error = err.toString();
        
        if (!output.isEmpty()) {
            log.info("명령 출력: {}", output.trim());
        }
        if (!error.isEmpty()) {
            log.warn("명령 오류: {}", error.trim());
        }
        
        channel.disconnect(); // 이 부분이 빠져있었습니다!
    }
}