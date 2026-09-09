package com.salesanalytics.security;

import com.salesanalytics.common.BusinessException;
import org.springframework.security.core.context.SecurityContextHolder;

public final class SecurityUtils {
    private SecurityUtils() {}

    public static CurrentUser currentUser() {
        Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        if (principal instanceof CurrentUser user) return user;
        throw new BusinessException("UNAUTHENTICATED", "请先登录");
    }
}
