package com.example.webhosting.controller;

import com.example.webhosting.dto.*;
import com.example.webhosting.entity.User;
import com.example.webhosting.service.UserService;
import com.example.webhosting.util.JwtUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;

@RestController
@CrossOrigin(origins = "*")
public class AuthController {
    
    @Autowired
    private UserService userService;
    
    @Autowired
    private JwtUtil jwtUtil;
    
    @PostMapping("/register")
    public ResponseEntity<ApiResponse<String>> register(@Valid @RequestBody UserRegistrationDto dto) {
        try {
            User user = userService.registerUser(dto);
            return ResponseEntity.ok(ApiResponse.success("회원가입이 완료되었습니다", user.getUsername()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }
    
    @PostMapping("/login")
    public ResponseEntity<ApiResponse<LoginResponse>> login(@Valid @RequestBody LoginDto dto) {
        try {
            User user = userService.authenticateUser(dto);
            String token = jwtUtil.generateToken(user.getUsername());
            
            LoginResponse response = new LoginResponse(token, user.getUsername());
            return ResponseEntity.ok(ApiResponse.success("로그인 성공", response));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }
    
}