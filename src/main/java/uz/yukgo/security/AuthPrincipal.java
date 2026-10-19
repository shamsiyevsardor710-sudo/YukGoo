package uz.yukgo.security;

import uz.yukgo.user.Role;

public record AuthPrincipal(Long id, Role role) {}
