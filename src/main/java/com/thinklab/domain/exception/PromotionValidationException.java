package com.thinklab.domain.exception;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Domain Exception: relays a 422 from {@code it-asset-registry-service} during
 * {@code control/promote} — the suggested {@code specifications} (this item's {@code rawAttributes})
 * violate the tenant's configured JSON Schema for the suggested category (ADR-032). This service never
 * re-implements that validation; it only relays the downstream verdict.
 *
 * <p>RFC 7807 mapping: HTTP 422 Unprocessable Entity, carrying the downstream {@code violations} list.
 */
public class PromotionValidationException extends BusinessException {

    private static final String ERROR_CODE = "ERR-DSC-00422";

    private final List<String> violations;

    public PromotionValidationException(String message, List<String> violations) {
        super(ERROR_CODE, message);
        Objects.requireNonNull(violations, "Violations cannot be null.");
        this.violations = List.copyOf(violations);
    }

    public List<String> getViolations() {
        return Collections.unmodifiableList(violations);
    }
}
