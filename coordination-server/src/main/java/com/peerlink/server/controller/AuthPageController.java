package com.peerlink.server.controller;

import com.peerlink.server.dto.UserRegistrationRequest;
import com.peerlink.server.exception.AccountAlreadyExistsException;
import com.peerlink.server.service.RegistrationService;
import jakarta.validation.Valid;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;

/** Login and registration pages. The login form itself is processed by Spring Security. */
@Controller
public class AuthPageController {

    private static final String REGISTER_VIEW = "auth/register";

    private final RegistrationService registrationService;

    public AuthPageController(RegistrationService registrationService) {
        this.registrationService = registrationService;
    }

    @GetMapping("/login")
    public String login(Authentication authentication) {
        return isSignedIn(authentication) ? "redirect:/dashboard" : "auth/login";
    }

    @GetMapping("/register")
    public String registrationForm(Authentication authentication, Model model) {
        if (isSignedIn(authentication)) {
            return "redirect:/dashboard";
        }
        model.addAttribute("registration", new UserRegistrationRequest());
        return REGISTER_VIEW;
    }

    @PostMapping("/register")
    public String register(@Valid @ModelAttribute("registration") UserRegistrationRequest registration,
                           BindingResult bindingResult) {
        if (!bindingResult.hasErrors()) {
            try {
                registrationService.register(registration);
                return "redirect:/login?registered";
            } catch (AccountAlreadyExistsException e) {
                bindingResult.rejectValue(e.getField(), e.getCode().name(), e.getMessage());
            }
        }
        registration.clearPasswords();
        return REGISTER_VIEW;
    }

    private static boolean isSignedIn(Authentication authentication) {
        return authentication != null && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken);
    }
}
