package com.zhuri.coding.user.controller.v1;

import com.zhuri.coding.model.common.dtos.ResponseResult;
import com.zhuri.coding.model.user.dto.PasswordUpdateDTO;
import com.zhuri.coding.model.user.dto.PrivacyMessageDTO;
import com.zhuri.coding.user.service.AccountService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/user")
@Slf4j
public class AccountController {

    @Autowired
    private AccountService accountService;

    @GetMapping("/bindings")
    public ResponseResult getBindings() {
        return accountService.getBindings();
    }

    @PutMapping("/password")
    public ResponseResult updatePassword(@RequestBody PasswordUpdateDTO dto) {
        return accountService.updatePassword(dto);
    }

    @DeleteMapping("/account")
    public ResponseResult deleteAccount() {
        return accountService.deleteAccount();
    }

    @PutMapping("/privacy/message")
    public ResponseResult updatePrivacyMessage(@RequestBody PrivacyMessageDTO dto) {
        return accountService.updatePrivacyMessage(dto);
    }
}