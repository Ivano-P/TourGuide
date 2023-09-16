package com.openclassrooms.tourguide;

import java.util.List;

import com.openclassrooms.tourguide.dto.NearbyAttractionDTO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import gpsUtil.location.VisitedLocation;

import com.openclassrooms.tourguide.service.TourGuideService;
import com.openclassrooms.tourguide.user.User;
import com.openclassrooms.tourguide.user.UserReward;

import tripPricer.Provider;

@RestController
public class TourGuideController {

	@Autowired
	TourGuideService tourGuideService;
	
    @RequestMapping("/")
    public String index() {
        return "Greetings from TourGuide!";
    }
    
    @RequestMapping("/getLocation") 
    public VisitedLocation getLocation(@RequestParam String userName) {
    	return tourGuideService.getUserLocation(getUser(userName));
    }


    /**
     * This endpoint receives a username as a request parameter and returns a list of the
     * five closest attractions to the user's current location. Each attraction is returned as
     * a NearbyAttractionDTO object.
     *
     * @param userName The username of the user for whom we are finding nearby attractions.
     * @return A list of NearbyAttractionDTO objects, representing the five closest attractions to the user's location.
     *
     * @author Ivano P
     */
    @RequestMapping("/getNearbyAttractions") 
    public List<NearbyAttractionDTO> getNearbyAttractions(@RequestParam String userName) {
        //get user location to search for 5 closest tourist attractions
    	VisitedLocation visitedLocation = tourGuideService.getUserLocation(getUser(userName));

        return tourGuideService.getNearByAttractions(
                tourGuideService.getAttractionsDistanceFromLocation(visitedLocation),
                getUser(userName),
                visitedLocation);
    }

    @RequestMapping("/getRewards") 
    public List<UserReward> getRewards(@RequestParam String userName) {
    	return tourGuideService.getUserRewards(getUser(userName));
    }
       
    @RequestMapping("/getTripDeals")
    public List<Provider> getTripDeals(@RequestParam String userName) {
    	return tourGuideService.getTripDeals(getUser(userName));
    }
    
    private User getUser(String userName) {
    	return tourGuideService.getUser(userName);
    }
   

}