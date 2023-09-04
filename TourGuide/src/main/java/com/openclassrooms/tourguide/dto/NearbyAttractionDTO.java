package com.openclassrooms.tourguide.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class NearbyAttractionDTO {
    private String attractionName;
    private String attractionLatLong;
    private String userLatLong;
    private double distanceInMiles;
    private int rewardPoints;
}
