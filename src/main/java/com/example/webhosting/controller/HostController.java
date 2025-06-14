package com.example.webhosting.controller;

import com.example.webhosting.dto.*;
import com.example.webhosting.entity.User;
import com.example.webhosting.service.HostService;
import com.example.webhosting.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import jakarta.validation.Valid;
import java.util.List;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/host")
@CrossOrigin(origins = "*")
public class HostController {
    
    @Autowired
    private HostService hostService;
    
    @Autowired
    private UserService userService;
    
    private User getCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new IllegalArgumentException("로그인이 필요합니다");
        }
        String username = authentication.getName();
        return userService.findByUsername(username);
    }
    
    @PostMapping
    public CompletableFuture<ResponseEntity<ApiResponse<HostResponseDto>>> createHost(
            @Valid @RequestBody HostCreationDto dto) {
        try {
            User user = getCurrentUser();
            return hostService.createHost(dto, user)
                .thenApply(host -> ResponseEntity.ok(ApiResponse.success("웹 호스팅 생성이 시작되었습니다", host)))
                .exceptionally(ex -> ResponseEntity.badRequest().body(ApiResponse.error(ex.getMessage())));
        } catch (IllegalArgumentException e) {
            return CompletableFuture.completedFuture(
                ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()))
            );
        }
    }
    
    @GetMapping
    public ResponseEntity<ApiResponse<List<HostResponseDto>>> getHosts() {
        try {
            User user = getCurrentUser();
            List<HostResponseDto> hosts = hostService.getUserHosts(user);
            return ResponseEntity.ok(ApiResponse.success("호스트 목록 조회 성공", hosts));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }
    
    @DeleteMapping
    public CompletableFuture<ResponseEntity<ApiResponse<String>>> deleteHost(
            @RequestParam Long hostId) {
        try {
            User user = getCurrentUser();
            return hostService.deleteHost(hostId, user)
                .thenApply(success -> {
                    if (success) {
                        return ResponseEntity.ok(ApiResponse.success("호스트가 삭제되었습니다", null));
                    } else {
                        return ResponseEntity.badRequest().body(ApiResponse.error("호스트 삭제에 실패했습니다"));
                    }
                });
        } catch (IllegalArgumentException e) {
            return CompletableFuture.completedFuture(
                ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()))
            );
        }
    }
}