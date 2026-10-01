package com.thinklab.domain.exception;

/**
 * Domain Exception: Indicates an illegal lifecycle transition or a business-rule violation on an
 * {@link com.thinklab.domain.model.DiscoveredItem} (for example, deploying an discoveredItem with no location, or
 * mutating a decommissioned discoveredItem).
 *
 * <p>RFC 7807 mapping: HTTP 409 Conflict (AST-03, ADR-019). The request is well formed but collides with
 * the aggregate's current state, the same contract used for every other state conflict on the platform.
 */
public class InvalidDiscoveredItemStatusException extends BusinessException {

    private static final String ERROR_CODE = "ERR-DSC-00409";

    public InvalidDiscoveredItemStatusException(String message) {
        super(ERROR_CODE, message);
    }
}
