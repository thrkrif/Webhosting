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
            
            // VM 부팅 완료 대기 (중요!)
            Thread.sleep(60000); // 60초 대기
            
            // 다양한 계정 정보 시도 (Ubuntu Live Server 환경 고려)
            String[][] credentials = {
                {"ubuntu", "ubuntu"},      // Ubuntu Live Server 기본
                {"ubuntu", ""},            // 빈 비밀번호
                {"ubuntu", "password"},    // 일반적인 기본값
                {"webuser", "webuser123"}, // 원래 설정값
                {"webuser", "webuser"},    // 간단한 비밀번호
                {"root", "root"},          // root 계정
                {"root", "ubuntu"},        // root with ubuntu password
                {"user", "user"}           // 일반 user 계정
            };
            
            Session session = null;
            String successUser = null;
            
            // SSH 연결 시도
            for (String[] cred : credentials) {
                try {
                    log.info("SSH 연결 시도: {}@localhost:{}", cred[0], sshPort);
                    JSch jsch = new JSch();
                    session = jsch.getSession(cred[0], "localhost", sshPort);
                    session.setPassword(cred[1]);
                    session.setConfig("StrictHostKeyChecking", "no");
                    session.setConfig("PreferredAuthentications", "password");
                    session.connect(30000); // 30초 타임아웃
                    
                    successUser = cred[0];
                    log.info("SSH 연결 성공: {}@{}:{} (비밀번호: '{}')", cred[0], vmIP, sshPort, 
                             cred[1].isEmpty() ? "빈 비밀번호" : cred[1]);
                    break;
                } catch (Exception e) {
                    log.warn("SSH 연결 실패 - {}:{} ({})", cred[0], 
                             cred[1].isEmpty() ? "빈 비밀번호" : cred[1], e.getMessage());
                    if (session != null && session.isConnected()) {
                        session.disconnect();
                    }
                    session = null;
                }
            }
            
            if (session == null || !session.isConnected()) {
                throw new RuntimeException("모든 SSH 연결 시도 실패");
            }
            
            // webuser 계정 생성 (ubuntu 계정으로 로그인한 경우)
            if ("ubuntu".equals(successUser)) {
                log.info("webuser 계정 생성 중...");
                String[] userSetupCommands = {
                    "sudo useradd -m -s /bin/bash webuser || true",  // 이미 존재하면 무시
                    "echo 'webuser:webuser123' | sudo chpasswd",
                    "sudo usermod -aG sudo webuser",
                    "sudo mkdir -p /home/webuser/.ssh",
                    "sudo chown webuser:webuser /home/webuser/.ssh",
                    "sudo chmod 700 /home/webuser/.ssh"
                };
                
                for (String command : userSetupCommands) {
                    executeSSHCommand(session, command);
                    Thread.sleep(1000);
                }
            }
            
            // 시스템 업데이트 및 nginx 설치
            log.info("시스템 업데이트 및 nginx 설치 중...");
            String[] installCommands = {
                "sudo apt update -y",
                "sudo apt install -y nginx",
                "sudo systemctl enable nginx",
                "sudo systemctl start nginx",
                "sudo systemctl status nginx --no-pager"
            };
            
            for (String command : installCommands) {
                executeSSHCommand(session, command);
                Thread.sleep(2000); // 패키지 설치는 시간이 걸릴 수 있음
            }
            
            // 웹사이트 디렉토리 및 콘텐츠 설정
            log.info("웹사이트 설정 중...");
            String[] webSetupCommands = {
                "sudo mkdir -p /var/www/html/" + hostName,
                "sudo chown -R www-data:www-data /var/www/html/" + hostName,
                "sudo chmod -R 755 /var/www/html/" + hostName
            };
            
            for (String command : webSetupCommands) {
                executeSSHCommand(session, command);
                Thread.sleep(500);
            }
            
            // HTML 콘텐츠 생성
            String htmlContent = String.format(
                "<html><head><title>%s</title><meta charset='UTF-8'></head>" +
                "<body style='font-family: Arial, sans-serif; margin: 40px; background: linear-gradient(135deg, #667eea 0%%, #764ba2 100%%); color: white;'>" +
                "<div style='background: rgba(255,255,255,0.1); padding: 30px; border-radius: 10px; backdrop-filter: blur(10px);'>" +
                "<h1 style='color: #fff; text-align: center;'>🎉 Welcome to %s! 🎉</h1>" +
                "<h2 style='color: #f0f0f0;'>웹 호스팅 서비스가 정상적으로 동작하고 있습니다!</h2>" +
                "<div style='background: rgba(255,255,255,0.2); padding: 20px; border-radius: 5px; margin: 20px 0;'>" +
                "<h3>서버 정보:</h3>" +
                "<ul><li><strong>VM IP:</strong> %s</li>" +
                "<li><strong>호스트명:</strong> %s</li>" +
                "<li><strong>웹 포트:</strong> 80 (포워딩: 8000)</li>" +
                "<li><strong>SSH 포트:</strong> 22 (포워딩: %d)</li>" +
                "<li><strong>생성 시간:</strong> %s</li></ul></div>" +
                "<p style='text-align: center; margin-top: 30px;'>" +
                "<span style='background: rgba(255,255,255,0.3); padding: 10px 20px; border-radius: 20px;'>" +
                "✅ 서비스 준비 완료</span></p></div></body></html>",
                hostName, hostName, vmIP, hostName, sshPort, new java.util.Date().toString()
            );
            
            // HTML 파일 생성 (이스케이프 처리)
            String createHtmlCommand = String.format(
                "echo '%s' | sudo tee /var/www/html/%s/index.html",
                htmlContent.replace("'", "'\"'\"'"), // 작은따옴표 이스케이프
                hostName
            );
            executeSSHCommand(session, createHtmlCommand);
            
            // nginx 기본 사이트 설정 (선택적)
            String nginxConfig = String.format(
                "server { listen 80 default_server; root /var/www/html/%s; index index.html; server_name _; location / { try_files $uri $uri/ =404; } }",
                hostName
            );
            
            String[] nginxCommands = {
                "echo '" + nginxConfig + "' | sudo tee /etc/nginx/sites-available/" + hostName,
                "sudo ln -sf /etc/nginx/sites-available/" + hostName + " /etc/nginx/sites-enabled/default",
                "sudo nginx -t", // 설정 파일 검사
                "sudo systemctl reload nginx"
            };
            
            for (String command : nginxCommands) {
                executeSSHCommand(session, command);
                Thread.sleep(1000);
            }
            
            // 방화벽 설정
            log.info("방화벽 설정 중...");
            String[] firewallCommands = {
                "sudo ufw --force reset",
                "sudo ufw allow 22/tcp",
                "sudo ufw allow 80/tcp",
                "sudo ufw allow 443/tcp",
                "sudo ufw --force enable",
                "sudo ufw status"
            };
            
            for (String command : firewallCommands) {
                executeSSHCommand(session, command);
                Thread.sleep(500);
            }
            
            // 최종 상태 확인
            log.info("서비스 상태 확인 중...");
            String[] statusCommands = {
                "sudo systemctl is-active nginx",
                "sudo systemctl is-enabled nginx",
                "curl -s localhost || echo 'Web test failed'",
                "ls -la /var/www/html/" + hostName + "/",
                "ps aux | grep nginx | head -3"
            };
            
            for (String command : statusCommands) {
                executeSSHCommand(session, command);
                Thread.sleep(500);
            }
            
            session.disconnect();
            log.info("웹서버 설정 완료 - {} (접속: http://localhost:8000)", hostName);
            
        } catch (Exception e) {
            log.error("웹서버 설정 실패 - {}: {}", hostName, e.getMessage(), e);
            throw new RuntimeException("웹서버 설정 실패: " + e.getMessage(), e);
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
        
        // 명령 완료 대기 (최대 60초)
        int timeout = 60000; // 60초
        int interval = 100; // 100ms
        int elapsed = 0;
        
        while (!channel.isClosed() && elapsed < timeout) {
            Thread.sleep(interval);
            elapsed += interval;
        }
        
        if (!channel.isClosed()) {
            log.warn("명령 실행 시간 초과: {}", command);
            channel.disconnect();
            return;
        }
        
        String output = out.toString().trim();
        String error = err.toString().trim();
        int exitStatus = channel.getExitStatus();
        
        if (!output.isEmpty()) {
            log.info("명령 출력: {}", output);
        }
        if (!error.isEmpty()) {
            if (exitStatus != 0) {
                log.error("명령 오류 (종료코드: {}): {}", exitStatus, error);
            } else {
                log.warn("명령 경고: {}", error);
            }
        }
        
        channel.disconnect();
        
        // 중요한 명령의 경우 실패시 예외 발생
        if (exitStatus != 0 && (command.contains("systemctl start nginx") || 
                                command.contains("apt install") || 
                                command.contains("nginx -t"))) {
            throw new RuntimeException("중요 명령 실패: " + command + " (종료코드: " + exitStatus + ")");
        }
    }
    
    // 추가: SSH 연결 테스트 메소드
    public boolean testSSHConnection(String vmIP, int sshPort) {
        String[][] credentials = {
            {"ubuntu", "ubuntu"},
            {"ubuntu", ""},
            {"webuser", "webuser123"}
        };
        
        for (String[] cred : credentials) {
            try {
                JSch jsch = new JSch();
                Session session = jsch.getSession(cred[0], "localhost", sshPort);
                session.setPassword(cred[1]);
                session.setConfig("StrictHostKeyChecking", "no");
                session.connect(10000); // 10초 타임아웃
                
                if (session.isConnected()) {
                    session.disconnect();
                    log.info("SSH 연결 테스트 성공: {}@{}:{}", cred[0], vmIP, sshPort);
                    return true;
                }
            } catch (Exception e) {
                log.debug("SSH 연결 테스트 실패: {}@{}:{} - {}", cred[0], vmIP, sshPort, e.getMessage());
            }
        }
        return false;
    }
}