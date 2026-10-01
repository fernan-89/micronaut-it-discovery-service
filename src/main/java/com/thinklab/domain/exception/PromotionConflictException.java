package com.thinklab.domain.exception;

/**
 * Domain Exception: relays a 404 or 409 from {@code it-asset-registry-service} during
 * {@code control/promote} — a {@code matchedAssetId} that no longer resolves, or a serial-number
 * collision on a brand-new Asset (ADR-032). The item is left in {@code UNDER_REVIEW}; the caller
 * should re-review and re-link before retrying.
 *
 * <p>RFC 7807 mapping: HTTP 409 Conflict.
 */
public class PromotionConflictException extends BusinessException {

    private static final String ERROR_CODE = "ERR-DSC-00409";

    public PromotionConflictException(String message, Throwable cause) {
        super(ERROR_CODE, message, cause);
    }
}
