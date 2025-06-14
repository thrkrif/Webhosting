package com.example.webhosting.dto;

import lombok.Data;
import jakarta.validation.constraints.*;

@Data
public class HostCreationDto {
    @NotBlank(message = "호스트명은 필수입니다")
    @Size(min = 3, max = 50, message = "호스트명은 3-50자 사이여야 합니다")
    @Pattern(regexp = "^[a-zA-Z0-9-]+$", message = "호스트명은 영문, 숫자, 하이픈만 사용 가능합니다")
    private String hostName;
}