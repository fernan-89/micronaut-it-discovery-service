package com.thinklab.application.dto.request;

import com.thinklab.domain.model.DiscoveredItem.AssetCategory;
import io.micronaut.serde.annotation.Serdeable;

import java.util.UUID;

/**
 * DTO for recording a reviewer's findings (BIAN Behavior Qualifier: {@code review/update}). Both
 * fields are optional and independently settable — a reviewer may record a category suggestion before
 * finding a matching existing Asset, or vice versa. {@code control/promote} enforces that
 * {@code suggestedCategory} is present before it allows the terminal transition.
 */
@Serdeable
public record ReviewUpdateDiscoveredItemRequest(
        AssetCategory suggestedCategory,
        UUID matchedAssetId
) {}
