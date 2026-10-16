package com.example.rewards.account;

public class InsufficientPointsException extends RuntimeException {

    public InsufficientPointsException(String accountId, long requestedPoints, long availablePoints) {
        super("Insufficient points for account " + accountId + ": requested " + requestedPoints
                + ", available " + availablePoints);
    }
}