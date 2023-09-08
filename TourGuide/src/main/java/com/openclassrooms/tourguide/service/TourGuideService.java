package com.openclassrooms.tourguide.service;

import com.openclassrooms.tourguide.dto.NearbyAttractionDTO;
import com.openclassrooms.tourguide.helper.InternalTestHelper;
import com.openclassrooms.tourguide.tracker.Tracker;
import com.openclassrooms.tourguide.user.User;
import com.openclassrooms.tourguide.user.UserReward;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import gpsUtil.GpsUtil;
import gpsUtil.location.Attraction;
import gpsUtil.location.Location;
import gpsUtil.location.VisitedLocation;

import tripPricer.Provider;
import tripPricer.TripPricer;

@Service
public class TourGuideService {
	private Logger logger = LoggerFactory.getLogger(TourGuideService.class);
	private final GpsUtil gpsUtil;
	private final RewardsService rewardsService;
	private final TripPricer tripPricer = new TripPricer();
	public final Tracker tracker;
	boolean testMode = true;

	public TourGuideService(GpsUtil gpsUtil, RewardsService rewardsService) {
		this.gpsUtil = gpsUtil;
		this.rewardsService = rewardsService;
		
		Locale.setDefault(Locale.US);

		if (testMode) {
			logger.info("TestMode enabled");
			logger.debug("Initializing users");
			initializeInternalUsers();
			logger.debug("Finished initializing users");
		}
		tracker = new Tracker(this);
		addShutDownHook();
	}

	public List<UserReward> getUserRewards(User user) {
		return user.getUserRewards();
	}

	public VisitedLocation getUserLocation(User user) {
		VisitedLocation visitedLocation = (user.getVisitedLocations().size() > 0) ? user.getLastVisitedLocation()
				: trackUserLocation(user);
		return visitedLocation;
	}

	public User getUser(String userName) {
		return internalUserMap.get(userName);
	}

	public List<User> getAllUsers() {
		return internalUserMap.values().stream().collect(Collectors.toList());
	}

	public void addUser(User user) {
		if (!internalUserMap.containsKey(user.getUserName())) {
			internalUserMap.put(user.getUserName(), user);
		}
	}

	public List<Provider> getTripDeals(User user) {
		int cumulatativeRewardPoints = user.getUserRewards().stream().mapToInt(i -> i.getRewardPoints()).sum();
		List<Provider> providers = tripPricer.getPrice(tripPricerApiKey, user.getUserId(),
				user.getUserPreferences().getNumberOfAdults(), user.getUserPreferences().getNumberOfChildren(),
				user.getUserPreferences().getTripDuration(), cumulatativeRewardPoints);
		user.setTripDeals(providers);
		return providers;
	}

	public VisitedLocation trackUserLocation(User user) {
		VisitedLocation visitedLocation = gpsUtil.getUserLocation(user.getUserId());
		user.addToVisitedLocations(visitedLocation);
		rewardsService.calculateRewards(user);
		return visitedLocation;
	}

	/**
	 * This method retrieves a SortedMap of attractions and their distance from VisitedLocation
	 *
	 * @param visitedLocation the visited location to find nearby attractions for.
	 * @return a SortedMap of the five closest attractions.
	 *
"	 * @author Ivano P
	 */
	public SortedMap<Double, Attraction> getAttractionsDistanceFromLocation(VisitedLocation visitedLocation) {
		//map to hold distance from visitedLocation to attraction
		TreeMap<Double, Attraction> attractionByDistance= new TreeMap<>();

		//Loop through list of attractions and map them with distances from visited location
		for(Attraction attraction : gpsUtil.getAttractions()){
			attractionByDistance.put(rewardsService.getDistance(visitedLocation.location, attraction), attraction);
		}

		return attractionByDistance;
	}

	/**
	 * This method takes a sorted map of attractions sorted by distance, a user, and a visited location.
	 * It then finds the five closest attractions to the visited location and returns them as a list
	 * of NearbyAttractionDTO objects.
	 *
	 * @param attractionAndDistance A sorted map where the keys are distances to attractions and the values are the attractions themselves.
	 * @param user The User object for whom we are finding the nearby attractions.
	 * @param visitedLocation The VisitedLocation object representing the user's current location.
	 * @return A list of the five closest attractions to the user's location, represented as NearbyAttractionDTO objects.
	 *
	 * @author Ivano P
	 */
	public List<NearbyAttractionDTO> getNearByAttractions(SortedMap<Double, Attraction> attractionAndDistance, User user,
															   VisitedLocation visitedLocation){
		List<NearbyAttractionDTO> fiveClosestAttractions = new ArrayList<>();

		int counter = 0;
		for (var entry : attractionAndDistance.entrySet()) {
			// Stop after collecting data for the closest five attractions
			if (counter >= 5) {
				break;
			}
			Attraction attraction = entry.getValue();
			Double distanceFromVisitedLocation = entry.getKey();
			int rewardPoints = rewardsService.getRewardPoints(attraction, user);
			String attractionLatLong = "Attraction's latitude: " + String.valueOf(attraction.latitude) + ", longitude: " +
					String.valueOf(attraction.longitude);
			String userLatLong = "User's latitude: " + String.valueOf(visitedLocation.location.latitude) +
					", longitude: " + String.valueOf(visitedLocation.location.longitude);

			fiveClosestAttractions.add(new NearbyAttractionDTO(attraction.attractionName, attractionLatLong,
					userLatLong, distanceFromVisitedLocation, rewardPoints));
			counter ++;
		}
		return fiveClosestAttractions;
	}


	private void addShutDownHook() {
		Runtime.getRuntime().addShutdownHook(new Thread() {
			public void run() {
				tracker.stopTracking();
			}
		});
	}

	/**********************************************************************************
	 * 
	 * Methods Below: For Internal Testing
	 * 
	 **********************************************************************************/
	private static final String tripPricerApiKey = "test-server-api-key";
	// Database connection will be used for external users, but for testing purposes
	// internal users are provided and stored in memory
	private final Map<String, User> internalUserMap = new HashMap<>();

	private void initializeInternalUsers() {
		IntStream.range(0, InternalTestHelper.getInternalUserNumber()).forEach(i -> {
			String userName = "internalUser" + i;
			String phone = "000";
			String email = userName + "@tourGuide.com";
			User user = new User(UUID.randomUUID(), userName, phone, email);
			generateUserLocationHistory(user);

			internalUserMap.put(userName, user);
		});
		logger.debug("Created " + InternalTestHelper.getInternalUserNumber() + " internal test users.");
	}

	private void generateUserLocationHistory(User user) {
		IntStream.range(0, 3).forEach(i -> {
			user.addToVisitedLocations(new VisitedLocation(user.getUserId(),
					new Location(generateRandomLatitude(), generateRandomLongitude()), getRandomTime()));
		});
	}

	private double generateRandomLongitude() {
		double leftLimit = -180;
		double rightLimit = 180;
		return leftLimit + new Random().nextDouble() * (rightLimit - leftLimit);
	}

	private double generateRandomLatitude() {
		double leftLimit = -85.05112878;
		double rightLimit = 85.05112878;
		return leftLimit + new Random().nextDouble() * (rightLimit - leftLimit);
	}

	private Date getRandomTime() {
		LocalDateTime localDateTime = LocalDateTime.now().minusDays(new Random().nextInt(30));
		return Date.from(localDateTime.toInstant(ZoneOffset.UTC));
	}

}