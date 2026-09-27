package com.quickbite.order.api;

import com.quickbite.order.client.Clients.TrackPoint;

import java.util.List;

/** What the customer's map needs: the path so far, where the rider is now, and how far away. */
public record TrackResponse(String orderId, String status, String riderId,
                            List<TrackPoint> path, TrackPoint rider, TrackPoint destination,
                            Double distanceKm, Integer etaMinutes) {}
