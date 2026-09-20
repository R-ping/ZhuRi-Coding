package com.heima.model.user.dto;

import lombok.Data;

@Data
public class PasswordUpdateDTO {
    private String oldPassword;
    private String newPassword;
}