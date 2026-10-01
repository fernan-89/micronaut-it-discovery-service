package com.thinklab.domain.exception;

/**
 * Domain Exception: Thrown when an DiscoveredItem is initiated with a serial number that already exists
 * within the same Organisation scope.
 *
 * <p>RFC 7807 mapping: HTTP 409 Conflict.
 */
public class DuplicateDiscoveredItemException extends BusinessException {

    private static final String ERROR_CODE = "ERR-DSC-00409";

    public DuplicateDiscoveredItemException(String message) {
        super(ERROR_CODE, message);
    }
}
