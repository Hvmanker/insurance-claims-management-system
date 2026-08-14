package com.sunil.insurance.claims.web;

import com.sunil.insurance.claims.domain.PortalUser;
import com.sunil.insurance.claims.domain.UserRole;

public record CurrentUserResponse(String fullName, String email, UserRole role) {
    public static CurrentUserResponse from(PortalUser user) {
        return new CurrentUserResponse(user.getFullName(), user.getEmail(), user.getRole());
    }
}
