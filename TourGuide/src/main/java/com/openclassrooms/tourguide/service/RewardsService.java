package com.openclassrooms.tourguide.service;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;

import gpsUtil.GpsUtil;
import gpsUtil.location.Attraction;
import gpsUtil.location.Location;
import gpsUtil.location.VisitedLocation;
import rewardCentral.RewardCentral;
import com.openclassrooms.tourguide.user.User;
import com.openclassrooms.tourguide.user.UserReward;

@Service
public class RewardsService {
    private static final double STATUTE_MILES_PER_NAUTICAL_MILE = 1.15077945;

	// proximity in miles
    private int defaultProximityBuffer = 10;
	private int proximityBuffer = defaultProximityBuffer;
	private int attractionProximityRange = 200;
	private final GpsUtil gpsUtil;
	private final RewardCentral rewardsCentral;

	//creating cash thread pool to call calculateReward concurrently
	private final ExecutorService executorService = Executors.newFixedThreadPool(55);

	/**
	 * List used to creat batch of users that can then be processed concurrently to optimize performance
	 */
	public final List<User> userBatch = Collections.synchronizedList(new ArrayList<>());


	//this list is used to check if the CompletableFeatures added to it are all completed.
	private final List<CompletableFuture<Void>> tasks = Collections.synchronizedList(new ArrayList<>());

	/**
	 * this method is used for performance test in order to wait for Completable features to finished before checking
	 * assertions
	*/
	public void waitForAllTasksToComplete() {
		CompletableFuture<Void> allOf = CompletableFuture.allOf(tasks.toArray(new CompletableFuture[0]));
		allOf.join();
	}


	public RewardsService(GpsUtil gpsUtil, RewardCentral rewardCentral) {
		this.gpsUtil = gpsUtil;
		this.rewardsCentral = rewardCentral;
	}
	
	public void setProximityBuffer(int proximityBuffer) {
		this.proximityBuffer = proximityBuffer;
	}
	
	public void setDefaultProximityBuffer() {
		proximityBuffer = defaultProximityBuffer;
	}

	/**
	 * Adds user to batch before processing the batch concurrently
	 * @param user
	 */
	public synchronized void addUserToBatchToCalculateRewardConcurrently(User user){
		userBatch.add(user);
		calculateUserRewardsConcurrently();
	}

	/**
	 * creates a defencive copy of the batch of users to work on, then clears the userBatch
	 * the new batch is then split in a defined maximum of users (USERS_PER_BATCH).
	 * processBatch method is called asynchronously on these batches
	 */
	public void calculateUserRewardsConcurrently(){
		List<User> currentBatch;
		final int USERS_PER_BATCH = 200;

		synchronized (userBatch) {
			currentBatch = new ArrayList<>(userBatch);
			userBatch.clear();
		}

		// Calculate the number of batches.
		int batches = (int) Math.ceil((double) currentBatch.size() / USERS_PER_BATCH);

		for (int i = 0; i < batches; i++) {
			int start = i * USERS_PER_BATCH;
			int end = Math.min(start + USERS_PER_BATCH, currentBatch.size());

			List<User> batch = currentBatch.subList(start, end);

			// Creating CompletableFuture for the batch and adding it to the list.
			CompletableFuture<Void> future = CompletableFuture.runAsync(() -> processBatch(batch), executorService);
			tasks.add(future);
		}
	}

	// This method processes a batch of users by calling calculate reward for each user in batch
	private void processBatch(List<User> batch) {
		for (User user : batch) {
			calculateRewards(user);
		}
	}


	public void calculateRewards(User user) {
		List<VisitedLocation> userLocations = user.getVisitedLocations();
		List<Attraction> attractions = gpsUtil.getAttractions();

		//defensive copies to make sure the versions of the list we are iterating through are no longer being updated
		List<VisitedLocation> userLocationsCopy = new ArrayList<>(userLocations);
		List<Attraction> attractionsCopy = new ArrayList<>(attractions);

		for(VisitedLocation visitedLocation : userLocationsCopy) {
			for(Attraction attraction : attractionsCopy) {
				if(user.getUserRewards().stream().filter(r -> r.attraction.attractionName
						.equals(attraction.attractionName)).count() == 0) {
					if(nearAttraction(visitedLocation, attraction)) {
						user.addUserReward(new UserReward(visitedLocation, attraction, getRewardPoints(attraction, user)));
					}
				}
			}
		}
	}
	
	public boolean isWithinAttractionProximity(Attraction attraction, Location location) {
		return getDistance(attraction, location) > attractionProximityRange ? false : true;
	}
	
	private boolean nearAttraction(VisitedLocation visitedLocation, Attraction attraction) {
		return getDistance(attraction, visitedLocation.location) > proximityBuffer ? false : true;
	}

	//changed this from private to default to be able to call it from TourGuideService.class
	int getRewardPoints(Attraction attraction, User user) {
		return rewardsCentral.getAttractionRewardPoints(attraction.attractionId, user.getUserId());
	}
	
	public double getDistance(Location loc1, Location loc2) {
        double lat1 = Math.toRadians(loc1.latitude);
        double lon1 = Math.toRadians(loc1.longitude);
        double lat2 = Math.toRadians(loc2.latitude);
        double lon2 = Math.toRadians(loc2.longitude);

        double angle = Math.acos(Math.sin(lat1) * Math.sin(lat2)
                               + Math.cos(lat1) * Math.cos(lat2) * Math.cos(lon1 - lon2));

        double nauticalMiles = 60 * Math.toDegrees(angle);
        double statuteMiles = STATUTE_MILES_PER_NAUTICAL_MILE * nauticalMiles;
        return statuteMiles;
	}

}
