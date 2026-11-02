package com.peerlink.server.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class PasswordsMatchValidator implements ConstraintValidator<PasswordsMatch, PasswordConfirmation> {

    @Override
    public boolean isValid(PasswordConfirmation form, ConstraintValidatorContext context) {
        if (form == null || form.getPassword() == null || form.getConfirmPassword() == null) {
            return true; // @NotBlank on the individual fields reports missing values
        }
        if (form.getPassword().equals(form.getConfirmPassword())) {
            return true;
        }
        // Attach the error to the confirmation field so forms and API clients can show it in place.
        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(context.getDefaultConstraintMessageTemplate())
                .addPropertyNode("confirmPassword")
                .addConstraintViolation();
        return false;
    }
}
