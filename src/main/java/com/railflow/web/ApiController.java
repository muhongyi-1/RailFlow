package com.railflow.web;

import com.railflow.common.ApiException;
import com.railflow.domain.*;
import com.railflow.order.OrderService;
import com.railflow.train.TrainService;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;
import java.util.*;

@RestController
public class ApiController {
    private final TrainService trains;
    private final OrderService orders;
    public ApiController(TrainService trains, OrderService orders) { this.trains = trains; this.orders = orders; }
    @GetMapping("/") public Map<String,String> root() { return Map.of("service", "RailFlow", "status", "running"); }
    @GetMapping("/health") public Map<String,String> health() { return Map.of("status", "ok"); }
    @GetMapping("/api/v1/trains/search")
    public List<TrainService.TrainResult> search(@RequestParam("from_station") String from, @RequestParam("to_station") String to,
            @RequestParam @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate date) { return trains.search(from,to,date); }
    @GetMapping("/api/v1/seats/available")
    public List<TrainService.SeatResult> seats(@RequestParam("train_id") long trainId,
            @RequestParam("start_station") int start, @RequestParam("end_station") int end) { return trains.available(trainId,start,end); }
    @PostMapping("/api/v1/orders") public Order create(@Valid @RequestBody BookingRequest request) { return orders.create(request); }
    @GetMapping("/api/v1/orders/{orderNo}") public Order order(@PathVariable String orderNo, @RequestParam("user_id") long userId) {
        if (userId <= 0) throw new ApiException(422, "user_id 必须大于 0");
        return orders.query(userId,orderNo);
    }
}
