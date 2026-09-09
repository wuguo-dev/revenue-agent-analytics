package com.salesanalytics.security;

public record CurrentUser(Long id, String username, String displayName, String role, Long storeId) {}
