package com.openclassrooms.tourguide.service;

import com.openclassrooms.tourguide.dto.NearbyAttractionDTO;
import com.openclassrooms.tourguide.helper.InternalTestHelper;
import com.openclassrooms.tourguide.tracker.Tracker;
import com.openclassrooms.tourguide.user.User;
import com.openclassrooms.tourguide.user.UserReward;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import lombok.extern.log4j.Log4j2;
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

	/**
	 * List used to creat batch of users that can then be processed concurrently to optimize performance
	 */
	public final List<User> userBatch = Collections.synchronizedList(new ArrayList<>());

	//creating cash thread pool to call trackUserLocation method concurrently on multiple batches of users
	private final ExecutorService executorService = Executors.newFixedThreadPool(10);


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
		VisitedLocation visitedLocation;
		if(user.getVisitedLocations().size() > 0){
			visitedLocation = user.getLastVisitedLocation();
		}else {
			userBatch.add(user);
			visitedLocation = getUserVisitedLocationFromBatch(user);
		}
		return visitedLocation;
	}

	/**
	 * creates a defencive copy of the batch of users to work on, then clears the userBatch
	 * the new batch is then split in a defined maximum of users (USERS_PER_BATCH).
	 * processBatch method is called asynchronously on these batches
	 * the map return by each batched processed is then combined into one MAP and then returned
	 *
	 * @return a map of users and their visited location
	 */
	public Map<User, VisitedLocation>  processUserLocationsInBatch() {
		List<User> currentBatch;
		final int USERS_PER_BATCH = 200;

		synchronized (userBatch) {
			currentBatch = new ArrayList<>(userBatch);
			userBatch.clear();
		}

		// Calculate the number of batches.
		int batches = (int) Math.ceil((double) currentBatch.size() / USERS_PER_BATCH);

		// List to hold all the CompletableFuture objects
		List<CompletableFuture<Map<User, VisitedLocation>>> futuresList = new ArrayList<>();

		for (int i = 0; i < batches; i++) {
			int start = i * USERS_PER_BATCH;
			int end = Math.min(start + USERS_PER_BATCH, currentBatch.size());

			List<User> batch = currentBatch.subList(start, end);

			// Creating CompletableFuture for the batch and adding it to the list.
			CompletableFuture<Map<User, VisitedLocation>> futureBatch = CompletableFuture
					.supplyAsync(() -> processBatch(batch), executorService);
			futuresList.add(futureBatch);
		}

		// This will contain the final combined results.
		Map<User, VisitedLocation> resultMapUsersAndVisitedLocation = new HashMap<>();

		// Combine all results from each CompletableFuture
		for(CompletableFuture<Map<User, VisitedLocation>> future : futuresList) {
			try {
				resultMapUsersAndVisitedLocation.putAll(future.get());
			} catch (InterruptedException | ExecutionException e) {
				e.printStackTrace();
			}
		}

		return resultMapUsersAndVisitedLocation;

	}


	// This method processes a batch of users and returns a Map of User to VisitedLocation
	private Map<User, VisitedLocation> processBatch(List<User> batch) {
		Map<User, VisitedLocation> batchResult = new HashMap<>();

		for (User user : batch) {
			VisitedLocation visitedLocation = trackUserLocation(user);
			batchResult.put(user, visitedLocation);
		}

		return batchResult;
	}

	/**
	 * get user visited locations from map
	 * @param user
	 * @return the visited locations of a given user
	 */
	public VisitedLocation getUserVisitedLocationFromBatch(User user){
		Map<User, VisitedLocation> batchResult = processUserLocationsInBatch();
		return batchResult.get(user);
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
		rewardsService.addUserToBatchToCalculateRewardConcurrently(user);
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
