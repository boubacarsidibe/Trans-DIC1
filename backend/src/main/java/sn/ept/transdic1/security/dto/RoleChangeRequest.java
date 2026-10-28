package sn.ept.transdic1.security.dto;

import jakarta.validation.constraints.NotNull;
import sn.ept.transdic1.security.domain.Role;

public record RoleChangeRequest(@NotNull Role role) {}
