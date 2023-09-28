package com.openclassrooms.tourguide.exceptions;

import lombok.extern.log4j.Log4j2;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

import java.util.concurrent.ExecutionException;

@Log4j2
@ControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler({ExecutionException.class, InterruptedException.class})
    public ResponseEntity<String> handleExecutionAndInterrupted(Exception ex) {
        log.error("Exception caught: ", ex);
        return new ResponseEntity<>("Something went wrong, please try again later", HttpStatus.INTERNAL_SERVER_ERROR);
    }


    @ExceptionHandler(InterruptedRewardCalculationException.class)
    public ResponseEntity<String> handleCustomExecutionException(InterruptedRewardCalculationException irce) {
        log.error("Custom exception caught: ", irce);
        return new ResponseEntity<>("Something went wrong while finding your location. Please try again later", HttpStatus.INTERNAL_SERVER_ERROR);
    }

}
