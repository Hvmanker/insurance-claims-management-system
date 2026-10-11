package com.hvmanker.insurance.claims.web;

import com.hvmanker.insurance.claims.domain.PortalUser;
import com.hvmanker.insurance.claims.domain.UserRole;

public record CurrentUserResponse(String fullName, String email, UserRole role) {
    public static CurrentUserResponse from(PortalUser user) {
        return new CurrentUserResponse(user.getFullName(), user.getEmail(), user.getRole());
    }
}
