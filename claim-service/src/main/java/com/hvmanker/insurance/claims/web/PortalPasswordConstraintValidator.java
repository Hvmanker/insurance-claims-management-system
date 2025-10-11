package com.hvmanker.insurance.claims.web;

import com.hvmanker.insurance.common.PortalPasswordValidator;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class PortalPasswordConstraintValidator
        implements ConstraintValidator<PortalPassword, String> {

    @Override
    public boolean isValid(String password, ConstraintValidatorContext context) {
        if (password == null) {
            return true;
        }
        var messages = PortalPasswordValidator.violationMessages(password);
        if (messages.isEmpty()) {
            return true;
        }
        context.disableDefaultConstraintViolation();
        for (String message : messages) {
            context.buildConstraintViolationWithTemplate(message).addConstraintViolation();
        }
        return false;
    }
}
