package com.peerlink.server.controller;

import com.peerlink.server.dto.UserResponse;
import com.peerlink.server.security.AuthenticatedUser;
import com.peerlink.server.service.AccountService;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class DashboardController {

    private static final DateTimeFormatter MEMBER_SINCE_FORMAT =
            DateTimeFormatter.ofPattern("d MMMM yyyy").withZone(ZoneId.systemDefault());

    private final AccountService accountService;

    public DashboardController(AccountService accountService) {
        this.accountService = accountService;
    }

    @GetMapping("/dashboard")
    public String dashboard(@AuthenticationPrincipal AuthenticatedUser user, Model model) {
        UserResponse account = accountService.getProfile(user.getId());
        model.addAttribute("account", account);
        model.addAttribute("memberSince", MEMBER_SINCE_FORMAT.format(account.createdAt()));
        return "dashboard";
    }
}
