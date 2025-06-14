package com.example.webhosting.config;

import com.example.webhosting.util.JwtUtil;
import com.example.webhosting.service.UserService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;

// @Component 제거! SecurityConfig에서 @Bean으로 생성
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    
    private final JwtUtil jwtUtil;
    private final UserService userService;
    
    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        
        // Authorization 헤더에서 토큰 추출
        String authHeader = request.getHeader("Authorization");
        String token = jwtUtil.extractTokenFromHeader(authHeader);
        
        if (token != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            try {
                // 토큰 유효성 검증
                if (jwtUtil.validateToken(token)) {
                    String username = jwtUtil.getUsernameFromToken(token);
                    
                    // 사용자 정보 로드
                    try {
                        userService.findByUsername(username);
                        
                        // Spring Security 인증 객체 생성
                        UsernamePasswordAuthenticationToken authentication =
                            new UsernamePasswordAuthenticationToken(
                                username,
                                null,
                                new ArrayList<>() // 권한 목록
                            );
                        
                        authentication.setDetails(
                            new WebAuthenticationDetailsSource().buildDetails(request)
                        );
                        
                        // SecurityContext에 인증 정보 설정
                        SecurityContextHolder.getContext().setAuthentication(authentication);
                    } catch (IllegalArgumentException e) {
                        // 사용자가 존재하지 않는 경우
                        logger.warn("User not found: " + username);
                    }
                }
            } catch (Exception e) {
                // 토큰 검증 실패 시 로그 출력
                logger.error("JWT token validation failed: " + e.getMessage());
            }
        }
        
        filterChain.doFilter(request, response);
    }
    
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        // 인증이 필요하지 않은 경로들
        return path.equals("/register") ||
               path.equals("/login") ||
               path.equals("/") ||
               path.equals("/error") ||
               path.startsWith("/h2-console") ||
               path.startsWith("/static") ||
               path.equals("/favicon.ico");
    }
}