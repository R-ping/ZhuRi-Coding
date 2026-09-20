package com.heima.user.controller.v1;

import com.heima.model.common.dtos.ResponseResult;
import com.heima.model.user.dto.PasswordUpdateDTO;
import com.heima.model.user.dto.PrivacyMessageDTO;
import com.heima.user.service.AccountService;
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